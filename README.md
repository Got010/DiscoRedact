# DiscoRedact

An Android app for one phone (GrapheneOS, Pixel) that deletes the owner's own messages from Discord DMs and
group DMs on an alternate account. Message IDs come from Discord's official data package or, from 0.3.0, from the
account's own open DMs read through Discord's API ("Fetch from Discord", below). Requests are slow, sequential and
header-consistent, one in flight at a time, far under every documented limit.

This automates a user token against Discord's API, which Discord's terms forbid however it is paced. The design
reduces the signals; it does not make the activity allowed. Alternate account only.

The build spec and program rules live in the owner's private program repo. `CLAUDE.md` carries the hard rules
every change must keep.

## Status

Milestone 1: import, local database, channel list, message browser, keep/select, filters, per-DM modes and the
final review that freezes a run's delete set. Milestone 2: the executor (one request in flight, the delete guard
before every send, the pacing presets, the response table, the tripwire, per-message status written before the
next message), the dry run, the Run screen, and the one OkHttp client with its `discord.com` allowlist. Milestone 3:
the credential import ("Copy as cURL" from a desktop browser, or the five fields by hand), the Keystore AES-GCM store,
the capture age with the 14-day staleness warning, and the wipe; the canary (5 real deletes, verified in the client)
passed. Milestone 4: the Reports screen, the leftovers CSV export, the fuller wipe, and signed releases on GitHub
Releases (see Releases below). 0.2.0: the sign-in on the phone itself, so the capture needs no desktop (below).
0.3.0: Fetch from Discord, a second import that makes the data package optional (below).

## Signing in on the phone

Credentials, "Sign in here" opens Discord's own login page in a WebView inside the app. Discord's page handles the
password, any second factor and any captcha; the app never sees the password. The page may load only over https from
`discord.com`, `discordapp.com`, `discordapp.net` and `hcaptcha.com` (and their subdomains); every other request is
refused and its host name is listed on the screen under "Blocked". When one of the page's own requests to
`https://discord.com/api/` carries the five headers (`Authorization`, `User-Agent`, `X-Super-Properties`,
`X-Discord-Locale`, `X-Discord-Timezone`), the app stores them in the same Keystore-encrypted store as a cURL import,
then clears everything the page stored (cookies, site storage, cache, history, form data) and destroys the WebView.
The same clear runs on Close, whenever the screen is left, and two minutes after the app goes to the background (time
to read a code in another app; coming back sooner keeps the page; if the phone sleeps through the two minutes, the
page ends at the next wake, and if the app is killed, its data goes at the next start); Wipe everything also deletes
the WebView's data directories, and a page never loads over data an earlier one left that could not be deleted. "Sign
in here" is off while a run is active or a wipe is running. The filter's known limits are in `gate/README.md`. The app runs no script
in the page and reads request headers only. The executor, the transport and its `discord.com` allowlist are unchanged:
deletes still go through the app's own client. The cURL paste stays as the fallback.

## Fetch from Discord

Channels, Import, "Fetch from Discord" shows a notice first, then reads with the stored headers, one request at a time:
the account's own id (`GET /users/@me`), the list of its open DMs and group DMs (`GET /users/@me/channels`), and each
DM's history, 100 messages per request, newest first (`GET /channels/{id}/messages?limit=100`, then `&before=` the
oldest id read, until a page has fewer than 100). Only messages whose author is the account are kept; everyone else's
are read and dropped in memory. They go into the same channel and message lists the data package fills, so browsing,
filters, Keep, the final review, the dry run and the deletes work on them unchanged. A message found by both imports is
one row (marked "both"); neither import changes a row's selection, Keep mark or status.

What it cannot find: Discord lists only the DMs that are open in the DM list. A DM the account closed (the X beside it)
or a group DM it left is not listed, although its messages still exist; the data package is still the way to them.
The package is optional, not replaced.

