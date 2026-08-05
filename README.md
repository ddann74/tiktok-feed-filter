# TikTok Feed Filter

An Android accessibility-service app that watches your For You feed and swipes past ads
and creators you've blocked - automatically, without you having to react to them
yourself. There's no official API for either "is this an ad" or "skip this video", so
this works the same way a human would: it reads what's on screen and, if it looks like
one of those two things, performs the same swipe-up gesture a finger would.

## How it works

- **Accessibility Service** (`TikTokFilterService`) gets notified whenever TikTok's
  screen content changes, reads every piece of text currently on screen (via the
  accessibility node tree - no screenshots, no screen recording), and hands that off to
  a pure decision function.
- **`FilterEngine`** decides, from that text alone:
  - Is the current video's creator (found as a standalone `@handle` node) in your
    blocked list?
  - Does any on-screen text contain one of your configured ad keywords (default:
    "Sponsored"), case-insensitive?
  - A blocked creator takes priority over an ad-keyword match on the same screen.
- If either fires, the service dispatches a swipe-up gesture and logs what happened
  (visible in the app under **Activity**) so the filtering stays auditable rather than
  a silent black box.
- A short cooldown after every skip avoids double-triggering while the next video is
  still loading in.

## This is inherently heuristic - read this before relying on it

There is no official way for a third-party app to control TikTok's algorithm or know
"this is an ad" with certainty. This app reads on-screen text and pattern-matches
against it, which means:

- **It will miss ads that don't say "Sponsored"** (or whatever keyword you've
  configured) anywhere on screen. Add more keywords under **Ad Keywords** as you
  notice wording it misses - no rebuild needed, it's a plain in-app list.
- **It could theoretically false-positive** on a normal video whose caption happens to
  contain a configured keyword. Keep the keyword list specific for this reason.
- **TikTok changing its app layout or wording can silently reduce accuracy.** The text
  traversal itself (walking the entire accessibility tree, not specific view IDs) is
  reasonably resilient to layout changes, but the *keywords* and the *handle-detection
  shape* (a node reading exactly `@handle`) are assumptions about today's TikTok, not
  guarantees about tomorrow's.
- If skips stop happening, check the **Activity** log first - if nothing's been
  skipped in a while despite ads clearly appearing, TikTok's ad wording likely changed.

## Setup

1. **Open in Android Studio**, let Gradle sync.
2. **Run it**, then in the app tap **Open Accessibility Settings** and turn on
   "TikTok Feed Filter" - Android requires this to be granted manually in Settings,
   an app can never enable it for itself.
3. Add any creators you want auto-skipped under **Blocked Creators** (with or without
   the leading `@`, doesn't matter).
4. Adjust **Ad Keywords** if needed - "Sponsored" is the default and covers most cases.
5. Open TikTok and scroll - matching videos should now skip on their own. Check
   **Activity** in this app afterward to see what it caught.

**minSdk 24 (Android 7.0)** is a hard requirement, not a stylistic choice -
`AccessibilityService.dispatchGesture`, the actual mechanism used to perform a skip,
doesn't exist before API 24.

## Privacy

The manifest declares **no permissions at all** beyond the accessibility service's own
system-granted binding permission - no internet, no storage, nothing. Everything
(blocked creators, keywords, skip counts, the activity log) lives in this app's own
SharedPreferences, on-device, and is never transmitted anywhere. The accessibility
service only reads screens belonging to the configured target package(s) (TikTok by
default) and ignores every other app.

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
TikTokFilterService   Accessibility service - screen reading + gesture dispatch
SettingsRepository    Blocked creators, ad keywords, target packages, toggles
StatsRepository       Skip counters + a capped, newest-first activity log
MainActivity          Setup UI: enable service, edit filters, view activity
```

Deliberately minimal dependencies: no networking library, no database - SharedPreferences
is more than sufficient for lists this small, and there's no server component to talk to
in the first place.

## Known open items

- The blocked-creator detector relies on TikTok rendering the current video's creator
  as its own text node reading exactly `@handle`. This has been true in testing but
  isn't a documented, stable contract - if it stops matching, that's the first thing
  to check.
- No handling yet for TikTok Live streams or the Shop tab, which have different on-screen
  layouts than the standard FYP video view - filtering there hasn't been validated.
