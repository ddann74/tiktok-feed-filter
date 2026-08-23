# Progress log — TikTok Feed Filter whole-app hardening

## Investigation + planning (2026-08-23)

Read every source file in the repo (all 11 Kotlin files under
`app/src/main/java/com/tiktokfilter/app/`, both test files, the
manifest, and the full README) before writing anything, rather than
just restating what the README already says.

The README's own "Known open items" section is already close to a
premortem — specific, evidence-backed findings from real diagnostic
logs (e.g. `isLiveStream` false-positiving on 906/912 real screen
reads). Cited, not repeated, in `docs/PRD.md` §4a-P4.

Found two things the README doesn't mention, via a systematic
cross-check (every `SettingsRepository` public property/function
against every reference in `MainActivity.kt` + `activity_main.xml`,
same audit discipline as an earlier session's `DriveMonitorEngine`
wrapper-method check on a different repo):

- **P1**: `liveIndicatorKeywords` (the "Live Streams" keyword list) has
  zero UI wiring anywhere — confirmed the only list-type setting with no
  UI references at all, out of 11 configurable lists. Directly
  contradicts the README's claim that it's editable, and is the only
  available lever to fix the README's own worst documented bug
  (`isLiveStream`'s 99% false-positive rate) without a code change.
- **P2**: `TikTokActionCoordinator.locateAndExtractAudio`'s call to
  `DownloadedVideoLocator.findRecentlyAddedVideo` has no exception guard
  anywhere in its call chain, unlike the extraction step right after it.
  A storage permission revoked mid-session (a real, non-exotic Android
  scenario) would crash the whole accessibility service process, not
  just fail one download.

Also flagged P3 (the `extractHandle(...) ?: texts.firstOrNull()` video-
identity dedup fallback) as a real, mechanism-level risk — not confirmed
against an actual incident, so recorded as an open question (PRD §5.1)
rather than unilaterally fixed.

Wrote `docs/PRD.md` (full whole-app hardening PRD, premortem folded in
as §4a) and `docs/RALPH_PROMPT.md` (implementation loop). Implementation
not yet started — awaiting sign-off/redirection per the PRD, matching
this session's established planning-before-coding pattern.