The reads are paced and halted exactly as the deletes: the preset's delay and jitter, the pause between DMs, Discord's
rate-limit headers, quiet hours, the daily cap (a fetch counts the day's reads and deletes together), the tripwire at
five invalid responses in ten minutes, and the halts on 401, a captcha, an edge block or an unknown error. Each DM's
progress (the oldest id read, done or not) is written per page before the next request, so a kill or reboot resumes
with no page read twice. A fetch is not a run: it deletes nothing and leaves the Dry run setting as it is. A fetch needs
stored credentials and does not start beside an active run (nor a run beside an active fetch). About one request per
100 messages (everyone's) per DM, plus one per DM, plus two; at the Slower preset 1,000 reads take about 75 minutes.

## Layout

- `core/` - plain Kotlin (JVM): the data-package parser, the selection rules (keep > manual toggle > filter, the
  frozen set and the delete guard), the pacing presets, the response classifier and the executor (the transport is
  an interface; `:core` names no network API), and the Fetch import's pure parts (`history/`: the author filter
  `HistoryRows.own`, the DM list and pagination, and `FetchSession`, the read-side executor). JVM tests: `./gradlew :core:test`; the executor's fakes are test
  fixtures shared with `:net`.
- `net/` - plain Kotlin (JVM): the one OkHttp client, the host allowlist with the request-shape check (the delete
  and the three reads, nothing else), the `DELETE` request and the Fetch import's `GET`s with the captured headers
  sent verbatim. The only module that names a network API. Its tests run the executor through the real
  client against a mock server. JVM tests: `./gradlew :net:test`.
- `app/` - Android (Compose, Room, WorkManager): screens, database, the run worker, glue. Room schemas are
  committed under `app/schemas/`.
- `gate/` - the release gate (see `gate/README.md`), Prikey's gate 2.1 files byte for byte.
- `tools/make-sample-package.py` - writes a synthetic data package (nothing real) for phone checks; CI uploads it as
  the `sample-package` artifact.

## Runs

A run is frozen at the final review and started as a dry run or a real run (the Dry run toggle at that moment; the
choice is written to the run and kept by every resume). The worker is a `dataSync` foreground service: one session of
the preset's length, then a break waited inside the worker, until quiet hours, the daily cap, a halt, a pause, a stop
or the end. A session ending for quiet hours or the cap enqueues the next one for the right time; Android lets a
foreground service start only while the app is in front, so if that time comes with the app closed, a notification
asks to open it and the run continues on the next open. A dry run walks the same order through the same guard with
no pacing, no quiet hours and no cap, sends nothing, and records a verdict per message. Pause and Stop wait for the
request in flight and its row; a kill or reboot resumes from the per-message status with no repeat and no skip. A
run that was started and not ended is resumable and takes precedence over a newer frozen run: Stop it first to start
another.

## Data model

Room tables `channels`, `messages`, `runs` and `events` are the spec's section 5. Three side tables hold what section
5 has no column for: `run_messages` (a run's frozen delete set, in run order, written once when the final review is
confirmed), `selection_overrides` (manual toggles, which filters never override) and `channel_inclusion` (the channel
list's include/exclude). A frozen run has `started_at` null until the executor starts it. Freezing a new run closes
every run that never started (`ended_at` set, `stop_reason` `superseded`), so the newest unstarted run is the only
runnable one; the executor relies on this (`RunRules` in `:core`). A run that was started but never ended (interrupted,
paused, halted or waiting) is neither superseded nor "runnable": it is resumable, and it comes before a newer frozen
run (Stop it to start another). Schema 2 adds `run_options` (dry run, order, a hold before resuming), `run_results`
(a dry run's verdicts) and `delete_log` (every real deletion; the daily cap counts these since local midnight).
The `events` log keeps its newest 5,000 rows and never holds a header value or a message's text. Schema 3 (0.3.0) adds
`source` to `channels` and `messages` (`PACKAGE`, `API` or `BOTH`; every row from before is `PACKAGE`) and
`fetch_progress` (each listed DM's oldest id read and whether it is done); a fetch's events are the log's rows with no
run, and the fetch's own state, the account id and the day's read count live in the settings file.

Builds run in CI (`.github/workflows/build.yml`). Dependencies are pinned by SHA-256 in
`gradle/verification-metadata.xml` and enforced with `--dependency-verification=strict`; local builds on macOS or
Windows use `--dependency-verification=lenient`, CI never does.

## Releases

Signed builds are published on this repository's GitHub Releases page, one release per version (`v0.1.0`, ...), each
with one file, `discoredact-v<version>.apk`. Install on the phone: open the Releases page in the browser, download the
APK of the latest release and open it (allow "Install unknown apps" for the browser or Files app once). The release
notes carry the APK's SHA-256 and the signing certificate's SHA-256.

- **Updates install in place.** Every release is signed with the same key, so a newer release installs over the older
  one and keeps its data.
- **The first signed build does not install over a test-key build.** CI also uploads copies signed with a throwaway key
  (the `apks` artifact of a build run, named `discoredact-<commit>-test-key.apk`) for phone checks; they have a
  different certificate, so Android refuses to install a release over one. Uninstall the test build first. Uninstalling
  deletes the app's data (the database and the stored credentials), so finish or stop any run first; the data package
  can be imported again.
- **A `VERSION` bump is a release.** `VERSION` at the repo root holds the version (`X.Y.Z`); the app's `versionName` is
  that text and its `versionCode` is `major * 1_000_000 + minor * 1_000 + patch`. A merge to `main` publishes the
  version in `VERSION` when that version has no release yet, and the release jobs wait for the owner's approval twice.
  Once a version is released, later merges publish nothing until `VERSION` is bumped, and a published version is never
  replaced.

How the release jobs work, and what the gate checks, is in `gate/README.md`.
