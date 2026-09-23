# Release-owner candidate preparation

This procedure prepares and audits **local** Maven artifacts. It never uploads,
tags, pushes, chooses a license, or configures a remote publisher. The repository
URL below is recorded intent, not evidence that the destination will accept the
candidate. Read `RELEASE-READINESS.md` for the other release gates.

Before staging, the owner must select:

- A coordinated release version (not the development default or a snapshot).
- A license, its canonical URL, and any required source-tree license/notice files.
  Setting a POM field does not itself license the repository.
- The actual HTTPS artifact repository and its repository-specific metadata,
  documentation, credential, signing and promotion requirements.
- Artifact signing policy: `none-approved` only when the owner and destination
  explicitly permit unsigned Maven artifacts; otherwise `external-openpgp`.
  Maven signing is separate from APK signing and the VPK root/signing keys.
- Whether that repository requires Javadoc JARs.

Store the decisions in an owner-created local JSON file (example shape; these are
placeholders, not project decisions):

```json
{
  "version": "OWNER_SELECTED_VERSION",
  "licenseName": "OWNER_SELECTED_LICENSE",
  "licenseUrl": "https://example.invalid/owner-selected-license",
  "repositoryUrl": "https://example.invalid/owner-selected-maven-repository",
  "artifactSigning": "external-openpgp",
  "requireJavadoc": true
}
```

Stage using the selected version and license, along with the environment described
in `README.md`:

```sh
PARAVOID_VERSION='<version>' \
PARAVOID_LICENSE_NAME='<license name>' \
PARAVOID_LICENSE_URL='<canonical HTTPS license URL>' \
bash release-tests/local-publication.sh
python3 release-tests/candidate.py --config /path/to/owner-decisions.json \
  --repository /tmp/paravoid-local-publication.XXXXXX > /tmp/candidate-report.json
```

Both license properties must be supplied together. Leaving both unset retains
development staging behavior without invented licensing. The Gradle script still
rejects non-file repositories. Credentials must not appear in the decisions file,
repository URL, command line, report or checked-in source.

The read-only audit requires Python 3.11+. It checks all six artifacts and five plugin marker POMs, exact
coordinate/license alignment, internal dependency versions, readable nonempty
binary/source ZIPs, and SHA-256 hashes. It optionally requires Javadoc artifacts.
Exit codes: `0` means these local checks passed, `1` means candidate deficiencies,
`2` means missing/invalid decisions or local inputs. It is not an archive safety
validator or proof of correspondence between source and binaries: use only the
trusted local staging output, and review/retain the build provenance separately.

Current publication supplies source JARs but **not Javadoc JARs**. A documentation
requirement therefore fails until implemented. `external-openpgp` reports missing
`.asc` files and always leaves an explicit owner verification gate: merely finding
signature files must never claim cryptographic verification or signer approval.
Remote staging, external signing and publication credentials intentionally remain
unconfigured until the owner supplies the destination/policy. This tool does not
waive those requirements to make the report green.

## Release notes and final owner checklist

Copy this outline into the actual version's release notes once decisions are final:

- Version / source commit / reproducible build inputs / candidate-report hashes.
- Supported Gradle, AGP, Java, Android API range and tested ABI/device matrix.
- Included shell/runtime, plugin, contract, optional Hilt and Work artifacts.
- Normal/shell packaging and updates configuration migration instructions.
- Behavior changes, known limitations and security review reference.
- Artifact destination, signature-verification instructions, source license/notices.

Before remote release: approve the actual source commit, run the standalone
consumer against that exact staged version, resolve every open readiness gate,
approve keys/credentials and retention/access policy, verify repository-specific
metadata/documentation/signatures, then separately authorize upload/promotion and
tagging. After an authorized upload, resolve the consumer from the actual remote
repository and compare downloaded hashes/signatures. A local report is never
remote publication evidence.

Tool regression: `python3 -m unittest discover -s release-tests -p test_candidate.py`.
