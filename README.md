# DiscoRedact

An Android app for one phone (GrapheneOS, Pixel) that deletes the owner's own messages from Discord DMs and
group DMs on an alternate account. Message IDs come only from Discord's official data package. Requests are slow,
sequential and header-consistent, one in flight at a time, far under every documented limit.

This automates a user token against Discord's API, which Discord's terms forbid however it is paced. The design
reduces the signals; it does not make the activity allowed. Alternate account only.

The build spec and program rules live in the owner's private program repo. `CLAUDE.md` carries the hard rules
every change must keep.

## Status

Milestone 1: import, local database, channel list, message browser, keep/select, filters, per-DM modes and the
final review that freezes a run's delete set. **No network code**: confirming the final review records the run and
deletes nothing. The executor (milestone 2), credentials (3) and reports and signed releases (4) come next.

## Layout

- `core/` - plain Kotlin (JVM): the data-package parser, the selection rules (keep > manual toggle > filter, the
  frozen set and the delete guard), the pacing presets. JVM tests: `./gradlew :core:test`.
- `app/` - Android (Compose, Room): screens, database, glue. Room schemas are committed under `app/schemas/`.
- `gate/` - the release gate (see `gate/README.md`).

Builds run in CI (`.github/workflows/build.yml`). Dependencies are pinned by SHA-256 in
`gradle/verification-metadata.xml` and enforced with `--dependency-verification=strict`; local builds on macOS or
Windows use `--dependency-verification=lenient`, CI never does.
