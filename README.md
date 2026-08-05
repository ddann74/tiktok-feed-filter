# TikTok Feed Filter

An Android accessibility-service app that watches your For You feed and swipes past ads
and creators you've blocked - automatically, without you having to react to them
yourself. It also puts two floating buttons over TikTok itself - **Block** and
**Download** - and can reach into TikTok's own menus to really block a creator (not
just skip their videos in this app) and download a video via TikTok's own Save option,
extracting its audio afterward. There's no official API for any of this, so it all
works the same way a human would: reading what's on screen and tapping the same things
you would.

## How it works

- **Accessibility Service** (`TikTokFilterService`) gets notified whenever TikTok's
  screen content changes, reads every piece of text currently on screen (via the
  accessibility node tree - no screenshots, no screen recording), and hands that off to
  a pure decision function.
- **`FilterEngine`** decides, from that text alone, whether the current video is an ad
  (keyword match) or from a blocked creator (display name match - see below) and the
  service dispatches a swipe-up gesture if so, the same gesture a finger would perform.
- **Floating Block/Download buttons** are drawn over TikTok whenever it's in the
  foreground (an accessibility overlay window - see *Floating buttons*, below).
- **Real TikTok integration** (`TikTokActionCoordinator`) drives two multi-tap
  automations - really blocking a creator in TikTok, and downloading + extracting a
  video's audio - by finding and tapping TikTok's own menu items (see its own section
  below; this is the most fragile part of the app and worth reading before relying on it).
- Every outcome - a skip, a block, a download, an extraction, or any of them failing -
  is written to the **Activity** log, so nothing here is a silent black box.
- A separate, opt-in **Diagnostic Log** (`DiagnosticLog`) captures the technical detail
  Activity deliberately leaves out - raw on-screen text, per-stage automation attempts -
  for actually troubleshooting or tuning a keyword list (see its own section below).
- **Live streams** are recognized separately from normal videos (`FilterEngine.isLiveStream`),
  since TikTok renders a Live room's screen differently - see its own section below.
- An optional **Subject Filter** inverts the ad-keyword idea: instead of skipping videos
  that match something, it skips every video that *doesn't* match one of your configured
  subjects - see its own section below.

## This is inherently heuristic - read this before relying on it

There is no official way for a third-party app to control TikTok's algorithm or know
"this is an ad" with certainty, and no official API for blocking a creator or saving a
video on your behalf. This app reads on-screen text and taps on-screen buttons by
pattern-matching against their labels, which means:

- **It will miss things whose wording doesn't match your configured keywords.** Every
  keyword list in this app (Ad Keywords, and the four under Real TikTok Integration) is
  editable without a rebuild for exactly this reason - if something stops matching after
  a TikTok update, add the new wording there.
- **It could theoretically false-positive** on a normal video whose caption happens to
  contain a configured ad keyword, or tap the wrong menu item if TikTok reuses a label
  in an unexpected place. Keep keyword lists specific.
- **TikTok preloads several videos ahead of the one you're watching, and all of their
  accessibility nodes are present at once.** A real diagnostic log caught this directly:
  a preloaded, not-yet-visible video's ad marker showed up in the same screen read as
  the video actually on screen, several videos earlier. Ad-keyword and blocked-creator
  matching are both scoped to just the current video's own text (`FilterEngine.evaluate`
  truncates at the next preloaded video's boundary) specifically because of this - so a
  skip is never triggered by something that hasn't scrolled into view yet.
- **The three-step Block automation and two-step Download automation can each stall
  partway through** if a step's keyword doesn't match what's actually on screen (a menu
  that didn't open, wording that changed, a step that doesn't exist for a given video).
  Each attempt has a 4-second timeout per step; when it times out, the **Activity** log
  says so explicitly rather than pretending it worked.
- If something stops working, check **Activity** first - it's the fastest way to tell
  "TikTok changed something" from "this app has a bug." If Activity isn't enough to
  tell what's going wrong, turn on the **Diagnostic Log** (below) and try again - it
  shows the exact on-screen text each decision was based on.

## Identifying a creator

