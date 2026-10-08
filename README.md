# Better YouTube Browser (working name)

A fast, lightweight Android browser made for YouTube. It has the Better YouTube engine built in: it holds the best
quality your device can really play, fixes stalls fast, blocks ads and keeps audio steady. It installs from GitHub
and updates itself.

Status: early development (M1). Not released yet. See docs/ARCHITECTURE.md for decisions, limits and milestones.

Built first for a Lenovo Tab M10 (3rd Gen) tablet, then phones.

## Layout
- core/      shared JavaScript: the Better YouTube script (core/betteryt.user.js)
- android/   the Android app (Kotlin, system WebView)
- docs/      architecture and decisions

## Notes
- Blocking YouTube ads goes against YouTube's terms, and YouTube changes often. Use at your own risk.
- Licence: MIT (see LICENSE).
- Never commit signing keystores, tokens or local.properties (see .gitignore).
