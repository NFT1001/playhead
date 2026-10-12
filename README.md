# Playhead

A fast, light Android browser made for YouTube. It has the Better YouTube engine built in: it holds the best
quality your device can really play, fixes stalls fast, blocks ad requests and keeps audio steady. It is meant to
install from GitHub and update itself.

Status: early development (v0.5.0). Download playhead.apk from the latest release (Releases page). See docs/ARCHITECTURE.md for decisions, limits and milestones.

Tested on an emulator so far, not yet on real hardware. Built first for a Lenovo Tab M10 (3rd Gen) tablet, then phones. Android only.

## What works today
- Tabs (a tab switcher grid, built to stay smooth with about 100 tabs), address and search bar, back/forward/reload
- Start page, bookmarks, history, downloads, find in page, share, desktop-site toggle
- Choice of search engine: YouTube, Brave Search, DuckDuckGo, Google, Bing, Startpage, Ecosia, Qwant, Yahoo, Wikipedia, or add your own custom one
- Long-press link actions, popups open as new tabs, file chooser, camera/microphone/location prompts, keyboard shortcuts
- Better YouTube script (v5.5) built in, with a native ad-request blocker and decoder-aware "Max" quality

## Layout
- core/      shared JavaScript: the Better YouTube script (core/betteryt.user.js)
- android/   the Android app (Kotlin, system WebView), package app.playhead.browser
- tools/     cdp.mjs, a DevTools helper for debug builds
- docs/      architecture and decisions

## Notes
- Blocking YouTube ads goes against YouTube's terms, and YouTube changes often. Use at your own risk.
- The Better YouTube script is MIT, copyright NFT1001. Licence for this repo: MIT (see LICENSE).
- Never commit signing keystores, tokens or local.properties (see .gitignore).