Every blocked-creator match - auto-skip, and the floating **Block** button - depends on
correctly figuring out who posted the video currently on screen, and this is worth being
explicit about since it changed based on what a real device's diagnostic log showed:

**TikTok does not expose the creator's `@username` to accessibility services on current
builds.** The original design assumed a text node reading exactly `@handle` would be
there; testing against a live device's diagnostic log showed that never happens - not
once across hundreds of screen reads. What TikTok *does* expose is the creator's
**display name**, via a content description reading `"<name> profile"` next to the
video. `FilterEngine.extractHandle` still checks for a bare `@handle` node first (in
case a future TikTok build brings it back), but in practice today it always falls
through to the display name.

**This means Blocked Creators entries need to be the creator's display name as shown on
their profile, not their `@username`.** These are usually similar or identical, but
not guaranteed to be - and display names, unlike `@usernames`, aren't guaranteed unique
across different accounts, so this is a real (if uncommon) false-positive risk worth
knowing about, not just a theoretical one.

When more than one video's nodes are present in the accessibility tree at once (TikTok
preloads the next video while you're still watching the current one), the *first*
`"<name> profile"` match is used - confirmed against real logs to consistently be the
creator of whichever video is actually visible, with preloaded videos' nodes always
appearing later in the tree.

## Floating buttons

Two small buttons - **Block** (red) and **Download** (blue) - appear near the edge of
the screen whenever TikTok is in front, and disappear when you switch to another app.
They're drawn using an accessibility overlay window, which is why this app doesn't need
the separate "display over other apps" permission most floating-bubble apps do - only
an accessibility service can request this particular window type, and it's already
covered by the Accessibility permission you grant in Setup.

