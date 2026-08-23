# PRD: TikTok Feed Filter — whole-app hardening

Status: DRAFT — planning phase, no implementation started from this PRD yet.
Scope: the whole app, not one feature. Written after reading every source
file in the repo (all 11 Kotlin files, both test files, the manifest, and
the README in full) — findings below are grounded in that code, not
guesses, and every "confirmed" claim traces to a specific file/line.

## 0. What this is / isn't

This is a hardening and verification PRD for an app that is already
unusually honest about its own limitations — the existing README's "Known
open items" section is close to a premortem already, written by whoever
built this from real diagnostic-log evidence (specific match counts like
"906 of 912 screen reads," specific timing like "one gap ran ~4s against a
nominal 1.5s delay"). This PRD does not repeat that work; it cites it,
adds what a fresh, systematic pass over the code found that the README
doesn't mention, and turns both into a prioritized, checkable list.

This is **not** a rewrite. Nothing here proposes replacing the
accessibility-tree-reading approach, the keyword-list design, or the
architecture — all of that is sound for what's achievable without an
official TikTok API. The gaps below are specific, fixable (or explicitly
not-fixable-without-a-device) items, not an indictment of the design.

## 1. Why (current real state, verified 2026-08-23)

Read every file in `app/src/main/java/com/tiktokfilter/app/`:
`TikTokFilterService.kt`, `filter/FilterEngine.kt`,
`tiktokactions/TikTokActionCoordinator.kt`,
`tiktokactions/ActionSequence.kt`, `SettingsRepository.kt`,
`StatsRepository.kt`, `media/AudioExtractor.kt`,
`media/DownloadedVideoLocator.kt`, `overlay/OverlayController.kt`,
`diagnostics/DiagnosticLog.kt`, `MainActivity.kt`, both test files, the
manifest, and the full README.

**What's real and working, per the existing tests and code (not just
claimed):** `FilterEngine`'s ad-keyword and blocked-creator matching, with
correct current-video scoping against preloaded next-videos — 20 unit
tests cover this, including the exact real preloaded-video scenario a
diagnostic log caught. `ActionSequence`'s stage-advancement and timeout
logic — 6 unit tests. The overlay-flashing bug (stray same-package
accessibility events) — fixed, documented, with the actual root cause
named. `AudioExtractor`'s empty-extraction-is-not-success fix. The
diagnostic log's entry-boundary-aware trimming (fixed from a
line-count-based trim that could corrupt multi-line entries).

**What's real but explicitly unconfirmed, per the README's own "Known open
items":** Subject Boost's auto-like tap, the Block/Download menu-label
keyword defaults, the Live Room Menu Entry Labels, whether "Ad starts in"
ever appears on a genuinely-current (not preloaded) video.

**What's real, broken, and NOT in the README** — found during this pass,
detailed in §4a:
- The "Live Streams" keyword list (`liveIndicatorKeywords`) has **zero UI
  wiring** — not in `MainActivity.kt`, not in `activity_main.xml`. It
  cannot be edited without a code change and rebuild, directly
  contradicting the README's own claim ("a separate, editable keyword
  list (Live Streams section in Setup)") and the app's stated design
  philosophy that every keyword list is tunable without a rebuild. This
  is the single most consequential gap in the whole app: it's also the
  only lever available to fix the README's own documented worst bug (see
  §4a-P1).
- `DownloadedVideoLocator.findRecentlyAddedVideo`'s `ContentResolver`
  query has no exception guard anywhere in its call chain, unlike the
  extraction step right after it (which does). A plausible, non-exotic
  trigger (storage permission revoked mid-session via Settings) would
  crash the whole accessibility service process, not just fail one
  download (§4a-P2).

## 2. Definition of "done" for this hardening pass

Not "every heuristic verified against a live TikTok install" — that's
explicitly outside what's achievable from this environment (no Android
SDK, no emulator, no TikTok account; see §6). Instead:

- [ ] Every user-configurable setting in `SettingsRepository` has a
      reachable UI control (closes the `liveIndicatorKeywords` gap, and
      confirms no sibling gap exists — see §4a-P1).
