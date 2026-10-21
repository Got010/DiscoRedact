# Release gate

`baseline.json` records what DiscoRedact's shipped APK (the release build, `releaseRuntimeClasspath`) can do:
package, SDK levels, `debuggable`, backup and cleartext-traffic flags, the network security config, permissions
(with `maxSdkVersion` and `usesPermissionFlags`), package queries, components another app or the system can reach
(exported ones, plus every service, receiver and provider), native libraries and code outside the main dex files,
network API types, host and URL literals, the runtime and file dependencies, and the signing certificate pin. CI
(`.github/workflows/build.yml`) builds the release APK once per run, unsigned, runs `gate.py check` against this
file and fails on any difference. A change to what the app can do therefore needs a change to `baseline.json` in the
same pull request, which the owner sees and merges (a gate change, CLAUDE.md rule 14). In a pull request the gate
also lists, as `BASELINE` warnings, every field the pull request changes in `baseline.json`.

## Provenance

`gate.py`, `test_gate.py`, `file-dependencies.init.gradle`, `annotate-file.sh` and `testdata/` are copied byte for
byte from Prikey's `gate/` (Prikey `main` at 5e4c4b4, gate 2.1, baseline schema 2), as every program repo does; a fix
to them goes to Prikey first and is then copied here. `baseline.json` and this README are DiscoRedact's own. The full
contract is `specs/gate-baseline.md` in the program repo.

## Commands

```
gate.py generate    --apk APK --deps LOG [--baseline FILE] [--out FILE] [--require-tools]
gate.py check       --apk APK --deps LOG [--baseline FILE] [--generated FILE] [--require-tools] [--require-pin] [--compare-to BASE]
gate.py cert        --apk APK [--baseline FILE] [--require-pin]
gate.py inspect     --apk APK [--require-tools]
gate.py fingerprint --apk APK
gate.py --version
```

Exit 0 match, 1 differences, 2 the check could not run (never a pass). `LOG` is the Gradle output holding the
`releaseRuntimeClasspath` dependency report and the `gateFileDependencies` lines from the init script. CI runs:

```
./gradlew --dependency-verification=strict --init-script gate/file-dependencies.init.gradle -Pgate.configuration=releaseRuntimeClasspath :core:test :app:testReleaseUnitTest :app:assembleRelease :app:dependencies --configuration releaseRuntimeClasspath gateFileDependencies | tee gradle.log
python3 gate/gate.py check --require-tools --apk app/build/outputs/apk/release/app-release-unsigned.apk --deps gradle.log --generated build/gate/baseline.generated.json --compare-to .gate-base/gate/baseline.json
python3 -m unittest discover -s gate
```

Not yet (milestone 4): the `sign`, `release-check` and `release` jobs and the certificate pin
(`signing.cert_sha256` stays null until the first signed release), so CI does not pass `--require-pin`. Until then CI
uploads the unsigned APK and a test copy signed with a throwaway key made inside the job, for the owner's phone checks.

## When the gate fails

The log lists each difference as `GATE + field: value`, `GATE - field: value` or `GATE ~ field: old -> new`, then
prints the baseline this APK would need, also as `baseline-generated` notice annotations. If the change is intended,
replace `baseline.json` with it in the pull request, give every new `hosts` or `network_api` entry a `note` (an entry
without one fails the check), and list the change under GATE CHANGES in the pull request description.

## Bringing a file back from CI without downloading artifacts

Agent sessions cannot download artifacts, so CI prints generated files xz-compressed and base64-encoded in numbered
notice annotations titled `<label> <i>/<n> <sha256>` (`annotate-file.sh`): `baseline-generated`,
`verification-metadata-build` (when `gradle/verification-metadata.xml` is missing) and `room-schema-<version>` (when
the committed Room schema differs from the code). Read the check run's annotations through the API, keep the chunks
with the wanted label and digest, join them in order, decode, decompress and compare the SHA-256 with the title.

## Dependency verification metadata

`gradle/verification-metadata.xml` pins the SHA-256 of every artifact; CI runs Gradle with
`--dependency-verification=strict`. It was written on Linux; a local build on macOS or Windows needs
`--dependency-verification=lenient` (never in CI). After a dependency change, delete the file in the pull request:
the next run writes it, prints it as notices and fails until it is committed.
