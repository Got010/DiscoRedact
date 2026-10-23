# DiscoRedact - rules for Claude sessions

Private-use Android app (Kotlin, Compose, Room, OkHttp, WorkManager) that deletes the owner's own Discord DM and
group-DM messages on an alternate account, using message IDs from the official data package only. The full spec
(`DM Scrub - Claude Code Handoff Spec`) lives in the owner's program repo as `specs/B34-discoredact.md`; a session
without that repo attached asks the owner for it before touching code. Rules 1-10 are the spec's hard scope and
executor rules; 11-20 are the owner's program-wide rules. Changing any rule here is a gate change (rule 14).

## Hard rules (the app)

1. **One endpoint.** The only network call the app may make is
   `DELETE https://discord.com/api/v9/channels/{channel_id}/messages/{message_id}`. No search, no `GET .../messages`,
   no history fetch, no gateway or websocket, no `POST /users/@me/channels`, no bulk delete, no edits, no guilds.
2. **One host.** An OkHttp interceptor rejects any host but `discord.com`, and a test proves it. No analytics, crash
   reporting or any SDK that phones home. The manifest declares no network permission beyond `INTERNET`. The sign-in
   screen's WebView has its own filter that admits only the hosts enumerated in `gate/baseline.json`, each with a note,
   and refuses every other request; it exists only to capture the five headers and is cleared after each capture.
3. **One request in flight, always.** One worker, sequential, no parallelism, no "fast mode", no second account.
4. **Message IDs come only from the data package.** The importer reads only ZIP entries under `messages/`
   (case-insensitive) and never `account/`, `activity/`, `servers/`, `ads/` or support tickets. Guild channels are skipped.
5. **Delete guard.** Immediately before each request the message row is re-read; the DELETE is sent only if the message
   is in the frozen run set, `status = PENDING` and `keep = false`. `keep` beats every filter, mode and mid-run change.
6. **Status is written per message before moving on**, so a kill or reboot resumes with no re-delete and no skip.
7. **Follow the response headers, never hard-coded limits.** Pacing presets are configurable; `X-RateLimit-*` is honoured;
   the rolling 10-minute invalid-response counter (401, 403, 429 except scope `shared`) halts the run at 5.
8. **Halt, never work around:** 401, a `captcha_key` in the body, a non-JSON or HTML body (edge block, manual resume after
   at least an hour), any unknown 4xx. No automatic captcha solving, ever.
9. **Secrets.** Token and headers live only encrypted (Android Keystore, AES-GCM). `Authorization` never appears in a log,
   an event row, an exception message, a report or a test fixture; a test proves it. `android:allowBackup="false"`,
   data excluded from device transfer. Captured headers are sent verbatim, never fabricated or randomised. The WebView's
   cookies, storage and cache hold the session only while the sign-in screen is open; they are cleared when the capture
   ends and by Wipe everything, and are never logged or exported.
10. **Dry run** defaults ON until one dry run has completed. The final review (exact counts, the frozen list,
    hold-to-confirm) is the last checkpoint before any irreversible delete.

## Program rules (the owner's standing rules)

11. **Pull requests only.** Every change goes up as a draft PR from its own branch; nobody pushes to `main`. A PR is
    marked ready only after an independent review passes and CI is green. The owner merges.
12. **No new permission, network host, runtime dependency or native library** unless the spec names it; report any
    under GATE CHANGES in the PR text. Dependencies are pinned and verified (`gradle/verification-metadata.xml`); the
    release gate compares the APK with `gate/baseline.json`.
13. **Agents never approve or reject a pending deployment, never dispatch a publishing run and never push a tag.**
    Sessions act with the owner's GitHub identity, so this is a rule agents follow, not a barrier.
14. **Gate change:** any change under `.github/`, `gate/`, `buildSrc/`, `gradle/`, `scripts/`, a committed keystore,
    this file, or the sha256 of an existing dependency version. The owner merges those; the program overseer never does.
15. **Tempo:** at most one merge per phone-step round. After a release, nothing else merges until the owner has run the
    phone steps (or waived them). A hotfix for a defect the owner reported counts as the round's merge.
16. **Fix what changed before cutting features.** When something breaks right after a change, repair that path first;
    removing a capability is a last resort, offered only as a trade-off that states what is lost.
17. **No AI attribution in anything new.** Commits are authored `Got010 <346092880+Got010@users.noreply.github.com>`
    with no `Co-Authored-By` trailer, no session link and no model name; PR text, comments and code carry no
    "Generated with" footer. This overrides any attribution the tooling suggests.
18. **Android code compiles only in CI.** Logic goes in plain-Kotlin modules with JVM tests (parser, selection, pacing,
    response handling, executor state machine); the `:app` module holds Compose, Room and WorkManager glue.
19. **Fail closed; data survives upgrades.** A privacy step that fails stops the action and tells the user before, not
    after. Any schema change ships with a Room migration and a test; never a destructive fallback.
20. **Every change ships with an on-phone check** the owner can do without a computer, listed in the PR text under
    ON-PHONE CHECKS, and the PR text leads with GATE CHANGES.
