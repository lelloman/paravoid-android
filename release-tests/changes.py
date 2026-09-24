#!/usr/bin/env python3
"""Read versioned Paravoid changes, including versions skipped by a consumer."""
import argparse
from datetime import date
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parent.parent
HEADING = re.compile(r'^## \[([^\]]+)\](?: - (\d{4}-\d{2}-\d{2}))?\s*$')
VERSION = re.compile(r'^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$')


def sections(source):
    results = []
    for line in source.splitlines(keepends=True):
        match = HEADING.match(line.rstrip('\n'))
        if match:
            name, published = match.groups()
            if name != 'Unreleased' and not VERSION.fullmatch(name):
                raise ValueError(f'Invalid release version: {name}')
            if name == 'Unreleased' and (results or published):
                raise ValueError('Unreleased must be first and undated')
            if name != 'Unreleased':
                if published is None:
                    raise ValueError(f'Release {name} needs a date')
                try:
                    date.fromisoformat(published)
                except ValueError as error:
                    raise ValueError(f'Invalid date for {name}: {published}') from error
            if any(previous[0] == name for previous in results):
                raise ValueError(f'Duplicate release: {name}')
            results.append((name, line))
        elif results:
            name, body = results[-1]
            results[-1] = (name, body + line)
    if not results or results[0][0] != 'Unreleased':
        raise ValueError('Changelog must begin with ## [Unreleased]')
    for name, body in results[1:]:
        migration = re.search(r'^### Migration\n(.*?)(?=^### |^## |\Z)', body, re.MULTILINE | re.DOTALL)
        if migration is None or not re.search(r'^- .+', migration.group(1), re.MULTILINE):
            raise ValueError(f'Release {name} needs a Migration section')
        if not re.search(r'^- .+', body, re.MULTILINE):
            raise ValueError(f'Release {name} has no changes')
    return results


def resolve_version(value, names):
    if value in names:
        return value
    if value.startswith('v') and value[1:] in names:
        return value[1:]
    return None


def changes_between(changelog, from_version, to_version):
    entries = sections(changelog)
    names = [name for name, _ in entries]
    target = resolve_version(to_version, names)
    previous = resolve_version(from_version, names) if from_version is not None else None
    if target is None:
        raise ValueError(f'Unknown target version: {to_version}')
    if from_version is not None and previous is None:
        raise ValueError(f'Unknown installed version: {from_version}')
    start = names.index(target)
    end = names.index(previous) if previous is not None else len(names)
    if start >= end:
        raise ValueError('Target version must be newer than installed version')
    label = from_version or 'first installation'
    return f'# Paravoid changes: {label} → {to_version}\n\n' + '\n'.join(
        body.rstrip() for _, body in entries[start:end]) + '\n'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--changelog', type=Path, default=ROOT / 'CHANGELOG.md')
    parser.add_argument('--from', dest='from_version', help='currently installed Paravoid version')
    parser.add_argument('--to', dest='to_version', help='target version, or Unreleased for a preview')
    parser.add_argument('--verify', metavar='VERSION', help='verify a release has dated notes and migration guidance')
    args = parser.parse_args()
    try:
        source = args.changelog.read_text(encoding='utf-8')
        entries = sections(source)
        if args.verify:
            if args.verify == 'Unreleased' or resolve_version(args.verify, [name for name, _ in entries]) is None:
                raise ValueError(f'No release notes for {args.verify}')
            print(f'Release notes present for {args.verify}')
        else:
            if not args.to_version:
                parser.error('--to is required unless --verify is used')
            print(changes_between(source, args.from_version, args.to_version), end='')
        return 0
    except (OSError, ValueError) as error:
        print(f'Release notes unavailable: {error}', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
