# DiscoRedact

An Android app for one phone (GrapheneOS, Pixel) that deletes the owner's own messages from Discord DMs and
group DMs on an alternate account. Message IDs come only from Discord's official data package. Requests are slow,
sequential and header-consistent, one in flight at a time, far under every documented limit.

This automates a user token against Discord's API, which Discord's terms forbid however it is paced. The design
reduces the signals; it does not make the activity allowed. Alternate account only.

The build spec and program rules live in the owner's private program repo. `CLAUDE.md` carries the hard rules
every change must keep.

Status: bootstrap. No code yet; milestone 1 (import, local database, message browser, selection, final review; no
network code) is the first pull request.