**If the buttons flash or blink instead of staying put**, that was a real bug (fixed):
`TikTokFilterService` used to hide the overlay the instant it saw a single accessibility
event from any package other than TikTok - which included events the overlay's *own*
window can generate just by redrawing, plus anything from system UI, a keyboard, etc.
momentarily interleaving with TikTok's events. It now ignores events from this app's own
package outright, and only hides on a short delay after TikTok's events actually stop -
cancelled immediately if a TikTok event arrives first - so a stray one-off event no
longer visibly flashes the overlay off and back on. If you still see flashing after
updating, it means something is generating those stray events more persistently than a
short delay can smooth over - the **Diagnostic Log**'s `[OVERLAY] shown` / `[OVERLAY]
hidden` lines will show exactly how often it's actually toggling.

- **Block** identifies the creator of whatever video is currently on screen (the same
  display-name detection `FilterEngine` uses - see *Identifying a creator*, below) and
  adds them to your local Blocked Creators list immediately - then, if **Really block in
  TikTok** is on, also attempts the real TikTok block automation described below.
- **Download** doesn't download anything by itself - tapping it reveals two smaller
  buttons, **Video** and **Audio**, so you choose which one you actually want before
  anything happens (see below).

Turn the buttons off under **Show floating Block/Download buttons** in Setup if you'd
rather use the in-app Blocked Creators list only, without anything drawn over TikTok.

## Real TikTok integration (Block and Download automations)

These are meaningfully more powerful - and more fragile - than the read-only ad/creator
skipping above, because they tap real buttons inside TikTok's own menus rather than
just reading text.

**Really blocking a creator** taps: the video's "more options" menu → the **Block**
option → the confirmation dialog's **Block** button. This is a genuine, permanent,
account-level TikTok block - it still applies even if this app is later uninstalled -
not just an entry in this app's own list. The local Blocked Creators list is *always*
updated too, immediately, regardless of whether the real-block automation succeeds, so
you're never worse off than before if a step doesn't find its target. Turn this off
under **Really block in TikTok (not just skip)** to fall back to local-list-only
blocking, which is faster to fail-safe if the automation is ever unreliable on your
TikTok version.

**Both Video and Audio start the exact same tap sequence** - the video's "more options"
menu → **Save video** (TikTok's own official download option) - since that's the only
download TikTok itself offers; there's no separate "video only" button inside TikTok to
find. What happens after that tap sequence completes is where they differ:

- **Video** stops there. The video is now in your device's media library, same as if
  you'd tapped Save yourself - nothing more happens.
- **Audio** additionally looks for that newly-saved video in the device's media library
  (polling briefly, since the file isn't necessarily written the instant the tap
  registers), then extracts its audio track on-device - pure container remuxing via
  Android's built-in `MediaExtractor`/`MediaMuxer`, no re-encoding, no external library,
  no network call of any kind - and saves it as an `.m4a` file under this app's own
  external files directory (`Android/data/com.tiktokfilter.app/files/ExtractedAudio/`,
  visible to any file manager, cleared if the app is uninstalled). **The video itself is
  left in place too** - this app never deletes anything TikTok saved, so choosing Audio
  leaves you with both the video and a separate audio file, not just the audio.

If a creator has disabled downloads for their video, the **Save video** option simply
doesn't exist on screen for either choice, the tap sequence times out, and the
**Activity** log says the download wasn't available for that video.

**Judging whether an extracted audio file is actually complete:** a successful
extraction's Activity line includes the extracted duration and sample count, e.g.
`"Audio extracted (~42.3s, 1,984 samples) to Android/data/.../ExtractedAudio/..."` -
compare that duration against how long the source video actually was (there's no
independent way for this app to know the video's real length, so this comparison has
to be made by you). A duration far shorter than the video is the clearest sign
something went wrong partway through, even though the file itself is technically valid.
`AudioExtractor` also no longer reports success on an empty extraction - copying zero
samples now correctly counts as a failure (with its own **Activity**/**Diagnostic Log**
message distinguishing "no audio track at all" from "a track was found but nothing
could be read from it"), rather than silently producing a technically-valid but
audio-less file and calling it done.

**This app will never try to download a video whose creator has disabled downloads by
any other means (e.g. screen-recording to bypass that setting).** That's a deliberate
line: automating a button on your own phone is one thing, circumventing a creator's
explicit choice about their own content is another, and this app only does the former.

The four keyword lists under **Real TikTok Integration** in Setup control what each tap
sequence looks for - "More Options" Button Labels, "Block" Menu Option Labels, Block
Confirmation Dialog Labels, and "Download/Save" Menu Option Labels - each with sensible
defaults, each editable without a rebuild.

## Live streams

Yes - blocked creators are skipped out of their Live rooms too, not just their normal
videos, and **Block** still works while watching one. TikTok renders a Live room
differently from a normal video, though, so this is handled as its own case rather than
assuming everything above just applies unchanged:

- **Detection** (`FilterEngine.isLiveStream`) looks for TikTok's own **LIVE** badge text
  on screen - a separate, editable keyword list (**Live Streams** section in Setup)
  from the ad/creator ones above, since it's answering a different question ("is this a
  Live room at all") rather than "who posted this" or "is this an ad".
- **Auto-skipping a blocked creator's Live** uses the exact same creator-identification
  match (see *Identifying a creator*, above) and swipe-away gesture as skipping their
  normal videos - no separate logic needed there, since a Live host's display name is
  just another piece of on-screen text. This is controlled by its own toggle, **Skip
  Live streams from blocked creators** (default on), independent of the video-skip
  toggles, in case you'd rather leave that off.
- **Really blocking** a Live host taps a different entry point than a video does - a
  Live room's own options/report menu, rather than a video's "..." button - configured
  by **Live Room Menu Entry Labels** in Setup. From there, it's assumed TikTok reuses
  the same **Block** wording and confirmation dialog as a normal video; if that turns
  out not to hold on your TikTok version, tune the Block Menu Option / Confirmation
  Dialog labels the same way you would for a normal video (they're shared between both).
- **Download does not run on a Live stream** - a live broadcast isn't a saved video
  file, so there's nothing for TikTok's Save option to act on. Tapping **Download**
  while watching a Live just logs that it isn't available, rather than tapping around
  looking for an option that was never going to appear.

Like the rest of Real TikTok Integration, none of this has been confirmed against a
real TikTok install - the Live room menu structure in particular is a best-effort
guess. If **Block** doesn't work on a Live but does on normal videos, that's the first
place to check with the **Diagnostic Log**.

## Subject filter

Everything above skips videos that match something specific (an ad, a blocked
creator). **Subject Filter** is the opposite: an *allow-list*, not a block-list. Add
subjects you actually want - "history", "science", whatever - and turn on **Only show
videos about my subjects**, and every video whose caption/hashtags don't mention at
least one of them gets skipped, not just ones that match something in particular.

It uses the exact same matching mechanics as Ad Keywords - case-insensitive substring
match against the current video's own on-screen text, scoped the same way (a preloaded
video further down the feed can't accidentally satisfy the match for the one actually
on screen) - just inverted: no match means skip, instead of a match meaning skip.

**Off by default, and safe to turn on before you've added anything**: with no subjects
configured, the filter has nothing to check against and stays completely inert, rather
than skipping every single video the moment the toggle is flipped. Add at least one
subject before it actually starts filtering.

An ad or a blocked creator is still skipped for *that* reason even if the video happens
to also mention one of your subjects - those checks run first and return immediately,
same priority order as before this existed.

This inherits the same wording-mismatch risk as every other keyword list here, but the
failure mode is more disruptive: a caption using different phrasing than you expect
(`"#historytok"` instead of the word "history", for instance) means that video gets
skipped as off-subject instead of just not being specially treated. If your feed
suddenly looks nearly empty after turning this on, check **Diagnostic Log** - every
skip includes which subjects it was looking for and the exact on-screen text it
searched, which is the fastest way to see what wording you're actually missing.

**If it feels like it's scrolling nonstop and you can't tell whether it's ever landing
on a match:** that's very likely correct, mechanical behavior, not a hang - if your
chosen subjects rarely appear in your actual feed, the app is doing exactly what "only
show videos about my subjects" means: skipping every single non-matching video it
sees, one after another, for as long as the feed keeps not matching. It isn't stuck; it
just hasn't found a match yet. Broaden your keyword list (see above) rather than
assuming something's broken.

A related bug was fixed here too: the skip cooldown used to be purely time-based
(900ms), which didn't check whether TikTok had actually finished transitioning to the
next video - on a slower connection or device, that could mean the *same* video got
skipped more than once before its own transition even finished, compounding into
something that felt faster and more chaotic than one skip per video. The service now
also tracks which video it last skipped and won't act on the same one twice.

**To actually stop it right now, in order of speed:**
1. **Leave TikTok** (press Home, switch apps) - the service only acts while TikTok is
   the foreground app, so this stops everything instantly regardless of any setting.
2. **Turn off "Only show videos about my subjects"** in this app - takes effect on the
   very next screen TikTok renders, well under a second, no reinstall needed.
3. **Turn off the Accessibility Service entirely** via **Open Accessibility Settings**
   in this app - the same button used in Setup - if you want every automation in this
   app off at once, not just Subject Filter.

## Diagnostic log

The **Activity** log is deliberately curated for everyday use - short, friendly lines
like "Ad skipped." The **Diagnostic Log**, under its own section in the app, is the
opposite: everything, in detail, meant for the one moment you actually need it.

With **Enable diagnostic logging** on, every screen evaluation is recorded with the
*full list of raw text* the accessibility tree returned for that screen - so if a video
isn't getting skipped, you can see exactly what TikTok rendered and check it against
your keyword list yourself, rather than guessing. Every Block/Download automation stage
is recorded too - which keywords it searched for, whether a matching node was found,
whether it timed out - so a stalled automation shows you precisely which step it never
got past. Audio extraction failures include the actual exception and stack trace.

It's off by default because this level of detail is genuinely noisy and not something
day-to-day use needs - turn it on when you're actively troubleshooting or tuning a
keyword list, reproduce the issue, then either:

- **Share Diagnostic Log** - opens the system share sheet (email, Files, a text editor,
  anywhere) with the log file, via a scoped `FileProvider` grant - nothing is shared
  automatically or without you choosing where.
- **Clear Diagnostic Log** - wipes it once you're done, so leaving logging on
  afterward doesn't slowly accumulate an old, irrelevant log.

The file itself lives in this app's private internal storage (`diagnostics.log`,
capped at 512 KB - oldest entries are trimmed first if it grows past that) and is
never written anywhere else, read by any other component, or transmitted anywhere -
sharing it is always a manual, explicit action you take.

## Setup

1. **Open in Android Studio**, let Gradle sync.
2. **Run it**, then in the app tap **Open Accessibility Settings** and turn on
   "TikTok Feed Filter" - Android requires this to be granted manually in Settings,
   an app can never enable it for itself.
3. Grant the storage-read permission when prompted (only used to locate a video TikTok
   itself just saved, for audio extraction - see *Privacy*).
4. Add any creators you want auto-skipped under **Blocked Creators**, using their
   **display name** as shown on their profile (not their `@username` - see *Identifying
   a creator*, above); the leading `@`, if you include one, is stripped automatically.
5. Adjust **Ad Keywords** if needed - "Sponsored" and "Ad starts in" are the defaults
   and cover the two ad formats confirmed against a real device.
6. If you want your feed narrowed to specific subjects, add them under **Subject
   Filter** and turn on **Only show videos about my subjects** - leave this off (the
   default) if you just want ad/creator filtering without narrowing your feed by topic.
7. Leave **Really block in TikTok**, **Show floating Block/Download buttons**, and
   **Skip Live streams from blocked creators** on (all default on) if you want the
   fuller feature set; turn any off if you'd rather stick to passive skip-only
   filtering, or leave Live rooms alone entirely.
8. Open TikTok and scroll - matching videos should now skip on their own, and the
   floating Block/Download buttons should appear. Check **Activity** in this app
   afterward to see what it caught (or attempted and couldn't complete).
9. If anything isn't working as expected, turn on **Enable diagnostic logging**
   under **Diagnostics**, reproduce the issue, then use **Share Diagnostic Log** to
   export the detail behind it (see *Diagnostic log*, above). It's off by default -
   only turn it on when you actually need it.

**minSdk 24 (Android 7.0)** is a hard requirement, not a stylistic choice -
`AccessibilityService.dispatchGesture`, the actual mechanism used to perform a skip,
doesn't exist before API 24.

## Privacy

Two permissions, both narrowly scoped, nothing else:

- The accessibility service's own system-granted binding permission (no separate
  `<uses-permission>` - Android requires this to be granted manually in Settings).
- `READ_EXTERNAL_STORAGE` (pre-Android 13) / `READ_MEDIA_VIDEO` (Android 13+) - used
  for exactly one thing: locating the video file TikTok's own Save action just wrote,
  so its audio can be extracted. Nothing else in this app reads device storage, and
  extracted audio is written to this app's own private external directory, not
  anywhere requiring write permission.

**No internet permission is requested or used anywhere in this app.** Everything -
blocked creators, keywords, skip/block/download counts, the activity log, extracted
audio files - lives entirely on-device and is never transmitted anywhere. The
accessibility service only reads and acts on screens belonging to the configured
target package(s) (TikTok by default) and ignores every other app.

## Target app packages

TikTok ships under different package names depending on region/variant. Two are
included by default:

- `com.zhiliaoapp.musically` - global/US TikTok
- `com.ss.android.ugc.trill` - used in some regions/older builds

If you use TikTok Lite or a build under a different package name, add it under
**Target App Packages** in the app - no rebuild required.

## Architecture

```
filter/          FilterEngine - pure Kotlin, no Android dependencies, unit-tested
tiktokactions/    ActionSequence (pure, unit-tested step logic) +
                  TikTokActionCoordinator (drives the Block/Download tap sequences,
                  triggers audio extraction once a download completes)
