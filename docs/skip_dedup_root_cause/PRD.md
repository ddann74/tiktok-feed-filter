# PRD: Fix the actual mechanism that can make one video get re-skipped repeatedly

Status: DRAFT - awaiting sign-off before implementation begins.
Scope: this one mechanism only. Not a general codebase pass.

## 0. What this is / isn't

The driver asked for the ROOT CAUSE of "auto scrolling taking over my
viewing experience," as top priority - not another layer around it. Every
prior fix this session (`docs/user_reported_fixes/PRD.md`'s circuit
breaker, `docs/auto_scroll_hard_stop/PRD.md`'s escalation) assumed the
runaway was many DIFFERENT real videos matching an over-broad keyword, and
deliberately declined to guess further without a diagnostic log. Re-reading
`TikTokFilterService`'s own duplicate-skip suppression line by line (not
assuming it works, actually tracing it) found a second, distinct, and
previously undocumented mechanism that fits the reported symptom just as
well - possibly better, since it explains rapid repeated swiping on effectively
ONE stuck screen, which matches "taking over the viewing experience" more
directly than "many different real videos happened to match."

This is a real code-level finding, not a guess dressed up as one - traced
through the exact logic below, cross-referenced against
`docs/PRD.md` §3.3/§4a-P3 (an existing, never-implemented open question
about this exact fallback), and fixes something that open question's
original two proposed options (keep the fallback, or drop it) would NOT
have actually fixed.

## 1. Why (investigation, 2026-08-31)

### 1.1 The duplicate-skip guard's fallback is unstable, not just imprecise

