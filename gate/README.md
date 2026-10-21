# Release gate

`gate/baseline.json` records what the shipped APK (release variant, `releaseRuntimeClasspath`) can do: package, SDK
levels, backup and network flags, permissions, components, queries, native libraries, extra code, network API types,
host literals and the dependency list. CI (`.github/workflows/build.yml`) builds the release APK once per run, unsigned,
and `gate.py check` fails on any difference. A change to what the app can do therefore needs a change to
`baseline.json` in the same pull request, which the owner sees and merges (a gate change, CLAUDE.md rule 14).

Files:

- `gate.py` - the gate (Python 3 standard library). `generate`, `check`, `cert`, `inspect`, `fingerprint`; exit 0 match,
  1 differences, 2 could not check (never a pass).
- `test_gate.py` - its tests (`python3 -m unittest discover -s gate`), on synthetic APKs; they never call the SDK tools.
- `file-dependencies.init.gradle` - prints `files()`/`fileTree()` and desugaring dependencies per project.
- `annotate-file.sh` - prints a file as xz+base64 notice annotations, so an agent without artifact access can rebuild
  the generated baseline or the verification metadata from a run.
- `baseline.json` - this repo's baseline.

Provenance: the program's reference gate is Prikey's `gate/`, which other repos copy byte for byte. This repo's session
could not read Prikey, so `gate.py`, `test_gate.py`, `file-dependencies.init.gradle` and `annotate-file.sh` here were
written from the gate contract (the program's `specs/gate-baseline.md`, sections 1-3 and 1.1). Replace them with
Prikey's files when they can be copied; the baseline format is the same contract.

Not yet (milestone 4): the `sign`, `release-check` and `release` jobs, the certificate pin (`signing.cert_sha256` is
null until the first signed release) and therefore `--require-pin`. Until then CI uploads only an unsigned APK and a
test copy signed with a throwaway key made inside the job.

Local use (macOS or Windows builds use `--dependency-verification=lenient`; CI never does):

```sh
./gradlew --init-script gate/file-dependencies.init.gradle -Pgate.configuration=releaseRuntimeClasspath :app:assembleRelease :app:dependencies --configuration releaseRuntimeClasspath gateFileDependencies | tee gradle.log
python3 gate/gate.py check --apk app/build/outputs/apk/release/app-release-unsigned.apk --deps gradle.log
```

After a dependency change, delete `gradle/verification-metadata.xml` in the pull request; the next CI run writes it and
prints it as `verification-metadata-build` notices; commit it.