media/            AudioExtractor (MediaExtractor/MediaMuxer remuxing) +
                  DownloadedVideoLocator (MediaStore query for TikTok's saved file)
overlay/          OverlayController - the floating Block/Download buttons
diagnostics/      DiagnosticLog - verbose, opt-in, file-backed troubleshooting log
TikTokFilterService   Accessibility service - screen reading, gesture dispatch,
                      overlay show/hide, forwards events to TikTokActionCoordinator
SettingsRepository    All filters, toggles, and the automation keyword lists
StatsRepository       Skip/block/download counters + a capped, newest-first activity log
MainActivity          Setup UI: enable service, edit filters, bulk-manage creators,
                      view activity
```

Deliberately minimal dependencies: no networking library, no database, no media/download
library - SharedPreferences is more than sufficient for lists this small, and audio
extraction uses only Android's built-in media APIs.

## Known open items

- **Subject Filter is untested against a real feed.** It reuses the same matching
  mechanics already confirmed to work correctly (current-video scoping, case-insensitive
  substring match), but whether TikTok captions/hashtags actually contain the literal
  words you'd configure (e.g. "history") as opposed to different phrasing (e.g.
  `"#historytok"`, `"#ww2"`) hasn't been verified against a live device the way ad
  detection has been. Because this is an allow-list rather than a block-list, a wording
  mismatch is much more visible here - it means everything gets skipped, not just one
  missed video - so add several plausible variants per subject rather than a single
  word, and check **Diagnostic Log** if your feed goes quiet after turning it on.
- **"Ad starts in" may never actually match anything, and that might be correct.**
  A second diagnostic log showed the exact same pattern as the first: `"Ad starts in
  5s"` appeared 222 times, always attached to a preloaded video several slots ahead,
  never once the video actually on screen (the current-video scoping fix correctly
  excluded all 222). It's possible this text is a preload-only marker that disappears
  once that video actually becomes current, in which case no keyword can ever catch it
  this way - there's no confirmed log yet of what an ad actually *looks like* while
  genuinely playing on screen. If you see a real ad and skipping still doesn't fire,
  that's the log worth capturing next.
- The blocked-creator detector matches on the creator's **display name**, not their
  `@username` (see *Identifying a creator*, above) - confirmed against a real device's
  diagnostic log, where TikTok never rendered an `@handle` node at all. Display names
  aren't guaranteed unique the way `@usernames` are, so this is a real, if uncommon,
  false-positive risk - not just a theoretical one.
- The Block and Download tap sequences (`moreOptionsKeywords`, `blockOptionKeywords`,
  `blockConfirmKeywords`, `downloadOptionKeywords`) are the least verified part of this
  app - the exact menu structure and button wording were not confirmed against a live
  TikTok install during development. Expect to tune these after first real use.
- Live streams are now handled (see *Live streams*, above), but the Live room menu
  structure - specifically **Live Room Menu Entry Labels**, how you get to the Block
  option in the first place - is a best-effort guess, not confirmed against a live
  TikTok install, same caveat as the rest of Real TikTok Integration. The Shop tab has
  its own on-screen layout too and still isn't handled at all.
- **`FilterEngine.isLiveStream` false-positives constantly, confirmed against a real
  diagnostic log** - `live=true` fired on 906 of 912 screen reads (99%) in a session
  that was overwhelmingly normal video scrolling, not Live rooms. Cause: TikTok shows a
  "Live now" preview rail (several `LIVE`-labeled entries, e.g. `"LIVE, GB News, LIVE,
  Christina Podolyan, ..., 3 LIVE streams | 20 Stories"`) *inline within a normal
  video's own screen*, not as a separate preloaded video - so the same current-video
  scoping fix used for ad/creator matching doesn't fix this one, since the rail is
  legitimately part of the current video's own text. The practical consequence: the
  real Block automation almost always believes it's on a Live room and uses
  `liveBlockActionStages()` instead of the normal video's tap sequence - this is a real,
  currently-live risk to Block's reliability on ordinary videos, not just a Live-room
  edge case. Not fixed here for lack of a confirmed example of what a screen where
  you're *actually inside* a Live room looks like (as opposed to this rail) - the next
  diagnostic log worth capturing is one from a few seconds spent actually watching a
  real Live room, to find a signal that reliably tells the two apart.
- Audio extraction assumes TikTok's saved video uses an audio codec `MediaMuxer` can
  remux into an MP4/M4A container (AAC, in practice) - if TikTok ever used something
  else, extraction for that file would fail and log accordingly rather than silently
  produce a broken file.