`TikTokFilterService` (L216, and the identical pattern at L~255 for
Subject Boost's auto-like dedup):
```kotlin
val videoIdentity = FilterEngine.extractHandle(texts) ?: texts.firstOrNull()
if (videoIdentity != null && videoIdentity == lastSkippedVideoIdentity) {
    // duplicate suppressed
}
```
`extractHandle` (per its own doc and the README's "Identifying a creator"
section) fails to find a real creator identity whenever TikTok doesn't
render a `"<name> profile"` content description for the current video -
not a rare edge case, just whatever it doesn't happen to catch. When it
fails, the fallback is `texts.firstOrNull()` - literally whatever string
happened to be first in a depth-first traversal of the ENTIRE current
screen. This string is not guaranteed to be stable across two reads of
the exact same still-on-screen video: a re-layout, a lazily-populated
chrome element, or any other page a real Android UI would only note as
"redrew" can change which node is first without the actual video changing
at all.

### 1.2 The concrete failure this produces

1. A video that should be skipped (ad/blocked creator/repeat-view) is
   detected. `performSkipGesture()` dispatches a swipe. This is a
   **best-effort gesture** (`dispatchGesture`, fire-and-forget, no
   confirmation TikTok actually advanced) - if it lands slightly wrong, or
   TikTok is busy/slow, the SAME video can still be on screen on the next
   accessibility event.
2. `COOLDOWN_MILLIS` (900ms) blocks re-evaluation briefly, but once it
   elapses, if the video is STILL there, `TikTokFilterService` evaluates
   again. `decision` comes back non-null again (same video, same reason).
3. This is exactly the case the duplicate-skip guard (§1.1) exists to
   catch - "still transitioning, don't skip again." But if `extractHandle`
   failed on both reads AND the `texts.firstOrNull()` fallback happened to
   differ between the two reads (§1.1), `videoIdentity != lastSkippedVideoIdentity`
   is true even though it's the SAME video - the guard fails silently, and
   `performSkipGesture()` fires AGAIN.
4. This can repeat: another swipe, still possibly not advancing TikTok,
   another slightly-different fallback string, another failed dedup match,
   another swipe. From the driver's side, this looks exactly like rapid,
   repeated, uncontrollable swiping on what might genuinely be ONE video -
   "taking over my viewing experience," not "many different ads in a row."

### 1.3 This is not a new problem invented for this PRD

`docs/PRD.md` (the earlier whole-app hardening PRD, still largely
unimplemented) already flagged this exact fallback as risky in §4a-P3 -
but only from the OPPOSITE direction: "if that string is ever something
video-independent... two different videos could register as 'the same
video,' silently SUPPRESSING a real skip." That PRD's own recommended fix
(§5.1: "drop the fallback... never dedup when identity is unknown") would
not have fixed §1.2 above - dropping the fallback removes the STABLE-collision
risk but makes the UNSTABLE-fallback risk (§1.1/1.2) worse, not better,
since "never dedup when unknown" means every failed-`extractHandle` read
gets treated as a brand-new video, guaranteeing a fresh skip attempt every
time instead of occasionally.

## 2. Definition of "done" for this task

- [ ] The fallback identity used for skip/auto-like dedup is **stable
      across repeated reads of the same still-rendered screen**, closing
      §1.2's mechanism.
- [ ] The fallback identity is still **no more likely to collide between
      two genuinely different videos** than before - ideally less likely,
      not just differently risky - so `docs/PRD.md` §4a-P3's original
      concern is also addressed, not traded for a new one.
- [ ] No change to `extractHandle` itself, ad-keyword matching, or any
      other heuristic's defaults - this is scoped to the fallback used
      only when `extractHandle` already failed.

Non-goals:
- Confirming or fixing the OTHER already-known-but-unconfirmed hypothesis
  (an over-broad Ad Keyword) - still blocked on a real diagnostic log, and
  this PRD's fix is independently justified regardless of whether that
  hypothesis also turns out to be real.
- Making `performSkipGesture` confirm TikTok actually advanced (would need
  a totally different verification mechanism, e.g. comparing screen
  content before/after with a delay) - out of scope, a bigger change.

## 3. Design

### 3.1 `FilterEngine.videoIdentity` - a stable, still-precise fallback

New public method in `FilterEngine`, replacing the
`extractHandle(...) ?: texts.firstOrNull()` pattern at both call sites:

```kotlin
fun videoIdentity(screenTexts: List<String>): String? {
    extractHandle(screenTexts)?.let { return it }
    val scoped = currentVideoTexts(screenTexts)
        .filterNot { likeCountRegex.matches(it.trim()) || commentCountRegex.matches(it.trim()) }
    if (scoped.isEmpty()) return null
    return scoped.joinToString("|")
}
```

- Prefers the real creator identity (`extractHandle`) exactly as before -
  no change when it succeeds.
- When it fails, uses a signature of the ENTIRE current-video-scoped text
  (already-existing `currentVideoTexts`, the same scoping `evaluate`/
  `videoFingerprint` use), not one arbitrary field - stable across re-reads
  of the same unchanged screen (fixes §1.2), and far less likely to
  coincidentally match a genuinely different video than a single string
  (addresses `docs/PRD.md` §4a-P3's original concern too).
- Filters out like/comment-count text (reusing the existing
  `likeCountRegex`/`commentCountRegex`, already used by `videoFingerprint`
  for the same reason) so a live-updating counter doesn't itself
  destabilize the signature.

### 3.2 Wire into both call sites

`TikTokFilterService`:
```kotlin
val videoIdentity = FilterEngine.videoIdentity(texts)
```
replaces both the skip-dedup line (L216) and the Subject Boost auto-like
dedup line (`lastAutoLikedVideoIdentity`'s equivalent) - identical
fragility, identical fix.

## 4. Testing / verification approach

`FilterEngine` is pure Kotlin, already has 20+ existing unit tests
(`FilterEngineTest.kt`). New tests added the same way, and - unlike the
Python-only verification earlier this session - this repo now has real CI
(`.github/workflows/android-build.yml`) that runs `./gradlew test` on every
push, so these will be genuinely executed and confirmed green before
calling this done, not just written.

## 5. Open questions

None blocking - this directly resolves `docs/PRD.md` §4a-P3/§5.1's
previously-open question, in a direction that fixes both the case that
PRD's own investigation found AND the new one found here.

## 6. Success criteria (implementation-phase checklist)

- [x] `FilterEngine.videoIdentity` added
- [x] Both call sites in `TikTokFilterService` (skip dedup, Subject Boost
      auto-like dedup) switched to it
- [x] Unit tests added: stable across repeated identical reads when
      `extractHandle` fails; still distinguishes two different videos;
      unaffected by a changing like/comment count; unchanged behavior when
      `extractHandle` succeeds; returns null only when truly nothing to
      identify by
- [ ] Tests pushed and confirmed GREEN via the real CI run (not just
      written) - closes `docs/PRD.md` §4a-P3 as well as this PRD
- [ ] User sign-off
