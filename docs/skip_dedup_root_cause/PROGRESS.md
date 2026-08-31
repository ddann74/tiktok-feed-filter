# Progress log — stabilize the skip/auto-like dedup fallback

## Investigation (2026-08-31)

Driver asked for the actual root cause of "auto scrolling taking over my
viewing experience" as top priority, not another safety-net layer.
Re-traced `TikTokFilterService`'s duplicate-skip guard line by line rather
than assuming it worked. Found: `FilterEngine.extractHandle(texts) ?:
texts.firstOrNull()` (used for both the skip-dedup and Subject-Boost-
auto-like-dedup identity) falls back to an arbitrary single string
whenever `extractHandle` fails - not guaranteed stable across two reads of
the same still-on-screen video. Combined with `performSkipGesture` being a
fire-and-forget `dispatchGesture` call (never confirmed to have actually
advanced TikTok), this can cause the SAME stuck video to be re-skipped
repeatedly, each read producing a different unstable fallback value that
fails to match `lastSkippedVideoIdentity`. This directly matches "taking
over my viewing experience" - rapid, repeated swiping on effectively one
video - better than the earlier "many different ads matched an over-broad
keyword" hypothesis alone.

Cross-referenced `docs/PRD.md` §4a-P3/§5.1 - an existing, unimplemented
open question about this exact fallback, but only from the opposite
direction (a fallback that's wrongly stable across two DIFFERENT videos).
That PRD's own recommended fix ("drop the fallback entirely") would not
have fixed this new finding - it would have made it worse, since "never
dedup when unknown" guarantees a fresh (failed) dedup attempt every time
instead of only sometimes.

Wrote `docs/skip_dedup_root_cause/PRD.md` and `RALPH_PROMPT.md`.

## Implementation (2026-08-31)

Added `FilterEngine.videoIdentity(screenTexts)`: prefers `extractHandle`
exactly as before; when that fails, uses a signature of the FULL
current-video-scoped text (`currentVideoTexts`, already used by
`evaluate`/`videoFingerprint`) rather than one arbitrary field, with
like/comment-count text filtered out (reusing `likeCountRegex`/
`commentCountRegex`, already used by `videoFingerprint` for the same
reason) so a live-updating counter doesn't itself destabilize it. This
is both more stable across re-reads of an unchanged screen (fixes the new
finding) AND less likely to coincidentally collide between two different
videos than the old single-field fallback (addresses `docs/PRD.md`
§4a-P3's original concern too) - resolves that PRD's open question in a
direction better than either of its own two proposed options.

Wired into both call sites in `TikTokFilterService.kt` (skip-dedup, and
Subject Boost's auto-like dedup) - identical fragility, identical fix.

Added 5 unit tests to `FilterEngineTest.kt`: unchanged behavior when
`extractHandle` succeeds; stable across two identical reads when it fails;
unaffected by a changing like/comment count; still distinguishes two
different videos; returns null only when there's truly nothing to
identify by. Traced each by hand against the implementation before
pushing (no Kotlin/JVM toolchain in this sandbox, same disclosed
limitation as every other PRD here).

## Verification

Pushed to PR #1's branch for the real CI to execute the new tests.

**Confirmed green**: both `build` check runs on commit `38e6e76`
completed with `conclusion: success`
(https://github.com/ddann74/tiktok-feed-filter/actions/runs/33396348855,
completed 2026-08-31T13:20:55Z) - `./gradlew test` (including the 5 new
`videoIdentity` tests, plus every pre-existing test in the suite) and
`./gradlew assembleDebug` both passed. PR #1's `mergeable_state` is
`clean`.

## Follow-up fix (2026-08-31, found while answering "are there any real gaps")

Re-reading my own `videoIdentity` implementation against `videoFingerprint`'s
existing, more thorough template-exclusion filtering
(`isKnownTemplateText`) found it was only reusing 2 of its ~6 checks
(like/comment counts) - not the literal `"Video"` marker or `"Follow "`-
prefixed chrome text. In the narrow case where a video has no real creator
identity AND no caption beyond generic markers, two DIFFERENT such videos
could still have collided on the same leftover "Video" string - a smaller
version of the exact collision class `videoFingerprint`'s own doc already
calls a "CONFIRMED REAL BUG."

Fixed by making `isKnownTemplateText`'s `handle` parameter nullable (the
other checks don't need one) and having `videoIdentity` reuse it directly
instead of its own narrower two-regex filter. In the fully-degenerate case
(no handle, no caption, nothing but chrome), `videoIdentity` now correctly
returns `null` for both videos rather than a wrongly-matching non-null
string - `TikTokFilterService`'s dedup guard already treats `null` as
"don't suppress," which is the same safe tradeoff `docs/PRD.md` §5.1
already recommended (an occasional double-skip attempt in a maximally
information-free case is safer than wrongly treating two different videos
as one). Added a test (`videoIdentity does not collide on the generic
Video marker...`) confirming this directly. Pushed alongside the other
tests for CI to confirm.

Remaining PRD §6 box: user sign-off.
