#!/usr/bin/env python3
"""Read-only local candidate audit. Never uploads, signs, tags, or contacts a repository."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import sys
from urllib.parse import urlsplit
import xml.etree.ElementTree as ET
import zipfile

MODULES = ('paravoid-update-api', 'paravoid-recovery-api', 'paravoid-api', 'paravoid-contract', 'paravoid-runtime',
           'paravoid-gradle-plugin', 'paravoid-hilt', 'paravoid-work')
MARKERS = ('com.lelloman.paravoid', 'com.lelloman.paravoid.module',
           'com.lelloman.paravoid.shell', 'com.lelloman.paravoid.hilt',
           'com.lelloman.paravoid.work')
NS = {'m': 'http://maven.apache.org/POM/4.0.0'}


def config_check(config):
    if not isinstance(config, dict):
        raise ValueError('Owner decisions must be a JSON object')
    required = ('version', 'licenseName', 'licenseUrl', 'repositoryUrl',
                'artifactSigning', 'requireJavadoc')
    for key in required:
        if key not in config or config[key] in ('', None):
            raise ValueError(f'Missing owner decision: {key}')
    version = config['version']
    if not isinstance(version, str) or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._+-]*', version):
        raise ValueError('version must be a safe Maven version segment')
    if version.endswith('-dev') or 'SNAPSHOT' in version.upper():
        raise ValueError('Select an explicit non-development candidate version')
    if not isinstance(config['licenseName'], str) or not config['licenseName'].strip():
        raise ValueError('licenseName must be owner-approved nonempty text')
    for key in ('licenseUrl', 'repositoryUrl'):
        if not isinstance(config[key], str):
            raise ValueError(f'{key} must be an HTTPS URL string')
        parsed = urlsplit(config[key])
        if parsed.scheme != 'https' or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
            raise ValueError(f'{key} must be an HTTPS URL without credentials, query or fragment')
    if config['artifactSigning'] not in ('none-approved', 'external-openpgp'):
        raise ValueError('artifactSigning must be none-approved or external-openpgp')
    if type(config['requireJavadoc']) is not bool:
        raise ValueError('requireJavadoc must be an explicit boolean repository-policy choice')
    return config


def audit(config, repository):
    config_check(config)
    repository = Path(repository).resolve(strict=True)
    if not repository.is_dir():
        raise ValueError('Local staging repository must be a directory')
    version = config['version']
    failures, artifacts = [], []

    def inspect(path, archive=False):
        if not path.is_file():
            failures.append(f'Missing artifact: {path.relative_to(repository)}')
            return False
        if path.is_symlink() or not path.resolve().is_relative_to(repository):
            failures.append(f'Artifact escapes local repository: {path.name}')
            return False
        if archive:
            try:
                with zipfile.ZipFile(path) as z:
                    if not z.namelist() or z.testzip() is not None:
                        raise ValueError('empty or corrupt archive')
            except (ValueError, zipfile.BadZipFile) as e:
                failures.append(f'Invalid archive {path.name}: {e}')
        with path.open('rb') as stream:
            digest = hashlib.file_digest(stream, 'sha256').hexdigest()
        artifacts.append({'path': str(path.relative_to(repository)), 'sha256': digest, 'bytes': path.stat().st_size})
        if config['artifactSigning'] == 'external-openpgp' and not Path(str(path) + '.asc').is_file():
            failures.append(f'Missing external signature: {path.name}.asc')
        return True

    coordinates = [('com.lelloman.paravoid', name, 'aar' if name in ('paravoid-api', 'paravoid-runtime') else 'jar') for name in MODULES]
    coordinates += [(name, name + '.gradle.plugin', 'pom') for name in MARKERS]
    for group, artifact, packaging in coordinates:
        directory = repository / group.replace('.', '/') / artifact / version
        stem = directory / f'{artifact}-{version}'
        pom = Path(str(stem) + '.pom')
        if inspect(pom):
            try:
                root = ET.parse(pom).getroot()
                for key, expected in (('groupId', group), ('artifactId', artifact), ('version', version)):
                    if root.findtext('m:' + key, namespaces=NS) != expected:
                        failures.append(f'{pom.name}: incorrect {key}')
                for key, expected in (('name', config['licenseName']), ('url', config['licenseUrl'])):
                    if root.findtext('m:licenses/m:license/m:' + key, namespaces=NS) != expected:
                        failures.append(f'{pom.name}: license {key} does not match owner decision')
                for dependency in root.findall('m:dependencies/m:dependency', NS):
                    dep_group = dependency.findtext('m:groupId', namespaces=NS) or ''
                    if dep_group.startswith('com.lelloman.paravoid') and dependency.findtext('m:version', namespaces=NS) != version:
                        failures.append(f'{pom.name}: uncoordinated internal dependency version')
            except ET.ParseError:
                failures.append(f'Invalid POM: {pom.name}')
        if packaging != 'pom':
            inspect(Path(str(stem) + '.' + packaging), archive=True)
            inspect(Path(str(stem) + '-sources.jar'), archive=True)
            if config['requireJavadoc']:
                inspect(Path(str(stem) + '-javadoc.jar'), archive=True)
    if config['artifactSigning'] == 'external-openpgp':
        failures.append('External OpenPGP verification and approved signer fingerprint require release-owner verification; signature presence is not authentication')
    return {'scope': 'local candidate audit only; not release approval or remote repository acceptance',
            'version': version, 'repositoryUrl': config['repositoryUrl'],
            'artifactSigning': config['artifactSigning'], 'artifacts': artifacts, 'failures': failures}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', required=True, type=Path)
    parser.add_argument('--repository', required=True, type=Path)
    args = parser.parse_args()
    try:
        result = audit(json.loads(args.config.read_text()), args.repository)
        print(json.dumps(result, indent=2))
        return bool(result['failures'])
    except (ValueError, OSError, TypeError) as e:
        print(f'Candidate preflight refused: {e}', file=sys.stderr)
        return 2


if __name__ == '__main__':
    sys.exit(main())
