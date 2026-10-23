# Release gate

`baseline.json` records what DiscoRedact's shipped APK (the release build, `releaseRuntimeClasspath`) can do:
package, SDK levels, `debuggable`, backup and cleartext-traffic flags, the network security config, permissions
(with `maxSdkVersion` and `usesPermissionFlags`), package queries, components another app or the system can reach
(exported ones, plus every service, receiver and provider), native libraries and code outside the main dex files,
network API types, host and URL literals, the runtime and file dependencies, and the signing certificate pin. CI
(`.github/workflows/build.yml`) builds the release APK once per run, unsigned, runs `gate.py check` against this
file and fails on any difference. A change to what the app can do therefore needs a change to `baseline.json` in the
same pull request, which the owner sees and merges (a gate change, CLAUDE.md rule 14). In a pull request the gate
also lists, as `BASELINE` warnings, every field the pull request changes in `baseline.json`. The signed release is
that same unsigned APK, signed after the merge (see Releases).

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
./gradlew --dependency-verification=strict --init-script gate/file-dependencies.init.gradle -Pgate.configuration=releaseRuntimeClasspath :core:test :net:test :app:testReleaseUnitTest :app:assembleRelease :app:dependencies --configuration releaseRuntimeClasspath gateFileDependencies | tee gradle.log
python3 gate/gate.py check --require-tools --apk app/build/outputs/apk/release/app-release-unsigned.apk --deps gradle.log --generated build/gate/baseline.generated.json --compare-to .gate-base/gate/baseline.json
python3 -m unittest discover -s gate
```

## Releases

`VERSION` at the repo root (`X.Y.Z`) is the app's version: `versionName` is its text and `versionCode` is
`major * 1_000_000 + minor * 1_000 + patch` (0.1.0 is 1000; minor and patch stay below 1000). A change to `VERSION`
is a gate change, and the next merge to `main` after it is a release. Only a push to `main` can sign or publish;
pull requests build unsigned and never reach the jobs below. `build.yml` runs, in order:

1. `build` (no secret, no writing token): builds, tests and gates the unsigned APK, then rehearses the signing with a
   throwaway key made in the job, using the pinned build-tools (`BUILD_TOOLS_VERSION`), the release's signature
   schemes (`SIGNING_SCHEMES`: v2 only) and the keystore type (`KEYSTORE_TYPE`), and checks apksigner's
   `Verified using` lines against the schemes. The rehearsal's APK, signed with the throwaway key, is uploaded in the
   `apks` artifact for the owner's phone checks and is never published. It exposes the commit and `VERSION` as
   outputs.
2. `plan` (no checkout): publishes only when `v<VERSION>` has no release and no tag and is not lower than the highest
   release. A release that already exists ends the run with a notice (bump `VERSION` to publish). A tag without a
   release, or a lower version, fails the run.
3. `sign` (environment `publish`): the only job that references the signing secrets. It checks out nothing and runs
   no repository code. After the owner approves the deployment it checks that the commit is on `main`, then
   decodes the keystore, aligns and signs the unsigned APK of the `apks` artifact with the pinned build-tools,
   removes the keystore and verifies the result. The signed APK goes to the `signed-apk` artifact.
4. `release-check` (read-only): checks out `gate/` of the built commit and runs
   `gate.py cert --require-pin --apk <signed APK>`, which fails for an unsigned APK, a different certificate or a
   baseline without a pin, and outputs the APK's SHA-256.
5. `publish` (environment `publish`, `contents: write`, no checkout, no repository code): refuses an APK whose
   SHA-256 is not the one `release-check` checked, verifies it with the pinned `apksigner` and `aapt2` (package and
   `versionName` against the tag), and creates the release `v<VERSION>` with `gh release create`, which also creates
   the tag. There is no tag-push trigger and no overwrite path: a version is released once.

Both `sign` and `publish` use the environment `publish`, so GitHub asks the owner to approve twice per release.
Agents never approve a deployment, dispatch a publishing run or push a tag (CLAUDE.md rule 13).

The environment `publish` (repository settings, created by the owner) has the required reviewer `Got010`, deployment
branches limited to `main`, and four environment secrets: `DISCOREDACT_KEYSTORE_BASE64`,
`DISCOREDACT_KEYSTORE_PASSWORD`, `DISCOREDACT_KEY_ALIAS` and `DISCOREDACT_KEY_PASSWORD`. The workflow names them in the
signing step of `sign` only (`grep -n 'secrets\.' .github/workflows/*.yml`).

The certificate pin, `signing.cert_sha256`, is the SHA-256 of the release certificate, taken from the owner's keystore
with `keytool` (or from a published APK with `gate.py fingerprint --apk`). Changing it is a gate change. While it is
`null`, `release-check` always fails, so nothing can be published before the pin exists. When the pin is set, the
`check` step in `build.yml` also gets `--require-pin`, which only checks that the baseline holds a pin; the certificate
itself is compared by `cert` on the signed APK in `release-check`.

## R8 and the network API set

The `network_api` entries are what the shipped dex references after R8 has shrunk OkHttp, so the java.net and
javax.net.ssl set can change with unrelated code (24 to 44 between M2 and M3). Pinning it with keep rules was measured
and rejected in M4: `-keep class okhttp3.** { *; }` listed 375 OkHttp types, and `-keep,allowobfuscation` still listed
345 renamed ones, because R8 does not repackage kept classes out of `okhttp3/` and the gate matches that prefix. A
change in the set is therefore reviewed as such, with a note per entry. The build log's group "URL literals in the dex
files" lists every printable string with `://` in the unsigned APK, which is how a `hosts` note names the library
behind each literal.

## The sign-in screen's WebView

From 0.2.0 the app shows Discord's login page in a WebView (`app/discoredact/ui/signin/`) to capture the five headers on
the phone. The WebView is a platform API: no permission, component, dependency or native library comes with it. What
the baseline gains:

- `network_api`: the `android.webkit` types the sign-in page uses (`WebView`, `WebViewClient`, `WebSettings`,
  `WebResourceRequest`, `WebResourceResponse`, `CookieManager`, `WebStorage`, `WebViewDatabase`,
  `ServiceWorkerController`, `ServiceWorkerClient`, `ServiceWorkerWebSettings`, and any type their called methods'
  signatures name, such as `ValueCallback`), each with a note; the CI run lists the exact set.
- `hosts`: the WebView's own allowlist, `SignInHosts.HOSTS` in `:core` (`discord.com`, `discordapp.com`,
  `discordapp.net`, `hcaptcha.com`), each with a note. The filter admits only https, these hosts and their subdomains,
  with no port and no user info; everything else gets an empty 403. The app's own client keeps its one-host allowlist
  (`HostAllowlist` in `:net`), which this list never reaches.

`NoNetworkCodeTest` allows `android.webkit` and `WebView` in exactly `ui/signin/SignInSession.kt`, and `WebView` (as a
directory name) in `data/SignInStorage.kt`, the WebView-free clearer that Wipe everything calls; every other main source
of `:app` and `:core` still names no network API.

Known limits of the WebView filter. The filter refuses every request the WebView API shows it; what the API does not
show it, it cannot refuse or list under Blocked:

- a WebSocket the page opens (Discord's login page may open its QR-login socket before sign-in, and the client opens
  its gateway socket after it);
- a redirect of a subresource or a fetch/XHR from an admitted host to another host (main-frame redirects do reach the
  navigation check, which refuses them);
- preconnect and DNS prefetch (lookups and handshakes with no request);
- WebRTC (STUN/TURN over UDP), and WebTransport;
- the WebView provider's own traffic (metrics, variations, component updates; off on GrapheneOS's Vanadium).

Closing the page the moment the capture lands, and whenever the screen is left or the app goes to the background,
limits these; it does not prevent them. The app's own client never opens any of them.

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