- [ ] Every unguarded call chain that can throw an exception on the main
      thread and take down the whole accessibility service is identified
      and fixed, or explicitly accepted with a documented reason (§4a-P2).
- [ ] The video-identity fallback used for skip/auto-like dedup is
      reviewed for the degenerate case where no real identity can be
      extracted at all (§4a-P3).
- [ ] Every README "Known open item" is either fixed, or has a concrete,
      testable next step recorded (most already do — a few don't).
- [ ] New Kotlin unit tests cover every new fix the same way the existing
      ones cover `FilterEngine`/`ActionSequence` — pure logic, no Android
      dependency required, runnable without a device.

## 3. Design

### 3.1 Live Streams keyword list UI (P1)

Add the same pattern already used for the other 9 editable lists
(`moreOptionsKeywords`, `blockOptionKeywords`, etc.) in `activity_main.xml`
and `MainActivity.kt`: a labeled section, an input + add button, a
rendered list with per-item remove, wired to
`settingsRepository::liveIndicatorKeywords` via the existing
`addKeyword`/`removeKeyword`/`renderList` helpers — no new plumbing
needed, `SettingsRepository` already has a working getter/setter, it's
purely a UI-layer gap.

This directly unblocks the README's own top open item: `isLiveStream`
false-positiving on 99% of screens because of TikTok's "Live now" preview
rail. With the keyword list actually editable, a user hitting this can
try narrowing/replacing the bare `"LIVE"` match (e.g. requiring it appear
alongside a distinctive Live-room-only string) without waiting for a code
change — turning an currently-unactionable bug into a tunable one, which
is the whole point of this app's keyword-list design.

### 3.2 Exception guard around the locate step

Wrap `DownloadedVideoLocator.findRecentlyAddedVideo`'s call site in
`TikTokActionCoordinator.locateAndExtractAudio` in a try/catch, symmetric
with the guard already around the extraction step a few lines later.
On a caught exception: log it via `diagnosticLog.logError`, record a
clear failure via `statsRepository.recordEvent`, and — critically —
still reset `isAudioExtractionInFlight = false`, the same cleanup every
other exit path already performs. Without this, the fix isn't just
"don't crash" but "don't leave the cross-request lock stuck."

### 3.3 Video-identity dedup fallback review

`TikTokFilterService`'s duplicate-skip and duplicate-auto-like guards both
use `FilterEngine.extractHandle(texts) ?: texts.firstOrNull()` as a video
identity. When `extractHandle` returns null (no `@handle` node, no
`"<name> profile"` content description found at all), falling back to
"whatever text happened to be first in the flat list" is not a real video
identity — if that first string is ever something generic and
repeated across different videos (a persistent chrome element, not
video-specific content), two different videos could be wrongly treated as
"the same one," silently suppressing a real skip or a real auto-like.
Needs a decision (§5, open questions) on whether `null` (no fallback —
never dedup when identity is unknown, accepting a rare double-action) is
actually safer than a fallback that can be wrong.

## 4. Testing / verification approach

Same situation as this session's other Android work: no Android SDK, no
emulator, no TikTok install, no real device available in this
environment. `FilterEngine` and `ActionSequence` are pure Kotlin with
existing unit tests runnable via `./gradlew test` — every fix that
touches pure logic gets a new test the same way; anything requiring the
real accessibility tree, a live TikTok screen, or actual file I/O against
MediaStore is disclosed as unverified-from-here, matching how the README
already discloses this for the Block/Download automations.

## 4a. Premortem (2026-08-23): assume this app fails again after this pass

- **P1 — `liveIndicatorKeywords` has no UI**, confirmed via a systematic
  cross-check (every `SettingsRepository` public property/function against
  every reference in `MainActivity.kt` + `activity_main.xml` — the only
  list with zero UI references that isn't an internal-only helper like
  `blockActionStages`/`parseList`). Consequence: the app's own documented
  worst bug (`isLiveStream` false-positiving 906/912 times in a real
  session) has no available fix path for an actual user — not "hard to
  fix," literally unreachable without editing source and rebuilding,
  which contradicts this app's entire "tune it without a rebuild"
  premise for every other keyword list.
- **P2 — An uncaught exception mid-download can crash the whole
  accessibility service**, not just fail that download.
  `TikTokActionCoordinator.locateAndExtractAudio`'s first line —
  `DownloadedVideoLocator.findRecentlyAddedVideo(context, afterEpochSeconds)`
  — runs on the main thread (via `mainHandler.postDelayed`) with **no
  try/catch anywhere in the call chain**, unlike the extraction step
  immediately after it, which is wrapped. A `SecurityException` from a
  storage permission revoked mid-session (Settings → Permissions, always
  possible on Android, not exotic) — or any other `ContentResolver`
  hiccup — would propagate uncaught out of a `Handler` callback, which
  crashes the process. Since `TikTokFilterService` (auto-skip, overlay,
  Block, Download — everything) runs in that same process, a user would
  see ALL filtering stop mid-scroll, not just "the download failed."
  Android will very likely auto-restart the accessibility service
  afterward, but that's a disruptive, silent, currently-undocumented
  failure mode for a genuinely plausible trigger.
- **P3 — The video-identity dedup fallback can be wrong in the degenerate
  case.** `extractHandle(texts) ?: texts.firstOrNull()` — when no real
  identity can be found at all, "the first string in the list" is used
  as if it were one. If that string is ever something video-independent
  (a persistent UI element's text, not per-video content), two different
  videos could register as "the same video," silently suppressing a real
  skip or auto-like. Not confirmed to have happened (no diagnostic log
  evidence either way, unlike P1/P2) — flagged as a real mechanism, not
  a confirmed incident, and needs a design decision (§5) rather than a
  unilateral fix.
- **P4 — Every README "Known open item" still stands.** Not re-litigated
  here — see the README's own section, especially the `isLiveStream`
  99%-false-positive finding (which P1 above is the direct fix path for)
  and the Block/Download menu-label keywords being the least-verified
  part of the app. This PRD's checklist (§5) tracks them as line items so
  "planning phase" produces one place to check status, not two documents
  saying different things.

## 5. Open questions

1. **P3's dedup fallback**: keep a fallback (risk: wrong-video
   suppression) or drop it (risk: an occasional duplicate skip/like when
   TikTok briefly re-renders the same video across two events without a
   readable identity either time)? Recommend dropping the fallback —
   README's own design philosophy elsewhere favors "fail toward doing
   nothing" (e.g. the Download in-flight lock rejects outright rather
   than risk a wrong-video extraction) — but this is a real tradeoff, not
   an obvious call, so flagged rather than decided unilaterally.
2. **P1's default keyword change**: once the UI exists, should the
   default `liveIndicatorKeywords` list also change (e.g. requiring a
   second, more Live-room-specific string alongside bare "LIVE"), or
   should the UI ship first and the default stay as-is until a real
   device confirms what actually distinguishes a genuine Live room screen
   from the preview rail? Recommend the latter — changing the default
   without a confirmed real-device signal would just swap one unverified
   guess for another; the value of P1 is making it *tunable*, not
   guessing a better default blind.

## 6. Success criteria (implementation-phase checklist)

- [ ] P1: `liveIndicatorKeywords` UI added (`activity_main.xml` +
      `MainActivity.kt`), mirroring the existing 9-list pattern
- [ ] P2: try/catch added around `locateAndExtractAudio`'s locate step,
      including resetting `isAudioExtractionInFlight` on the caught path
- [ ] P3: open question resolved (dedup fallback kept or dropped) and
      implemented
- [ ] New unit tests added for whichever of P1-P3 have pure-logic-testable
      behavior (P2, P3 — P1 is UI-only, not unit-testable without
      instrumentation)
- [ ] `./gradlew test` run and passing (this environment can attempt this
      — see PROGRESS.md for whether it actually succeeded)
- [ ] User sign-off
