# Playhead: architecture and decisions

Playhead (app.playhead.browser) is the app. "Better YouTube" is the name of the script inside it.

## Goal
A fast, light, YouTube-first Android browser: Brave-style protection, with the Better YouTube quality, recovery and
audio engine built in. Installs from GitHub and updates itself. Android only. Tablet first (Lenovo Tab M10 3rd Gen),
phones after that.

## Target device (first)
Lenovo Tab M10 (3rd Gen): Unisoc T610 (2x Cortex-A75 + 6x Cortex-A55), Mali-G52 MP2, 3 or 4 GB RAM,
10.1" 1920x1200 60 Hz IPS, Android 11 (upgradable to 12). A budget SoC, so:
- keep memory and CPU use low: at most two WebViews alive (see Tabs), no heavy UI, no background work while idle
- a quality ceiling above 1080p buys little on a 1200p panel and costs decode power
- ask the OS what the video decoders can really do (MediaCodecList) instead of guessing

## Decisions

1. Engine: the system Chromium WebView, not a Brave/Chromium fork.
   Forking Chromium needs hundreds of GB of disk and a build farm for every release; it is not realistic here.
   WebView gives Chromium codecs and Media Source, a small APK, document-start script injection in the page world
   (WebViewCompat.addDocumentStartJavaScript) and request interception (shouldInterceptRequest).
   Trade-off: the engine version is whatever Android System WebView the device has installed.

2. Desktop site by default on tablets. A mobile user agent gets YouTube's mobile site; the script is built and tested
   against the desktop site. The user agent is derived from the installed WebView version.

3. One script core (core/betteryt.user.js). The browser injects it at document start on youtube.com. Platform features
   a userscript cannot have go behind a small native bridge: real settings storage, decoder capabilities,
   update info, toasts.

4. Network layer (new power over a userscript): refuse ad and tracker requests before they leave the device.
   The in-page ad stripping stays the main YouTube defence, because YouTube serves its ads inside first-party data.

5. Auto-update (public repo, so no token is needed):
   a) Script and filter lists: fetched from GitHub Releases, hash-checked, cached, applied on the next load.
   b) The app: the APK is downloaded from the latest GitHub Release and handed to PackageInstaller.
      Android 11 always shows an install confirmation; silent updates only exist on Android 12+ and only
      after the first manual install.
   The Android signing key must never change (updates only install over the same key). Keep it out of the repo and
   back it up somewhere safe.

6. v1 scope: tabs, address/search bar, back/forward/reload, YouTube-first start page, fullscreen video, the existing
   settings panel, blocking and the updater. Bookmarks, history, settings, downloads and find in page are already in.
   Out of scope for v1: sync, extensions, passwords.

7. Search engines are a fixed built-in list (Support.kt, Search.ENGINES): each is a name plus a URL prefix that the
   encoded query is appended to. A user-defined engine would be a URL template with a %s placeholder; not built yet.

## Tabs (built for about 100 tabs)
Only the active tab and one recent tab (hotLimit 1; 0 on low-RAM devices) keep a live WebView. Every other tab is
saved to disk: files/tabs/<id>.state (WebView state), <id>.jpg (thumbnail) and tabs.json (the list, debounced writes).
Opening a tab restores it. onTrimMemory drops background WebViews, and background tabs have media paused. The switcher
is a RecyclerView grid with a pooled set of cards. Popups and target=_blank links open as new tabs, and Back closes
them and returns to the opener.

Debug builds only: `am start ... --ei open_tabs 100` and `--ei cycle_tabs 30 --el cycle_ms 2500` for stress tests,
log tags PLH (tab and live counts, blocked requests) and BYB (device info timing), and tools/cdp.mjs for DevTools.

## What the app tells the script (window.__BYT_NATIVE, version 1)
Device model, memory, screen size and refresh rate, and for VP9, AV1, H.264 and HEVC whether a hardware decoder
exists and the tallest picture it handles (at 30 and 60 fps). From these the app picks "Max": the tallest tier that
both the decoders and the screen can use (a video fills the long edge at 16:9). It is probed once per Android
build and cached (about 45 ms the first time, 4 ms after). An explicit tier chosen in the panel still wins.
The script tells the app, through BYTNative.postMessage, whether "Block ad requests" is on, and the app's own
request blocker (Blocker.kt) follows that switch. Its rules mirror the script's AD_REQ pattern.

## Honest limits
- Blocking YouTube ads goes against YouTube's terms and YouTube changes often; expect ongoing maintenance.
- Network-level blocking cannot see inside YouTube's own responses; that is what the script does.
- Brave-level fingerprinting protection is out of reach on stock WebView.
- The repo is public: never commit keystores, tokens or personal data.

## Milestones
- M0 scaffold (this folder, local git)                                   done
- M1 Android shell: loads YouTube, script injected, panel works, fullscreen   done (emulator-verified)
- M2 tabs, address bar, navigation polish                                    done (emulator-verified)
- M3 native bridge: decoder-aware Max, request blocking                     done (emulator-verified)
- M4 updater (script channel, then APK), release signing key  done (emulator-verified)
- M5 performance pass on the tablet, then phone layouts
- M6 public GitHub release: README with screenshots, signed APK in Releases

## Updates and release signing
- Source: the latest GitHub Release (UpdateManager.kt, BuildConfig.UPDATE_BASE). A release has three files:
  manifest.json (versions plus a SHA-256 for the script and the APK), betteryt.user.js and playhead.apk.
  tools/make-release.ps1 builds a signed release and writes them to dist/ (git-ignored). It never uploads anything.
- Script channel: checked in the background at most about every 12 hours (setting "Automatic updates", on by default).
  The download must match the manifest hash, look like the Better YouTube script and carry the manifest version, or it
  is thrown away. It is stored in files/script, used only with the app version it was downloaded for, and only while it
  is newer than the bundled copy. New tabs use it; open tabs keep the old one until they reload.
- App channel: the manifest only reports a newer versionCode. Installing is always the person's choice (Settings >
  Updates > Install): the APK is downloaded, hash-checked, confirmed to be Playhead at the expected version, then handed to
  PackageInstaller. Android shows its own confirmation (Android 11 always does), and refuses it if the signing key differs.
- Limit: the hash is checked against a manifest from the same place, so it protects against damaged or swapped downloads,
  not against a compromised GitHub account. Only the APK has a real signature check (done by Android).
- Signing key: playhead-release.jks lives outside the repo (see signing.properties next to it). Builds read its path from
  the PLAYHEAD_SIGNING environment variable. It must never change or be lost: installed copies can only update to APKs
  signed with the same key. The first release APK is signed differently from debug builds, so debug builds must be
  uninstalled once before installing a release.
- Testing without publishing: debug builds accept `--es update_url http://localhost:8099/` and `--ez update_check true`
  and may use plain http to localhost only; serve manifest.json, betteryt.user.js and playhead.apk from a local folder.