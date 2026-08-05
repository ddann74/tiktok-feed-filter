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
  (keyword match) or from a blocked creator (`@handle` match), and the service dispatches
  a swipe-up gesture if so - the same gesture a finger would perform.
- **Floating Block/Download buttons** are drawn over TikTok whenever it's in the
  foreground (an accessibility overlay window - see *Floating buttons*, below).
- **Real TikTok integration** (`TikTokActionCoordinator`) drives two multi-tap
  automations - really blocking a creator in TikTok, and downloading + extracting a
  video's audio - by finding and tapping TikTok's own menu items (see its own section
  below; this is the most fragile part of the app and worth reading before relying on it).
- Every outcome - a skip, a block, a download, an extraction, or any of them failing -
  is written to the **Activity** log, so nothing here is a silent black box.

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
- **The three-step Block automation and two-step Download automation can each stall
  partway through** if a step's keyword doesn't match what's actually on screen (a menu
  that didn't open, wording that changed, a step that doesn't exist for a given video).
  Each attempt has a 4-second timeout per step; when it times out, the **Activity** log
  says so explicitly rather than pretending it worked.
- If something stops working, check **Activity** first - it's the fastest way to tell
  "TikTok changed something" from "this app has a bug."

## Floating buttons

Two small buttons - **Block** (red) and **Download** (blue) - appear near the edge of
the screen whenever TikTok is in front, and disappear when you switch to another app.
They're drawn using an accessibility overlay window, which is why this app doesn't need
the separate "display over other apps" permission most floating-bubble apps do - only
an accessibility service can request this particular window type, and it's already
covered by the Accessibility permission you grant in Setup.

- **Block** identifies the creator of whatever video is currently on screen (the same
  `@handle` detection `FilterEngine` uses) and adds them to your local Blocked Creators
  list immediately - then, if **Really block in TikTok** is on, also attempts the real
  TikTok block automation described below.
- **Download** attempts the download-and-extract-audio automation described below for
  whatever video is currently on screen.

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

**Downloading + extracting audio** taps: the video's "more options" menu → **Save
video** (TikTok's own official download option). If a creator has disabled downloads
for their video, this option simply doesn't exist on screen, the tap sequence times
out, and the **Activity** log says the download wasn't available for that video.

**This app will never try to download a video whose creator has disabled downloads by
any other means (e.g. screen-recording to bypass that setting).** That's a deliberate
line: automating a button on your own phone is one thing, circumventing a creator's
explicit choice about their own content is another, and this app only does the former.

Once a download succeeds, the app looks for the newly-saved video in the device's media
library (polling briefly, since the file isn't necessarily written the instant the tap
registers), then extracts its audio track on-device - pure container remuxing via
Android's built-in `MediaExtractor`/`MediaMuxer`, no re-encoding, no external library,
no network call of any kind - and saves it as an `.m4a` file under this app's own
external files directory (`Android/data/com.tiktokfilter.app/files/ExtractedAudio/`,
visible to any file manager, cleared if the app is uninstalled).

The four keyword lists under **Real TikTok Integration** in Setup control what each tap
sequence looks for - "More Options" Button Labels, "Block" Menu Option Labels, Block
Confirmation Dialog Labels, and "Download/Save" Menu Option Labels - each with sensible
defaults, each editable without a rebuild.

## Setup

1. **Open in Android Studio**, let Gradle sync.
2. **Run it**, then in the app tap **Open Accessibility Settings** and turn on
   "TikTok Feed Filter" - Android requires this to be granted manually in Settings,
   an app can never enable it for itself.
3. Grant the storage-read permission when prompted (only used to locate a video TikTok
   itself just saved, for audio extraction - see *Privacy*).
4. Add any creators you want auto-skipped under **Blocked Creators** (with or without
   the leading `@`, doesn't matter).
5. Adjust **Ad Keywords** if needed - "Sponsored" is the default and covers most cases.
6. Leave **Really block in TikTok** and **Show floating Block/Download buttons** on
   (both default on) if you want the fuller feature set; turn either off if you'd
   rather stick to passive skip-only filtering.
7. Open TikTok and scroll - matching videos should now skip on their own, and the
   floating Block/Download buttons should appear. Check **Activity** in this app
   afterward to see what it caught (or attempted and couldn't complete).

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

- The blocked-creator detector relies on TikTok rendering the current video's creator
  as its own text node reading exactly `@handle`. This has been true in testing but
  isn't a documented, stable contract - if it stops matching, that's the first thing
  to check.
- The Block and Download tap sequences (`moreOptionsKeywords`, `blockOptionKeywords`,
  `blockConfirmKeywords`, `downloadOptionKeywords`) are the least verified part of this
  app - the exact menu structure and button wording were not confirmed against a live
  TikTok install during development. Expect to tune these after first real use.
- No handling yet for TikTok Live streams or the Shop tab, which have different on-screen
  layouts than the standard FYP video view - filtering and the automations there haven't
  been validated.
- Audio extraction assumes TikTok's saved video uses an audio codec `MediaMuxer` can
  remux into an MP4/M4A container (AAC, in practice) - if TikTok ever used something
  else, extraction for that file would fail and log accordingly rather than silently
  produce a broken file.
