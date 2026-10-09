// ==UserScript==
// @name         Better YouTube
// @namespace    http://tampermonkey.net/
// @version      5.5
// @description  Holds YouTube at the best quality your device can actually play, fixes stalls fast, blocks ads, works on Shorts, respects your manual choices, and adds extras (full-resolution frame capture, hide Shorts, dismiss "Continue watching?").
// @author       NFT1001
// @match        https://www.youtube.com/*
// @match        https://m.youtube.com/*
// @icon         https://www.google.com/s2/favicons?sz=64&domain=youtube.com
// @run-at       document-start
// @license      MIT
// @grant        none
// @noframes
// ==/UserScript==

/*
MIT License

Copyright (c) 2026 NFT1001

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
*/

/*
 v5.4 overview
 -------------
 QUALITY    Picks the best quality that exists for the video (up to your ceiling) and holds it.
            If you pick a quality in YouTube's own gear menu, that choice wins for that video.
            A watchdog re-requests a switch that never arrives.
 CODEC      "Recommended" asks the browser whether AV1 is hardware-decoded: if yes AV1 is left
            alone, if not AV1 is hidden so YouTube serves VP9. Manual: VP9 / AV1 / H.264.
            "Default" leaves codec choice entirely to YouTube.
 SMOOTH     If the device drops a large share of frames for ~10 s, the ceiling steps down one tier
 GUARD      (never below 720p) and is remembered for the tab session.
 RECOVERY   Decides from evidence (playhead AND download buffer): nudge, then reload, then stop.
            A slow-but-moving download is never reloaded; adaptive relief lets YouTube's own
            streaming take over until the buffer is healthy, then the lock returns.
 ADS        Removes ads from YouTube's own data before they play (player + feed), hides ad slots and
            banners, refuses ad/tracking requests the page makes, and as a last resort mutes and
            fast-forwards any video ad that still plays. It works inside the page, so it cannot match a
            browser's network-level blocking, and YouTube changes often: see DEVELOPER below.
 SHORTS     Quality lock, stall recovery and frame capture work on Shorts too. Optional: open Shorts in
            the normal player, or hide Shorts entirely.
 EXTRAS     Save a full-resolution PNG of the current frame (Alt+S), hide Shorts, hide end cards
            and pause suggestions, turn off Up Next autoplay, dismiss "Continue watching?".
 DEVELOPER  Extras > Developer log records what the script sees in YouTube's page (selectors, player
            API, data shapes, errors). "Health check" shows what broke after a YouTube update and
            "Copy report" gives a diagnostic to fix it from.
 AUDIO      "Original loudness" removes YouTube's loudness data so loud videos are not turned down (applies
            from the next video). A watchdog re-syncs audio that drops out while the picture keeps moving,
            and again when the audio device changes.
 UI         Status chip beside the title (floating on Shorts, phones and unknown layouts). Alt+B opens
            the panel. Tabs: Playback, Blocking, Extras, and Debug (only while Developer log is on).

 Console: betterYT.settings, betterYT.set(key, value), betterYT.reload(), betterYT.saveFrame(),
          betterYT.health(), betterYT.report()
*/

(function () {
    'use strict';

    if (window.__betterYTLoaded) return;   // never run twice (e.g. two copies installed)
    window.__betterYTLoaded = true;

    const VERSION = '5.5';

    // ---- Developer log: always keeps a small ring of what happened and which errors were swallowed, so a
    //      YouTube change that breaks something can be diagnosed afterwards. Console echo only in dev mode. ----
    let devOn = false;
    const devRing = [];
    const devErrors = new Map();
    const devLog = (tag, msg, data) => {
        const e = { t: Date.now(), tag, msg };
        if (data !== undefined) e.data = data;
        devRing.push(e);
        if (devRing.length > 400) devRing.shift();
        if (devOn) console.debug('[BetterYT:dev]', tag, msg, data !== undefined ? data : '');
    };
    const log = (...a) => devLog('log', a.map(String).join(' '));
    const say = (msg) => { console.log('[BetterYT] ' + msg); devLog('event', msg); };   // visible to everyone
    const noteError = (e) => {
        try {
            const msg = String((e && e.message) || e).slice(0, 160);
            const cur = devErrors.get(msg) || { n: 0, stack: '' };
            cur.n++;
            if (!cur.stack && e && e.stack) cur.stack = String(e.stack).split('\n').slice(0, 3).join(' | ').slice(0, 300);
            devErrors.set(msg, cur);
            if (devErrors.size > 60) devErrors.delete(devErrors.keys().next().value);
        } catch (x) { /* ignore */ }
    };
    const safe = (fn, fallback) => {
        try { const r = fn(); return r === undefined ? fallback : r; } catch (e) { noteError(e); return fallback; }
    };

    // ==========================================
    // DEVICE
    // ==========================================
    const UA = navigator.userAgent || '';
    const IS_MOBILE_SITE = location.hostname === 'm.youtube.com';
    const IS_PHONE = IS_MOBILE_SITE || /iPhone|iPod|Android.*Mobile|Mobile.*Firefox/i.test(UA) || !!safe(() => navigator.userAgentData.mobile, false);
    const SAVE_DATA = !!safe(() => navigator.connection.saveData, false);

    // Handed over by the Better YouTube Browser app before this script runs (decoders, screen, memory).
    // null in a plain userscript manager, where the script falls back to browser-side guesses.
    const NATIVE = safe(() => (window.__BYT_NATIVE && window.__BYT_NATIVE.v === 1 ? window.__BYT_NATIVE : null), null);

    // ==========================================
    // SETTINGS
    // ==========================================
    const STORE_KEY = 'ybSettings5';

    // Quality tiers YouTube exposes, lowest -> highest (index = rank).
    const RANK = { tiny: 0, small: 1, medium: 2, large: 3, hd720: 4, hd1080: 5, hd1440: 6, hd2160: 7, hd2880: 8, highres: 9 };
    const KEY_BY_RANK = Object.keys(RANK).sort((a, b) => RANK[a] - RANK[b]);
    const QL = { tiny: '144p', small: '240p', medium: '360p', large: '480p', hd720: '720p', hd1080: '1080p', hd1440: '1440p', hd2160: '2160p', hd2880: '2880p', highres: '4320p' };
    const PX = { 144: 'tiny', 240: 'small', 360: 'medium', 480: 'large', 720: 'hd720', 1080: 'hd1080', 1440: 'hd1440', 2160: 'hd2160', 2880: 'hd2880', 4320: 'highres' };

    // What "Max" means. In the app: the best this device can play smoothly (hardware decoders and screen).
    const NATIVE_RANK = NATIVE && RANK[NATIVE.ceilingTier] !== undefined ? RANK[NATIVE.ceilingTier] : 9;
    const MAX_HINT = NATIVE
        ? `Max = up to ${QL[NATIVE.ceilingTier]} on this device (${NATIVE.limitedBy === 'screen' ? 'screen size' : 'hardware decoder'} limit). Pick a higher tier to override.`
        : 'Highest quality each video offers (up to 8K).';

    const QUALITY_OPTIONS = [
        ['max', 'Max', MAX_HINT],
        ['hd2160', '2160p'],
        ['hd1440', '1440p'],
        ['hd1080', '1080p'],
        ['hd720', '720p'],
        ['large', '480p'],
        ['medium', '360p'],
        ['auto', 'Default', 'Do not control quality: YouTube decides, as if this script were off.']
    ];

    const CODEC_OPTIONS = [
        ['smart', 'Recommended', 'Picks the best codec for this device.'],
        ['vp9', 'VP9', 'Hides AV1: higher bitrate, no software AV1 decoding.'],
        ['av1', 'AV1', 'Hides VP9; H.264 (1080p) if there is no AV1.'],
        ['h264', 'H.264', 'Lightest on the CPU, but capped at 1080p.'],
        ['auto', 'Default', 'YouTube picks the codec. This script does not interfere.']
    ];

    const DEFAULTS = {
        quality: SAVE_DATA ? 'hd720' : (IS_PHONE ? 'hd1080' : 'max'),
        codec: 'smart',
        recovery: true,
        adaptive: true,
        smoothGuard: true,
        autoPlay: true,
        autonavOff: false,
        keepWatching: true,
        hiddenDowngrade: false,
        hideShorts: false,
        shortsRedirect: false,
        hideOverlays: false,
        adBlock: true,
        adRequests: true,
        adSkip: true,
        audioFix: true,
        audioOriginal: false,
        notify: true,
        devLog: false
    };
    const TOGGLES = ['recovery', 'adaptive', 'smoothGuard', 'autoPlay', 'autonavOff', 'keepWatching', 'hiddenDowngrade', 'hideShorts', 'shortsRedirect', 'hideOverlays', 'adBlock', 'adRequests', 'adSkip', 'audioFix', 'audioOriginal', 'notify', 'devLog'];

    const normalize = (s) => {
        const out = Object.assign({}, DEFAULTS, s || {});
        if (!QUALITY_OPTIONS.some(o => o[0] === out.quality)) out.quality = DEFAULTS.quality;
        if (!CODEC_OPTIONS.some(o => o[0] === out.codec)) out.codec = DEFAULTS.codec;
        for (const k of TOGGLES) out[k] = !!out[k];
        return out;
    };

    const loadSettings = () => {
        let s = {};
        let fresh = false;
        try {
            const raw = localStorage.getItem(STORE_KEY);
            if (raw) s = JSON.parse(raw) || {};
            else fresh = true;
        } catch (e) { fresh = true; }

        // Migrate from v4.x (single "ytFixerPreferredQuality" string)
        if (fresh) {
            const old = safe(() => localStorage.getItem('ytFixerPreferredQuality'), null);
            if (old) s.quality = (old === 'Disable' || old === 'auto') ? 'auto' : (old === 'highres' ? 'max' : old);
        }
        return normalize(s);
    };

    const settings = loadSettings();
    devOn = settings.devLog;
    // Tell the app which ad-request setting applies, so its network layer follows the panel's toggle.
    const pushNative = () => {
        const port = safe(() => window.BYTNative, null);
        if (port) safe(() => port.postMessage(JSON.stringify({ adRequests: !!(settings.adBlock && settings.adRequests) })));
    };
    const saveSettings = () => { try { localStorage.setItem(STORE_KEY, JSON.stringify(settings)); } catch (e) { /* ignore */ } pushNative(); };
    saveSettings();

    // ==========================================
    // TUNABLES
    // ==========================================
    const GRACE_MS = 3500;            // quiet period after a video starts (progress-based detection makes this safe)
    const NAV_SUSPEND_MS = 100000;    // monitoring suspended while a navigation is in flight
    const STALL_MS = 5000;            // nothing moving (playhead or download) for this long = stalled
    const STALL_POST_RELOAD_MS = 9000;// more patience right after a reload (fresh connection, big first segments)
    const STALL_AFTER_NUDGE_MS = 4000;// how long a nudge gets to work before we reload
    const SLOW_BUFFER_MS = 10000;     // buffering this long while still downloading = slow network
    const COOLDOWN_MS = 6000;         // minimum gap between reloads
    const HEALTHY_MS = 5000;          // smooth playback this long re-arms the nudge
    const HEALTHY_RESET_MS = 30000;   // ...this long clears the failure streak
    const MAX_ATTEMPTS = 4;           // consecutive failed reloads before giving up (VOD)
    const MAX_LIVE_ATTEMPTS = 3;      // ...live streams
    const RELIEF_MIN_MS = 6000;       // adaptive relief lasts at least this long
    const RELIEF_MAX_MS = 60000;      // ...and at most this long
    const RELIEF_EXIT_BUFFER_S = 15;  // seconds of buffer ahead needed to restore the lock
    const QUALITY_RETRY_MS = 3000;    // spacing between quality requests
    const QUALITY_WINDOW_MS = 60000;  // quality request budget window
    const QUALITY_MAX_SETS = 12;      // requests allowed per window
    const SWITCH_REPIN_MS = 7000;     // a requested quality that has not arrived after this long gets re-requested
    const SWITCH_NUDGE_MS = 14000;    // ...and after this long the playhead is nudged to flush stale buffer
    const DROP_RATIO = 0.15;          // share of frames dropped (per sample) that counts as "cannot keep up"
    const DROP_MIN_FRAMES = 100;      // frames needed for a meaningful sample
    const GUARD_FLOOR_RANK = 4;       // smooth guard never steps below 720p

    // ==========================================
    // CODEC CONTROL (runs immediately, document-start)
    // YouTube asks the browser "can you play this codec?" before choosing a stream.
    // Answering "no" for the codec we do not want makes it serve another one.
    // ==========================================
    const CODEC_BLOCK = {
        vp9: /av01/i,                    // hide AV1
        av1: /vp0?9|vp8/i,               // hide VP9 / VP8
        h264: /av01|vp0?9|vp8/i          // hide everything but H.264
    };

    // Is AV1 hardware-decoded here? true / false / null (unknown). Cached so the very first
    // page load after install is the only one that runs before the answer is known.
    let av1Hw = (() => {
        if (NATIVE && NATIVE.codecs && NATIVE.codecs.av1) return !!NATIVE.codecs.av1.hw;   // the OS knows: no guessing
        const v = safe(() => localStorage.getItem('ybAv1Hw'), null);
        return v === '1' ? true : (v === '0' ? false : null);
    })();

    const origDecodingInfo = safe(() => navigator.mediaCapabilities.decodingInfo.bind(navigator.mediaCapabilities), null);

    if (origDecodingInfo && !NATIVE) {
        safe(() => origDecodingInfo({
            type: 'media-source',
            video: { contentType: 'video/mp4; codecs="av01.0.08M.08"', width: 1920, height: 1080, bitrate: 4000000, framerate: 30 }
        }).then((r) => {
            av1Hw = !!(r && r.supported && r.powerEfficient);
            safe(() => localStorage.setItem('ybAv1Hw', av1Hw ? '1' : '0'));
        }).catch(() => { /* leave unknown */ }));
    }

    const isBlocked = (type) => {
        if (typeof type !== 'string') return false;
        if (settings.codec === 'auto') return false;
        if (settings.codec === 'smart') return av1Hw === false && /av01/i.test(type);
        const re = CODEC_BLOCK[settings.codec];
        return !!re && re.test(type);
    };

    const hookMediaSource = (MS) => {
        if (MS && typeof MS.isTypeSupported === 'function' && !MS.__ybHooked) {
            const orig = MS.isTypeSupported.bind(MS);
            MS.isTypeSupported = (t) => (isBlocked(t) ? false : orig(t));
            MS.__ybHooked = true;
        }
    };
    safe(() => hookMediaSource(window.MediaSource));
    safe(() => hookMediaSource(window.ManagedMediaSource));   // Safari / iOS

    // Remember which video codec the browser was actually given. Some players (m.youtube.com) have no
    // stats-for-nerds, so this is how the chip can still show the real codec.
    let lastVideoType = '';
    const noteVideoType = (t) => { if (typeof t === 'string' && /^video\//i.test(t)) lastVideoType = t; };
    safe(() => {
        for (const C of [window.MediaSource, window.ManagedMediaSource]) {
            const proto = C && C.prototype;
            if (proto && typeof proto.addSourceBuffer === 'function' && !proto.__ybAdd) {
                const orig = proto.addSourceBuffer;
                proto.addSourceBuffer = function (t) { noteVideoType(t); return orig.apply(this, arguments); };
                proto.__ybAdd = true;
            }
        }
        const SB = window.SourceBuffer && window.SourceBuffer.prototype;
        if (SB && typeof SB.changeType === 'function' && !SB.__ybChange) {
            const orig = SB.changeType;
            SB.changeType = function (t) { noteVideoType(t); return orig.apply(this, arguments); };
            SB.__ybChange = true;
        }
    });

    safe(() => {
        const proto = window.HTMLMediaElement && window.HTMLMediaElement.prototype;
        if (proto && !proto.__ybHooked) {
            const orig = proto.canPlayType;
            proto.canPlayType = function (t) { return isBlocked(t) ? '' : orig.call(this, t); };
            proto.__ybHooked = true;
        }
    });

    safe(() => {
        const mc = navigator.mediaCapabilities;
        if (mc && origDecodingInfo && !mc.__ybHooked) {
            mc.decodingInfo = (cfg) => {
                const ct = cfg && cfg.video && cfg.video.contentType;
                if (isBlocked(ct)) return Promise.resolve({ supported: false, smooth: false, powerEfficient: false });
                return origDecodingInfo(cfg);
            };
            mc.__ybHooked = true;
        }
    });

    // ==========================================
    // AD BLOCKING, PART 1 (runs immediately, document-start)
    // Works inside the page: (1) removes ad data from YouTube's own responses before the player can act
    // on it, (2) refuses ad / tracking requests the page makes. Part 2 (CSS, fast-forward fallback,
    // warning removal) runs once the page exists, further down.
    // ==========================================
    const adStats = { player: 0, feed: 0, requests: 0, skipped: 0, enforcement: 0 };
    const audioStats = { loudness: 0, dropouts: 0, deviceNudges: 0 };

    // How busy is the page's main thread? Freezes of 50 ms or more stall audio and video alike, so the
    // developer tab shows them (and the script's own cost) to tell whether lag comes from us, YouTube or the PC.
    const perf = { n: 0, sum: 0, max: 0, long: [] };
    safe(() => {
        new PerformanceObserver((list) => {
            const now = Date.now();
            for (const e of list.getEntries()) perf.long.push({ t: now, d: Math.round(e.duration) });
            while (perf.long.length && perf.long[0].t < now - 60000) perf.long.shift();
        }).observe({ entryTypes: ['longtask'] });
    });

    const PLAYER_AD_KEYS = ['adPlacements', 'playerAds', 'adSlots'];
    const AD_RENDERERS = {
        adSlotRenderer: 1, inFeedAdLayoutRenderer: 1, promotedSparklesWebRenderer: 1, promotedSparklesTextSearchRenderer: 1,
        displayAdRenderer: 1, compactPromotedVideoRenderer: 1, promotedVideoRenderer: 1, searchPyvRenderer: 1,
        bannerPromoRenderer: 1, statementBannerRenderer: 1, videoMastheadAdV3Renderer: 1, primetimePromoRenderer: 1,
        adSlotAndLayoutRenderer: 1, actionCompanionAdRenderer: 1
    };
    // Cheap substring test so we only parse responses that can contain ads.
    const AD_MARKERS = ['adPlacements', 'playerAds', 'adSlots', 'adSlotRenderer', 'inFeedAd', 'promotedSparkles', 'displayAdRenderer',
        'promotedVideo', 'searchPyv', 'bannerPromo', 'statementBanner', 'MastheadAd', 'primetimePromo', 'actionCompanionAd'];

    const isAdItem = (it) => {
        if (!it || typeof it !== 'object') return false;
        for (const k in it) {
            if (AD_RENDERERS[k]) return true;
            if (k === 'richItemRenderer' || k === 'richSectionRenderer') {
                const c = it[k] && it[k].content;
                if (c && typeof c === 'object') for (const kk in c) if (AD_RENDERERS[kk]) return true;
            }
        }
        return false;
    };

    // Remove ad items from arrays anywhere in a feed / search / watch-next payload. Returns how many.
    const pruneFeed = (root) => {
        let removed = 0;
        const walk = (n, d) => {
            if (!n || typeof n !== 'object' || d > 16) return;
            if (Array.isArray(n)) {
                for (let i = n.length - 1; i >= 0; i--) {
                    if (isAdItem(n[i])) { n.splice(i, 1); removed++; } else walk(n[i], d + 1);
                }
            } else {
                for (const k in n) walk(n[k], d + 1);
            }
        };
        walk(root, 0);
        return removed;
    };

    // "Stable volume": YouTube turns loud videos down using loudness data it sends with every video.
    // Removing that data leaves the audio at its original level. (Takes effect for the next video.)
    const LOUDNESS_KEYS = ['loudnessDb', 'perceptualLoudnessDb', 'trackAbsoluteLoudnessLkfs'];
    const stripLoudness = (o) => {
        let n = 0;
        const c = o && o.playerConfig && o.playerConfig.audioConfig;
        if (c && typeof c === 'object') {
            for (const k of LOUDNESS_KEYS) if (k in c) { delete c[k]; n++; }
            if (c.enablePerFormatLoudness) { c.enablePerFormatLoudness = false; n++; }
        }
        const sd = o && o.streamingData;
        if (sd) {
            for (const list of [sd.adaptiveFormats, sd.formats]) {
                if (Array.isArray(list)) for (const f of list) {
                    if (!f || typeof f !== 'object') continue;
                    for (const k of Object.keys(f)) if (/oudness/i.test(k)) { delete f[k]; n++; }
                }
            }
        }
        if (n) audioStats.loudness++;
        return n;
    };

    // Remove the ad schedule (and, if asked, the loudness data) from a player response. Returns how many ad fields.
    const prunePlayer = (r) => {
        if (!r || typeof r !== 'object') return 0;
        let n = 0;
        const each = (o) => {
            if (!o || typeof o !== 'object') return;
            if (settings.adBlock) for (const k of PLAYER_AD_KEYS) if (k in o) { delete o[k]; n++; }
            if (settings.audioOriginal) stripLoudness(o);
        };
        each(r);
        each(r.playerResponse);
        if (Array.isArray(r)) for (const it of r) { each(it); each(it && it.playerResponse); }
        return n;
    };

    // Page data objects YouTube defines at load: strip ads the moment they are assigned.
    const trapGlobal = (name, fn) => {
        try {
            const d = Object.getOwnPropertyDescriptor(window, name);
            if (d && !d.configurable) return;
            let val = window[name];
            Object.defineProperty(window, name, {
                configurable: true, enumerable: true,
                get() { return val; },
                set(v) { try { fn(v); } catch (e) { noteError(e); } val = v; }
            });
            if (val) { try { fn(val); } catch (e) { noteError(e); } }
        } catch (e) { noteError(e); }
    };
    trapGlobal('ytInitialPlayerResponse', (v) => {
        const n = prunePlayer(v);
        if (n) { adStats.player++; devLog('ads', 'pruned initial player response', n); }
        const reason = v && v.playabilityStatus && v.playabilityStatus.reason;
        if (reason && /ad ?block/i.test(String(reason))) devLog('ads', 'playability reason mentions ad blockers', String(reason).slice(0, 120));
    });
    trapGlobal('ytInitialData', (v) => {
        if (!settings.adBlock) return;
        const n = pruneFeed(v);
        if (n) { adStats.feed += n; devLog('ads', 'pruned initial data', n); }
    });

    // Ad / tracking endpoints the page may call.
    const AD_REQ = /doubleclick\.net|googlesyndication\.com|googleadservices\.com|\/pagead\/|\/api\/stats\/ads|\/pcs\/activeview|\/ptracking|\/get_midroll_/i;
    const PRUNE_URL = /\/youtubei\/v1\/(player|next|browse|search|reel\/)/;
    const urlOf = (input) => { try { return typeof input === 'string' ? input : ((input && input.url) || String(input)); } catch (e) { return ''; } };
    const blockedReq = (url) => settings.adBlock && settings.adRequests && AD_REQ.test(url);

    const pruneResponse = async (res) => {
        try {
            if (!res || !res.ok) return res;
            const text = await res.clone().text();
            const adsMaybe = settings.adBlock && AD_MARKERS.some(m => text.indexOf(m) >= 0);
            const loudMaybe = settings.audioOriginal && text.indexOf('oudness') >= 0;   // matches loudnessDb and perceptualLoudnessDb
            if (!adsMaybe && !loudMaybe) return res;
            const obj = JSON.parse(text);
            const l0 = audioStats.loudness;
            const a = prunePlayer(obj);
            const b = settings.adBlock ? pruneFeed(obj) : 0;
            if (!a && !b && audioStats.loudness === l0) return res;
            if (a) adStats.player++;
            adStats.feed += b;
            devLog('ads', 'pruned response', { player: a, feed: b, url: String(res.url || '').replace(/\?.*/, '').slice(-40) });
            const out = new Response(JSON.stringify(obj), { status: res.status, statusText: res.statusText, headers: res.headers });
            try { Object.defineProperty(out, 'url', { value: res.url }); } catch (e) { /* ignore */ }
            return out;
        } catch (e) { noteError(e); return res; }
    };

    safe(() => {
        const orig = window.fetch;
        if (typeof orig !== 'function' || orig.__yb) return;
        const wrapped = function (input) {
            const url = urlOf(input);
            if (blockedReq(url)) {
                adStats.requests++;
                return Promise.resolve(new Response(null, { status: 204 }));
            }
            const p = orig.apply(this, arguments);
            if (!(settings.adBlock || settings.audioOriginal) || !PRUNE_URL.test(url)) return p;
            return p.then(pruneResponse);
        };
        wrapped.toString = () => Function.prototype.toString.call(orig);   // look native
        wrapped.__yb = true;
        window.fetch = wrapped;
    });

    safe(() => {
        const open = XMLHttpRequest.prototype.open;
        if (open.__yb) return;
        const wrapped = function (method, url) {
            if (blockedReq(String(url))) { adStats.requests++; arguments[1] = 'data:text/plain,'; }
            return open.apply(this, arguments);
        };
        wrapped.toString = () => Function.prototype.toString.call(open);
        wrapped.__yb = true;
        XMLHttpRequest.prototype.open = wrapped;
    });

    safe(() => {
        const beacon = Navigator.prototype.sendBeacon;
        if (typeof beacon !== 'function' || beacon.__yb) return;
        const wrapped = function (url) {
            if (blockedReq(String(url))) { adStats.requests++; return true; }
            return beacon.apply(this, arguments);
        };
        wrapped.toString = () => Function.prototype.toString.call(beacon);
        wrapped.__yb = true;
        Navigator.prototype.sendBeacon = wrapped;
    });

    // ==========================================
    // STATE
    // ==========================================
    const vid = {
        id: '', startedAt: 0, sets: 0, windowStart: 0, lastSet: 0, playTries: 0, lastPlayTry: 0, hiddenApplied: false, target: null,
        userQuality: null,        // set when you pick a quality in YouTube's own menu: wins for this video
        arrivingSince: 0, fix: 0, // stuck-switch watchdog
        autonavTries: 0
    };
    const rec = {
        count: 0, nudges: 0, streak: 0, lastAt: 0, stopped: false, graceUntil: 0,
        lastTime: -1, lastEnd: -1, lastMoveAt: 0, frozenSince: 0, bufferSince: 0, healthySince: 0,
        nudged: false, stallLimit: STALL_MS
    };
    const relief = { active: false, since: 0, reason: '', slowCount: 0, latched: false };
    const guard = {
        capRank: safe(() => { const v = parseInt(sessionStorage.getItem('ybCap'), 10); return isNaN(v) ? 99 : v; }, 99),
        bad: 0, lastTotal: -1, lastDropped: 0, holdUntil: 0
    };
    const saveCap = () => safe(() => sessionStorage.setItem('ybCap', String(guard.capRank)));
    const info = { at: 0, raw: '', codec: '', res: '', bw: NaN, curH: 0, optH: 0, fps: 0 };
    const ui = { chipHost: null, chip: null, dot: null, q: null, div: null, c: null, badge: null, floating: false, misses: 0, mode: '', toastHost: null, toast: null, toastT: 0 };
    let P = null;            // panel refs (built lazily)
    let panelOpen = false;
    let lastStatus = { level: 'idle', text: '' };
    let lastState = 1;
    let cachedPlayer = null;
    let cachedVideo = null;
    let cachedKind = '';
    let tickCount = 0;
    let welcomed = false;
    let lastLogged = null;

    // ==========================================
    // HELPERS
    // ==========================================
    const isShorts = () => location.pathname.startsWith('/shorts');
    // A page with a video player we manage: watch, live, and Shorts.
    const isWatchPage = () => /^\/(watch|live|shorts)(\/|$)/.test(location.pathname);

    // Shorts pages contain TWO players: the real one (#shorts-player) and an idle, zero-size #movie_player.
    const getPlayer = () => {
        const kind = isShorts() ? 'shorts' : 'watch';
        if (cachedPlayer && cachedPlayer.isConnected && cachedKind === kind) return cachedPlayer;
        cachedKind = kind;
        cachedVideo = null;   // the video element belongs to the player
        cachedPlayer = kind === 'shorts'
            ? (document.getElementById('shorts-player') || document.getElementById('movie_player'))
            : (document.getElementById('movie_player') || document.querySelector('.html5-video-player'));
        return cachedPlayer;
    };

    const getVideo = (p) => {
        if (cachedVideo && cachedVideo.isConnected) return cachedVideo;
        cachedVideo = p ? (p.querySelector('video.html5-main-video') || p.querySelector('video')) : null;
        return cachedVideo;
    };

    const isMainVideo = (t) => !!t && t.tagName === 'VIDEO' && (t.classList.contains('html5-main-video') || t === cachedVideo);
    const playerReady = (p) => !!p && typeof p.getPlayerState === 'function';
    const adShowing = (p) => !!p && p.classList.contains('ad-showing');
    // Visible on screen? (offsetParent is null for position:fixed elements, which YouTube's dialogs are.)
    const shown = (e) => !!e && (e.offsetWidth > 0 || e.offsetHeight > 0 || e.getClientRects().length > 0);

    // Fullscreen (element fullscreen, YouTube's own class, or the browser's F11 mode): the chip stays out of the way.
    const isFullscreen = () => {
        if (document.fullscreenElement) return true;
        const p = cachedPlayer;
        if (p && p.classList && p.classList.contains('ytp-fullscreen')) return true;
        return safe(() => matchMedia('(display-mode: fullscreen)').matches, false);   // F11
    };

    // Where should the chip live right now? Beside the video title, beside a Short's channel name, or nowhere (float).
    const findDock = () => {
        if (isShorts()) {
            // Several Shorts can exist in the page at once (the ones before and after); use the one on screen.
            const names = document.querySelectorAll('yt-reel-channel-bar-view-model .ytReelChannelBarViewModelChannelName');
            for (const name of names) {
                if (!shown(name)) continue;
                const r = name.getBoundingClientRect();
                if (r.bottom > 0 && r.top < window.innerHeight) return { mode: 'shorts', el: name };
            }
            return null;
        }
        const raw = document.querySelector('ytd-watch-metadata #title') || (document.querySelector('#title h1') || {}).parentElement;
        return raw && shown(raw) ? { mode: 'title', el: raw } : null;
    };
    const codecShort = (s) => (/^av01/i.test(s) ? 'AV1' : /^vp0?9/i.test(s) ? 'VP9' : /^avc1/i.test(s) ? 'H.264' : /^vp0?8/i.test(s) ? 'VP8' : '');

    // The user's ceiling as a rank, before the smooth guard lowers it.
    const baseRank = () => (settings.quality === 'max' ? NATIVE_RANK : (RANK[settings.quality] === undefined ? NATIVE_RANK : RANK[settings.quality]));
    const effectiveRank = () => (settings.smoothGuard ? Math.min(baseRank(), guard.capRank) : baseRank());
    const ceilingKey = () => KEY_BY_RANK[effectiveRank()];

    // Seconds of buffer ahead of the playhead, and the end of that range (download progress).
    const bufferInfo = (v) => {
        if (!v) return { ahead: 0, end: 0 };
        const t = v.currentTime;
        const b = v.buffered;
        for (let i = 0; i < b.length; i++) {
            if (t >= b.start(i) - 0.3 && t <= b.end(i) + 0.3) return { ahead: Math.max(0, b.end(i) - t), end: b.end(i) };
        }
        return { ahead: 0, end: b.length ? b.end(b.length - 1) : 0 };
    };

    const markIdle = () => {
        rec.lastTime = -1;
        rec.lastEnd = -1;
        rec.frozenSince = 0;
        rec.bufferSince = 0;
        rec.healthySince = 0;
        rec.lastMoveAt = Date.now();
    };

    const resetQualityBudget = () => {
        vid.sets = 0;
        vid.windowStart = Date.now();
        vid.lastSet = 0;
    };

    const resetForNewVideo = (graceMs) => {
        const now = Date.now();
        rec.count = 0;
        rec.nudges = 0;
        rec.streak = 0;
        rec.lastAt = 0;
        rec.stopped = false;
        rec.nudged = false;
        rec.stallLimit = STALL_MS;
        rec.graceUntil = now + graceMs;
        markIdle();
        relief.active = false;
        relief.slowCount = 0;
        relief.latched = false;
        guard.bad = 0;
        guard.lastTotal = -1;
        info.at = 0; info.raw = ''; info.codec = ''; info.res = ''; info.bw = NaN; info.curH = 0; info.optH = 0; info.fps = 0;
        resetQualityBudget();
        vid.startedAt = now;
        vid.playTries = 0;
        vid.lastPlayTry = 0;
        vid.target = null;
        vid.userQuality = null;
        vid.arrivingSince = 0;
        vid.fix = 0;
        vid.autonavTries = 0;
    };

    // ==========================================
    // QUALITY ENGINE
    // ==========================================
    // Best quality that exists for this video, not above the effective ceiling
    // (or the quality you picked in YouTube's own menu, which wins for this video).
    const pickTargetQuality = (p) => {
        if (settings.quality === 'auto' || vid.userQuality === 'auto') return null;
        const ceiling = vid.userQuality ? RANK[vid.userQuality] : effectiveRank();

        let list = [];
        const data = safe(() => p.getAvailableQualityData(), null);
        if (data && data.length) {
            list = data.filter(q => q && q.isPlayable !== false).map(q => q.quality);
        }
        if (!list.length) list = safe(() => p.getAvailableQualityLevels(), []) || [];

        let best = null;
        let bestRank = -1;
        for (const q of list) {
            const r = RANK[q];
            if (r === undefined || r > ceiling) continue;
            if (r > bestRank) { best = q; bestRank = r; }
        }
        return best;
    };

    const ensureQuality = (p, force) => {
        if (settings.quality === 'auto' || vid.userQuality === 'auto' || relief.active || !playerReady(p) || adShowing(p)) return;

        // Setting quality before playback has begun can reset the player to the play button.
        const state = p.getPlayerState();
        if (state !== 1 && state !== 2 && state !== 3) return;

        const target = pickTargetQuality(p);
        vid.target = target;
        if (!target) return;

        const current = safe(() => p.getPlaybackQuality(), '');
        if (current === target && !force) return;   // force: re-pin even if the tier already matches (after relief / a manual choice)

        const now = Date.now();
        if (now - vid.windowStart > QUALITY_WINDOW_MS) resetQualityBudget();
        if (vid.sets >= QUALITY_MAX_SETS || now - vid.lastSet < QUALITY_RETRY_MS) return;

        log(`Quality ${current} -> ${target}`);
        safe(() => p.setPlaybackQualityRange(target, target));
        safe(() => p.setPlaybackQuality(target));
        vid.sets++;
        vid.lastSet = now;
    };

    // The player reports the REQUESTED tier, so a switch that never arrives looks "done". Compare against
    // what is really on screen vs what YouTube says it wants, and retry (then flush) if it is stuck.
    const watchSwitch = (p, state, now) => {
        const arriving = state === 1 && !relief.active && !vid.userQuality && info.optH && info.curH && info.curH < info.optH;
        if (!arriving) { vid.arrivingSince = 0; vid.fix = 0; return; }
        if (!vid.arrivingSince) { vid.arrivingSince = now; return; }
        const waited = now - vid.arrivingSince;
        if (waited > SWITCH_NUDGE_MS && vid.fix < 2) {
            vid.fix = 2;
            say('Quality switch is stuck. Nudging playback to flush the old buffer.');
            safe(() => p.seekTo(p.getCurrentTime(), true));
        } else if (waited > SWITCH_REPIN_MS && vid.fix < 1) {
            vid.fix = 1;
            log('Quality switch has not arrived. Re-requesting.');
            resetQualityBudget();
            ensureQuality(p, true);
        }
    };

    // If you pick a quality in YouTube's own gear menu, respect it for this video.
    document.addEventListener('click', (e) => {
        const item = e.target && e.target.closest ? e.target.closest('.ytp-quality-menu .ytp-menuitem') : null;
        if (!item || settings.quality === 'auto') return;
        const txt = (item.textContent || '').trim();
        const m = /^(\d{3,4})p/.exec(txt);
        const key = m ? PX[m[1]] : (/^auto/i.test(txt) ? 'auto' : null);
        devLog('quality', 'menu click', { text: txt.slice(0, 30), parsed: key });
        if (!key) return;
        vid.userQuality = key;
        relief.active = false;
        relief.latched = false;
        resetQualityBudget();
        setTimeout(() => {
            const p = getPlayer();
            if (playerReady(p) && key !== 'auto') ensureQuality(p, true);
            if (key !== 'auto' && RANK[key] < baseRank()) notify(`${QL[key]} for this video`, { label: 'Use as ceiling', run: () => setSetting('quality', key) });
            renderAll();
        }, 400);
    }, true);

    // ---- Adaptive relief: let YouTube's own ABR run (up to your ceiling) while the buffer refills ----
    const applyReliefRange = (p) => safe(() => p.setPlaybackQualityRange('tiny', ceilingKey()));

    const startRelief = (p, reason) => {
        if (!settings.adaptive || settings.quality === 'auto' || vid.userQuality || !playerReady(p)) return;
        if (reason === 'slow') {
            relief.slowCount++;
            // Twice on one video means the connection cannot hold the ceiling: stop fighting it.
            if (relief.slowCount >= 2) relief.latched = true;
        }
        relief.active = true;
        relief.since = Date.now();
        relief.reason = reason;
        applyReliefRange(p);
        log('Relief on:', reason);
    };

    const endRelief = (p) => {
        if (!relief.active) return;
        relief.active = false;
        relief.latched = false;
        resetQualityBudget();
        ensureQuality(p, true);
        log('Relief off');
    };

    const manageRelief = (p, state, now) => {
        if (!relief.active || relief.latched) return;
        const held = now - relief.since;
        const ahead = bufferInfo(getVideo(p)).ahead;
        if (held > RELIEF_MAX_MS || (held > RELIEF_MIN_MS && state === 1 && ahead >= RELIEF_EXIT_BUFFER_S)) endRelief(p);
    };

    // ---- Smooth guard: if the device cannot decode the tier, step the ceiling down ----
    const watchDrops = (p) => {
        if (Date.now() < guard.holdUntil) return;   // let a freshly chosen tier settle before judging it
        const v = getVideo(p);
        const q = v && safe(() => v.getVideoPlaybackQuality(), null);
        if (!q) return;
        const total = q.totalVideoFrames;
        const dropped = q.droppedVideoFrames;
        if (guard.lastTotal < 0 || total < guard.lastTotal) { guard.lastTotal = total; guard.lastDropped = dropped; return; }
        const dT = total - guard.lastTotal;
        if (dT < DROP_MIN_FRAMES) return;
        const dD = dropped - guard.lastDropped;
        guard.lastTotal = total;
        guard.lastDropped = dropped;

        guard.bad = (dD / dT > DROP_RATIO) ? guard.bad + 1 : 0;
        if (guard.bad < 2) return;
        guard.bad = 0;

        const cur = RANK[safe(() => p.getPlaybackQuality(), '')];
        if (cur === undefined || cur <= GUARD_FLOOR_RANK) return;
        guard.capRank = cur - 1;
        saveCap();
        guard.lastTotal = -1;                        // fresh baseline: the switch itself can drop frames
        guard.holdUntil = Date.now() + 12000;
        resetQualityBudget();
        ensureQuality(p, true);
        say(`Device is dropping frames. Capping quality at ${QL[KEY_BY_RANK[guard.capRank]]}.`);
        notify(`Lowered to ${QL[KEY_BY_RANK[guard.capRank]]}: this device was dropping frames`, {
            label: 'Undo', run: () => { guard.capRank = 99; saveCap(); applyQualityChoice(); renderAll(); }
        });
    };

    // ==========================================
    // RECOVERY
    // ==========================================
    const resumePlayback = (p, tries) => {
        if (tries > 10) return;
        setTimeout(() => {
            const st = safe(() => p.getPlayerState(), 1);
            if (st === 1 || st === 3) return;      // playing or buffering: good
            safe(() => p.playVideo());
            resumePlayback(p, tries + 1);
        }, 500);
    };

    const softReload = (p, live) => {
        const data = safe(() => p.getVideoData(), null);
        const id = data && data.video_id;
        if (!id || typeof p.loadVideoById !== 'function') { location.reload(); return; }

        const wasPaused = safe(() => p.getPlayerState(), 1) === 2;
        const cur = Math.max(0, safe(() => p.getCurrentTime(), 0) - 1);
        safe(() => p.stopVideo());   // guarantees a fresh stream fetch

        setTimeout(() => {
            safe(() => (live ? p.loadVideoById(id) : p.loadVideoById(id, cur)));
            setTimeout(() => { if (relief.active) applyReliefRange(p); }, 700);
            if (!wasPaused) resumePlayback(p, 0);
        }, 50);
    };

    // Step 1: re-request the current position without tearing the player down.
    const nudge = (p, reason) => {
        const cur = safe(() => p.getCurrentTime(), 0);
        rec.nudged = true;
        rec.nudges++;
        rec.frozenSince = 0;
        rec.lastMoveAt = Date.now();
        rec.stallLimit = STALL_AFTER_NUDGE_MS;
        say(`${reason}. Nudging playback.`);
        safe(() => p.seekTo(cur, true));
    };

    // Step 2: reload the stream, with a short ramp so the reload itself does not stall.
    const recover = (reason) => {
        if (!settings.recovery || rec.stopped || !isWatchPage()) return;
        const p = getPlayer();
        if (!playerReady(p) || adShowing(p)) return;

        const now = Date.now();
        if (now - rec.lastAt < COOLDOWN_MS) return;

        const live = !!safe(() => p.getVideoData().isLive, false);
        const limit = live ? MAX_LIVE_ATTEMPTS : MAX_ATTEMPTS;

        if (rec.streak >= limit) {
            rec.stopped = true;
            say(`Gave up after ${rec.streak} failed reloads (${reason}).`);
            notify('Playback keeps failing.', { label: 'Reload', run: reloadStream });
            return;
        }

        rec.count++;
        rec.streak++;
        rec.lastAt = now;
        rec.graceUntil = now + GRACE_MS;
        rec.nudged = false;
        rec.stallLimit = STALL_POST_RELOAD_MS;
        markIdle();
        resetQualityBudget();
        startRelief(p, 'ramp');

        say(`${reason}. Reloading stream (attempt ${rec.streak}/${limit}).`);
        softReload(p, live);
    };

    const handleStall = (p, reason, live) => {
        if (Date.now() - rec.lastAt < COOLDOWN_MS) return;
        if (!rec.nudged && !live) { nudge(p, reason); return; }
        recover(reason);
    };

    const watchPlayback = (p, state, now) => {
        if (rec.stopped || now < rec.graceUntil || !navigator.onLine) { markIdle(); return; }

        const live = !!safe(() => p.getVideoData().isLive, false);
        const cur = safe(() => p.getCurrentTime(), 0);
        const dur = safe(() => p.getDuration(), 0);
        const nearEnd = !live && dur > 0 && cur > dur - 3;
        if ((state !== 1 && state !== 3) || nearEnd) { markIdle(); return; }

        // YouTube's own error screen
        const err = p.querySelector('.ytp-error');
        if (err && shown(err)) { recover('Player error screen'); return; }

        const bi = bufferInfo(getVideo(p));
        const playheadMoved = cur !== rec.lastTime;
        const bufferMoved = Math.abs(bi.end - rec.lastEnd) > 0.05;
        rec.lastTime = cur;
        rec.lastEnd = bi.end;
        if (playheadMoved || bufferMoved) rec.lastMoveAt = now;

        if (state === 1) {
            rec.bufferSince = 0;
            if (playheadMoved) {
                rec.frozenSince = 0;
                if (!rec.healthySince) rec.healthySince = now;
                const h = now - rec.healthySince;
                if (h > HEALTHY_MS) { rec.nudged = false; rec.stallLimit = STALL_MS; }
                if (h > HEALTHY_RESET_MS) rec.streak = 0;
            } else {
                // "Playing" but the clock is not advancing
                rec.healthySince = 0;
                if (!rec.frozenSince) rec.frozenSince = now;
                else if (now - rec.frozenSince > rec.stallLimit) handleStall(p, 'Playhead frozen', live);
            }
        } else {
            // Buffering: stalled download (nothing moving) vs slow download (still moving)
            rec.frozenSince = 0;
            rec.healthySince = 0;
            if (!rec.bufferSince) rec.bufferSince = now;
            if (now - rec.lastMoveAt > rec.stallLimit) handleStall(p, 'Download stalled', live);
            else if (now - rec.bufferSince > SLOW_BUFFER_MS) { rec.bufferSince = now; startRelief(p, 'slow'); }
        }
    };

    // ---- Audio dropouts: sound that dies while the picture keeps moving. Chromium exposes how many bytes of
    //      audio have been decoded; it normally rises every fraction of a second, so a flat counter means silence. ----
    const AUDIO_FLAT_MS = 3500;
    const audio = { last: -1, lastT: -1, flatSince: 0, fixedAt: 0 };
    const watchAudio = (p, state, now) => {
        const v = getVideo(p);
        if (!settings.audioFix || !v || state !== 1 || v.muted || v.volume === 0 || typeof v.webkitAudioDecodedByteCount !== 'number') {
            audio.last = -1; audio.lastT = -1; audio.flatSince = 0;
            return;
        }
        const t = v.currentTime;
        const moving = audio.lastT >= 0 && t > audio.lastT + 0.2;
        audio.lastT = t;
        const bytes = v.webkitAudioDecodedByteCount;
        if (!moving || bytes === 0 || bytes !== audio.last) { audio.last = bytes; audio.flatSince = 0; return; }   // 0 = no audio track
        if (!audio.flatSince) { audio.flatSince = now; return; }
        if (now - audio.flatSince < AUDIO_FLAT_MS || now - audio.fixedAt < 8000) return;
        audio.fixedAt = now;
        audio.flatSince = 0;
        audioStats.dropouts++;
        say('Audio stopped while the video kept playing. Re-syncing.');
        notify('Re-synced audio that had dropped out.');
        safe(() => p.seekTo(t, true));
    };

    // Headphones / speakers / Bluetooth switched while playing: the audio output can end up stuck, so re-sync.
    safe(() => {
        const md = navigator.mediaDevices;
        if (!md || typeof md.addEventListener !== 'function') return;
        let timer = 0;
        md.addEventListener('devicechange', () => {
            clearTimeout(timer);
            timer = setTimeout(() => {   // let the OS finish switching outputs first
                const p = getPlayer();
                if (!settings.audioFix || !isWatchPage() || !playerReady(p) || adShowing(p) || p.getPlayerState() !== 1) return;
                audioStats.deviceNudges++;
                say('Audio device changed. Re-syncing playback.');
                safe(() => p.seekTo(p.getCurrentTime(), true));
            }, 1500);
        });
    });

    // Native media errors (network / source) on the main video. Media events do not bubble,
    // so capture them at the document instead of attaching to every <video>.
    document.addEventListener('error', (e) => {
        if (!isMainVideo(e.target)) return;
        const code = e.target.error && e.target.error.code;
        // 2: MEDIA_ERR_NETWORK, 4: MEDIA_ERR_SRC_NOT_SUPPORTED (403s often land here)
        if (code === 2 || code === 4) recover(`Media error (code ${code})`);
    }, true);

    // Apply the quality lock the instant playback (re)starts instead of waiting for the next tick.
    ['loadedmetadata', 'playing'].forEach((ev) => {
        document.addEventListener(ev, (e) => {
            if (!isMainVideo(e.target)) return;
            const p = getPlayer();
            if (playerReady(p)) ensureQuality(p);
        }, true);
    });

    // Connection came back (Wi-Fi <-> mobile data, signal regained) while the spinner is up: re-request now.
    window.addEventListener('online', () => {
        const p = getPlayer();
        if (!settings.recovery || !isWatchPage() || !playerReady(p) || adShowing(p)) return;
        if (p.getPlayerState() === 3) nudge(p, 'Connection restored');
    });

    // ==========================================
    // AUTOPLAY (only right after a video loads, never fights a manual pause)
    // ==========================================
    const maybeAutoplay = (p, state) => {
        if (!settings.autoPlay) return;
        // -1 = unstarted, 5 = cued. State 2 (paused) is left alone.
        if (state !== -1 && state !== 5) return;
        const now = Date.now();
        if (now - vid.startedAt > 10000 || vid.playTries >= 3 || now - vid.lastPlayTry < 1000) return;
        vid.playTries++;
        vid.lastPlayTry = now;
        log(`Autoplay attempt ${vid.playTries}`);
        safe(() => p.playVideo());
    };

    // ==========================================
    // EXTRAS (page tweaks that are not about the stream itself)
    // ==========================================
    const STYLE_ID = 'yb-style';
    const buildCSS = () => {
        let css = '';
        if (settings.hideShorts) {
            css += `
                ytd-rich-section-renderer:has(ytd-rich-shelf-renderer[is-shorts]),
                ytd-reel-shelf-renderer,
                ytd-guide-entry-renderer:has(a[title="Shorts"]),
                ytd-mini-guide-entry-renderer[aria-label="Shorts"],
                ytd-rich-item-renderer:has(a[href^="/shorts/"]),
                ytd-video-renderer:has(a[href^="/shorts/"]),
                ytd-grid-video-renderer:has(a[href^="/shorts/"]) { display: none !important; }`;
        }
        if (settings.hideOverlays) {
            css += `
                .ytp-ce-element, .ytp-endscreen-content, .ytp-pause-overlay, .ytp-pause-overlay-container,
                .ytp-cards-teaser, .ytp-videowall-still { display: none !important; }`;
        }
        if (settings.adBlock) {
            // Leftovers the data pruning did not catch. (The player's own ad UI is NOT hidden: the fast-forward
            // fallback needs to be able to press its skip button.)
            css += `
                #masthead-ad, #player-ads, ytd-ad-slot-renderer, ytd-in-feed-ad-layout-renderer, ytd-promoted-sparkles-web-renderer,
                ytd-promoted-video-renderer, ytd-display-ad-renderer, ytd-compact-promoted-video-renderer, ytd-banner-promo-renderer,
                ytd-statement-banner-renderer, ytd-action-companion-ad-renderer, ytd-companion-slot-renderer,
                ytd-player-legacy-desktop-watch-ads-renderer, ytd-brand-video-shelf-renderer, ytd-brand-video-singleton-renderer,
                ytd-mealbar-promo-renderer, yt-mealbar-promo-renderer, .ytp-ad-overlay-container, .ytp-ad-text-overlay,
                ytd-rich-item-renderer:has(ytd-ad-slot-renderer), ytd-rich-section-renderer:has(ytd-ad-slot-renderer),
                ytd-reel-video-renderer:has(ytd-ad-slot-renderer),
                ytd-engagement-panel-section-list-renderer[target-id="engagement-panel-ads"] { display: none !important; }`;
        }
        return css;
    };

    function applyStyle() {
        const css = buildCSS();
        let s = document.getElementById(STYLE_ID);
        if (!css) { if (s) s.remove(); return; }
        if (!s) {
            const parent = document.head || document.documentElement;
            if (!parent) return;
            s = document.createElement('style');
            s.id = STYLE_ID;
            parent.appendChild(s);
        }
        if (s.textContent !== css) s.textContent = css;
    }
    safe(applyStyle);

    // "Video paused. Continue watching?" appears after a long idle stretch and stops playback.
    const dismissIdlePrompt = () => {
        const root = document.querySelector('ytd-popup-container');
        if (!root) return;
        // YouTube keeps an empty, hidden dialog in this container, so look at every one, not just the first.
        for (const dlg of root.querySelectorAll('yt-confirm-dialog-renderer, ytd-confirm-dialog-renderer')) {
            if (!shown(dlg)) continue;
            if (!/still watching|continue watching|video paused/i.test(dlg.textContent || '')) continue;
            const btn = [...dlg.querySelectorAll('button, [role="button"]')].find(b => /^(yes|continue|ok|resume)/i.test((b.textContent || b.getAttribute('aria-label') || '').trim()))
                || dlg.querySelector('#confirm-button button, #confirm-button');
            if (!btn) continue;
            btn.click();
            log('Dismissed the idle prompt');
            const p = getPlayer();
            if (playerReady(p) && p.getPlayerState() === 2) safe(() => p.playVideo());
            return;
        }
    };

    // Up Next autoplay: switch it off for you (only in the first seconds of a video, so you can still turn it back on).
    const maybeDisableAutonav = () => {
        if (!settings.autonavOff || vid.autonavTries >= 4) return;
        const b = document.querySelector('.ytp-autonav-toggle-button[aria-checked="true"]');
        if (!b) return;
        vid.autonavTries++;
        b.click();
    };

    // ---- Ad blocking, part 2: what still gets through ----
    const SKIP_SEL = '.ytp-skip-ad-button, .ytp-ad-skip-button-modern, .ytp-ad-skip-button, .ytp-ad-skip-button-container button, .ytp-ad-overlay-close-button';
    const adRun = { active: false, timer: 0, muted: false, rate: 1 };

    const adStop = () => {
        if (!adRun.active) return;
        clearInterval(adRun.timer);
        adRun.active = false;
        const v = getVideo(getPlayer());
        if (v) { try { v.muted = adRun.muted; v.playbackRate = adRun.rate; } catch (e) { noteError(e); } }
        devLog('ads', 'ad ended');
    };

    // A video ad is playing: press skip if there is a button, otherwise mute it and jump to its end.
    const adStep = () => {
        const p = getPlayer();
        const v = getVideo(p);
        if (!p || !v || !adShowing(p) || !settings.adBlock || !settings.adSkip) { adStop(); return; }
        const btn = document.querySelector(SKIP_SEL);
        if (btn) safe(() => btn.click());
        try {
            v.muted = true;
            v.playbackRate = 16;
            if (isFinite(v.duration) && v.duration > 0 && v.currentTime < v.duration - 0.25) v.currentTime = v.duration - 0.1;
        } catch (e) { noteError(e); }
    };

    const adStart = () => {
        if (adRun.active || !settings.adBlock || !settings.adSkip) return;
        const v = getVideo(getPlayer());
        if (!v) return;
        adRun.active = true;
        adRun.muted = v.muted;
        adRun.rate = v.playbackRate > 4 ? 1 : v.playbackRate;
        adStats.skipped++;
        devLog('ads', 'video ad detected, fast-forwarding', { duration: v.duration, skippable: !!document.querySelector(SKIP_SEL) });
        adRun.timer = setInterval(adStep, 150);
        adStep();
    };

    // React to the player entering ad mode immediately instead of waiting for the next tick.
    let watchedPlayer = null;
    const watchPlayerAds = (p) => {
        if (!p || watchedPlayer === p) return;
        watchedPlayer = p;
        try {
            new MutationObserver(() => { if (adShowing(p)) adStart(); }).observe(p, { attributes: true, attributeFilter: ['class'] });
        } catch (e) { noteError(e); }
        if (adShowing(p)) adStart();
    };

    // YouTube's "ad blockers are not allowed" wall, if it ever appears.
    const removeEnforcement = () => {
        const m = document.querySelector('ytd-enforcement-message-view-model, #enforcement-message');
        if (!m) return;
        const dlg = m.closest('tp-yt-paper-dialog') || m;
        devLog('ads', 'ad-blocker warning found', { tag: dlg.tagName.toLowerCase(), text: String(dlg.textContent || '').trim().slice(0, 100) });
        dlg.remove();
        document.querySelectorAll('tp-yt-iron-overlay-backdrop.opened').forEach((b) => b.remove());
        adStats.enforcement++;
        const p = getPlayer();
        if (playerReady(p) && p.getPlayerState() !== 1) safe(() => p.playVideo());
    };

    // ---- Shorts: optionally open them in the normal player (full controls, up to the video's real quality) ----
    const shortsId = () => { const m = /^\/shorts\/([\w-]+)/.exec(location.pathname); return m ? m[1] : null; };
    const maybeRedirectShorts = () => {
        if (!settings.shortsRedirect) return false;
        const id = shortsId();
        if (!id) return false;
        devLog('shorts', 'redirecting to the normal player', id);
        location.replace('/watch?v=' + id);
        return true;
    };
    safe(maybeRedirectShorts);   // a Short opened directly
    document.addEventListener('click', (e) => {   // a Short clicked inside YouTube
        if (!settings.shortsRedirect || e.defaultPrevented || e.button !== 0 || e.ctrlKey || e.metaKey || e.shiftKey) return;
        const a = e.target && e.target.closest ? e.target.closest('a[href^="/shorts/"]') : null;
        const m = a && /^\/shorts\/([\w-]+)/.exec(a.getAttribute('href') || '');
        if (!m) return;
        e.preventDefault();
        e.stopImmediatePropagation();
        location.assign('/watch?v=' + m[1]);
    }, true);

    // Full-resolution PNG of the current frame (native video resolution, not the window size).
    function saveFrame() {
        const p = getPlayer();
        const v = getVideo(p);
        if (!v || !v.videoWidth || !v.videoHeight) { toast('Nothing to capture yet'); return; }
        try {
            const c = document.createElement('canvas');
            c.width = v.videoWidth;
            c.height = v.videoHeight;
            c.getContext('2d').drawImage(v, 0, 0, c.width, c.height);
            const t = Math.floor(safe(() => p.getCurrentTime(), 0));
            const title = String(safe(() => p.getVideoData().title, 'YouTube')).replace(/[\\/:*?"<>|]+/g, '').trim().slice(0, 80) || 'YouTube';
            const stamp = `${Math.floor(t / 3600) ? Math.floor(t / 3600) + 'h' : ''}${Math.floor((t % 3600) / 60)}m${String(t % 60).padStart(2, '0')}s`;
            c.toBlob((blob) => {
                if (!blob) { toast('Could not capture this video'); return; }
                const a = document.createElement('a');
                a.href = URL.createObjectURL(blob);
                a.download = `${title} - ${stamp}.png`;
                document.body.appendChild(a);
                a.click();
                a.remove();
                setTimeout(() => URL.revokeObjectURL(a.href), 10000);
                toast(`Saved ${c.width}x${c.height} frame`);
            }, 'image/png');
        } catch (e) {
            toast('Could not capture this video');
        }
    }

    // Keyboard: Alt+B opens the panel, Alt+S saves the frame.
    window.addEventListener('keydown', (e) => {
        if (!e.altKey || e.ctrlKey || e.metaKey || e.shiftKey || !isWatchPage()) return;
        const t = e.target;
        if (t && (t.isContentEditable || /^(input|textarea|select)$/i.test(t.tagName || ''))) return;
        if (e.code === 'KeyB') { e.preventDefault(); togglePanel(); }
        else if (e.code === 'KeyS') { e.preventDefault(); saveFrame(); }
    }, true);

    // ==========================================
    // STATS (cheap; stats-for-nerds is only read every few seconds)
    // ==========================================
    // Fallback when the player has no stats-for-nerds: the codec of the video buffer the browser was given.
    const codecFromBuffer = () => {
        const m = /codecs="?([^",;]+)/i.exec(lastVideoType);
        if (!m) return;
        info.raw = m[1].trim();
        info.codec = codecShort(info.raw);
    };

    const refreshInfo = (p, force) => {
        const now = Date.now();
        if (!force && now - info.at < 4000) return;
        info.at = now;
        const s = safe(() => p.getStatsForNerds(), null);
        if (!s) { codecFromBuffer(); return; }
        info.raw = s.codecs ? String(s.codecs).split(' / ')[0].replace(/\s*\(\d+\)$/, '') : '';
        info.codec = codecShort(info.raw);
        if (!info.codec) codecFromBuffer();
        info.res = s.resolution ? String(s.resolution).split(' / ')[0] : '';
        info.bw = parseFloat(s.bandwidth_kbps);
        // "current / optimal": what is on screen now vs what YouTube wants to be showing
        const parse = (t) => { const m = /(\d+)x(\d+)(?:@(\d+))?/.exec(t || ''); return m ? { w: +m[1], h: +m[2], fps: +(m[3] || 0) } : null; };
        const parts = String(s.resolution || '').split(' / ').map(parse);
        info.curH = parts[0] ? Math.min(parts[0].w, parts[0].h) : 0;
        info.optH = parts[1] ? Math.min(parts[1].w, parts[1].h) : 0;
        info.fps = parts[0] ? parts[0].fps : 0;
    };

    // Height of the frames actually on screen (portrait video: the short side), never the requested tier.
    const renderedHeight = (p) => {
        const v = getVideo(p);
        if (v && v.videoWidth && v.videoHeight) return v.videoWidth < v.videoHeight ? v.videoWidth : v.videoHeight;
        return info.curH;
    };

    const computeStatus = (p, ad) => {
        if (ad) return { level: 'idle', text: 'Ad playing' };
        if (rec.stopped) return { level: 'error', text: 'Recovery paused after repeated failures. Use Reload to try again.' };
        if (rec.count > 0 && Date.now() - rec.lastAt < 12000) return { level: 'warn', text: 'Recovering the stream' };
        if (lastState === 3 && rec.bufferSince && Date.now() - rec.bufferSince > 2500) {
            return { level: 'warn', text: rec.nudged ? 'Buffering: nudged the stream, waiting for data' : 'Buffering: waiting for data' };
        }
        if (relief.active) {
            if (relief.reason === 'ramp') return { level: 'warn', text: 'Rebuilding the buffer before locking quality' };
            return { level: 'warn', text: relief.latched ? 'Adapting: your connection cannot hold the ceiling' : 'Adapting to your connection' };
        }
        if (settings.quality === 'auto') return { level: 'idle', text: 'YouTube controls quality' };
        if (vid.userQuality) {
            return { level: 'idle', text: vid.userQuality === 'auto' ? 'You chose Auto for this video' : `You chose ${QL[vid.userQuality]} for this video` };
        }
        if (settings.smoothGuard && guard.capRank < baseRank()) {
            return { level: 'warn', text: `Capped at ${QL[KEY_BY_RANK[guard.capRank]]}: your device was dropping frames` };
        }
        const cur = safe(() => p.getPlaybackQuality(), '');
        const shown = renderedHeight(p);
        const arriving = info.optH && shown && shown < info.optH;
        if ((vid.target && cur && cur !== vid.target) || arriving) {
            return { level: 'warn', text: `Switching to ${arriving ? info.optH + 'p' : QL[vid.target]}` };
        }
        return { level: 'ok', text: `Locked at ${shown ? shown + 'p' : (QL[cur] || cur || 'best available')}` };
    };

    // ==========================================
    // SETTINGS ACTIONS
    // ==========================================
    const applyQualityChoice = () => {
        const p = getPlayer();
        if (!playerReady(p)) return;
        resetQualityBudget();
        relief.active = false;
        relief.latched = false;
        vid.userQuality = null;
        if (settings.quality === 'auto') {
            // Hand control back to YouTube's adaptive streaming.
            safe(() => p.setPlaybackQualityRange('tiny', 'highres'));
            safe(() => p.setPlaybackQuality('auto'));
        } else {
            ensureQuality(p, true);
        }
    };

    const reloadStream = () => {
        const p = getPlayer();
        if (!playerReady(p) || adShowing(p)) return;
        const live = !!safe(() => p.getVideoData().isLive, false);
        rec.stopped = false;
        rec.streak = 0;
        rec.nudged = false;
        rec.graceUntil = Date.now() + GRACE_MS;
        rec.stallLimit = STALL_POST_RELOAD_MS;
        markIdle();
        resetQualityBudget();
        startRelief(p, 'ramp');
        softReload(p, live);
    };

    const setSetting = (key, value) => {
        if (!(key in DEFAULTS)) return;
        if (settings[key] === value) {
            // Re-picking the current quality is how you clear an automatic cap from the smooth guard.
            if (key === 'quality' && guard.capRank < 99) {
                guard.capRank = 99;
                saveCap();
                applyQualityChoice();
                renderAll();
            }
            return;
        }
        settings[key] = value;
        Object.assign(settings, normalize(settings));
        saveSettings();
        const p = getPlayer();
        if (key === 'quality' || (key === 'smoothGuard' && !value)) {
            guard.capRank = 99;      // a manual choice clears any automatic cap
            saveCap();
            applyQualityChoice();
        } else if (key === 'codec') reloadStream();                 // codec is chosen when the stream loads
        else if (key === 'recovery') { markIdle(); if (value) { rec.stopped = false; rec.streak = 0; } }
        else if (key === 'adaptive' && !value && playerReady(p)) endRelief(p);
        else if (key === 'hideShorts' || key === 'hideOverlays' || key === 'adBlock') { applyStyle(); if (key === 'adBlock' && !value) adStop(); }
        else if (key === 'devLog') { devOn = value; devLog('dev', 'developer log ' + (value ? 'on' : 'off')); }
        else if (key === 'shortsRedirect') maybeRedirectShorts();
        else if (key === 'audioOriginal') notify('Takes effect from the next video you open.');
        else if (key === 'autonavOff') vid.autonavTries = 0;
        renderAll();
    };

    // Keep several open tabs in agreement.
    window.addEventListener('storage', (e) => {
        if (e.key !== STORE_KEY || !e.newValue) return;
        try {
            const prev = settings.quality;
            Object.assign(settings, normalize(JSON.parse(e.newValue)));
            devOn = settings.devLog;
            if (settings.quality !== prev) applyQualityChoice();
            applyStyle();
            renderAll();
        } catch (err) { /* ignore */ }
    });

    // ==========================================
    // UI: status chip + panel (Shadow DOM, so YouTube's CSS cannot touch it)
    // ==========================================
    const SVGNS = 'http://www.w3.org/2000/svg';
    const icon = (d, size, cls) => {
        const s = document.createElementNS(SVGNS, 'svg');
        s.setAttribute('viewBox', '0 0 24 24');
        s.setAttribute('width', size);
        s.setAttribute('height', size);
        s.setAttribute('fill', 'none');
        s.setAttribute('stroke', 'currentColor');
        s.setAttribute('stroke-width', '2.2');
        s.setAttribute('stroke-linecap', 'round');
        s.setAttribute('stroke-linejoin', 'round');
        s.setAttribute('aria-hidden', 'true');
        if (cls) s.setAttribute('class', cls);
        const path = document.createElementNS(SVGNS, 'path');
        path.setAttribute('d', d);
        s.appendChild(path);
        return s;
    };

    const el = (tag, attrs, ...kids) => {
        const n = document.createElement(tag);
        const a = attrs || {};
        for (const k of Object.keys(a)) {
            if (k === 'text') n.textContent = a[k];
            else if (k === 'on') { for (const ev of Object.keys(a.on)) n.addEventListener(ev, a.on[ev]); }
            else n.setAttribute(k, a[k]);
        }
        for (const c of kids.flat()) {
            if (c == null) continue;
            n.appendChild(typeof c === 'string' ? document.createTextNode(c) : c);
        }
        return n;
    };

    const setText = (n, t) => { if (n && n.textContent !== t) n.textContent = t; };

    // Colour carries one meaning only: status. Everything else follows the page's own text colour.
    const BASE_CSS = `
        :host { all: initial; --fg: 15,15,15; --bg: 255,255,255; font-family: Roboto, Arial, sans-serif; }
        *, *::before, *::after { box-sizing: border-box; }
        [hidden] { display: none !important; }
        button { font-family: inherit; color: inherit; -webkit-tap-highlight-color: transparent; }
        .dot { width: 8px; height: 8px; border-radius: 50%; flex: none; background: rgba(var(--fg), .35); transition: background .2s; }
        .dot.ok { background: #2ba640; box-shadow: 0 0 0 3px rgba(43,166,64,.2); }
        .dot.warn { background: #f0a020; animation: pulse 1.1s ease-in-out infinite; }
        .dot.error { background: #e5453b; box-shadow: 0 0 0 3px rgba(229,69,59,.2); }
        @keyframes pulse { 50% { opacity: .35; } }
        @media (prefers-reduced-motion: reduce) { .dot.warn { animation: none; } * { transition: none !important; } }
    `;

    const CHIP_CSS = BASE_CSS + `
        :host { display: inline-flex; align-items: center; vertical-align: middle; }
        .chip { display: inline-flex; align-items: center; gap: 8px; height: 28px; padding: 0 9px 0 11px; border: 0; border-radius: 14px;
                background: rgba(var(--fg), .07); font: 500 12.5px/1 Roboto, Arial, sans-serif; color: rgb(var(--fg)); cursor: pointer;
                font-variant-numeric: tabular-nums; transition: background .15s; }
        .chip:hover { background: rgba(var(--fg), .13); }
        :host([data-overlay]) .chip { background: rgba(0,0,0,.55); color: #fff; -webkit-backdrop-filter: blur(8px); backdrop-filter: blur(8px); }
        :host([data-overlay]) .chip:hover { background: rgba(0,0,0,.72); }
        :host([data-overlay]) .div { background: rgba(255,255,255,.35); }
        .chip:focus-visible { outline: 2px solid #3ea6ff; outline-offset: 2px; }
        :host([data-float]) .chip { background: rgb(var(--bg)); border: 1px solid rgba(var(--fg), .16); box-shadow: 0 2px 12px rgba(0,0,0,.3); }
        .div { width: 1px; height: 12px; background: rgba(var(--fg), .25); }
        .badge { font-size: 11.5px; padding: 2px 6px; border-radius: 8px; background: rgba(240,160,32,.2); }
        .chev { opacity: .6; transition: transform .15s; }
        .chip[aria-expanded="true"] .chev { transform: rotate(180deg); }
        @media (pointer: coarse) { .chip { height: 36px; padding: 0 12px 0 13px; font-size: 13.5px; border-radius: 18px; } }
    `;

    const TOAST_CSS = BASE_CSS + `
        .toast { position: fixed; left: 50%; bottom: calc(28px + env(safe-area-inset-bottom, 0px)); transform: translate(-50%, 8px); z-index: 2147483001;
                 padding: 10px 16px; border-radius: 20px; background: rgb(var(--fg)); color: rgb(var(--bg)); font: 500 13px/1.2 Roboto, Arial, sans-serif;
                 box-shadow: 0 6px 24px rgba(0,0,0,.35); opacity: 0; pointer-events: none; transition: opacity .15s, transform .15s; }
        .toast.show { opacity: 1; transform: translate(-50%, 0); }
        .toast { display: flex; align-items: center; max-width: calc(100vw - 32px); }
        .toast.interactive { pointer-events: auto; }
        .act { margin-left: 14px; padding: 5px 11px; border: 0; border-radius: 13px; background: rgba(var(--bg), .22); color: inherit;
               font: 600 12.5px Roboto, Arial, sans-serif; cursor: pointer; white-space: nowrap; }
        .act:hover { background: rgba(var(--bg), .36); }
    `;

    const PANEL_CSS = BASE_CSS + `
        .panel { position: fixed; z-index: 2147483000; top: 0; left: 0; width: 336px; max-width: calc(100vw - 16px); max-height: calc(100vh - 16px); overflow-y: auto;
                 -webkit-overflow-scrolling: touch; overscroll-behavior: contain; padding: 16px 16px 14px;
                 border-radius: 18px; background: rgb(var(--bg)); color: rgb(var(--fg)); border: 1px solid rgba(var(--fg), .12);
                 box-shadow: 0 16px 48px rgba(0,0,0,.3); font: 400 13px/1.4 Roboto, Arial, sans-serif; font-variant-numeric: tabular-nums;
                 opacity: 0; transform: translateY(-6px) scale(.985); transform-origin: top left; visibility: hidden;
                 transition: opacity .14s, transform .14s, visibility 0s .14s; }
        .panel.open { opacity: 1; transform: none; visibility: visible; transition-delay: 0s; }
        .panel.sheet { left: 8px; right: 8px; top: auto; bottom: calc(8px + env(safe-area-inset-bottom, 0px)); width: auto; max-width: none;
                       transform: translateY(12px); transform-origin: bottom center; }
        .panel.sheet.open { transform: none; }
        .head { display: flex; align-items: center; justify-content: space-between; }
        .title { font-size: 15px; font-weight: 600; }
        .ver { margin-left: 8px; font-size: 11.5px; font-weight: 400; opacity: .5; }
        .x { display: grid; place-items: center; width: 28px; height: 28px; margin-right: -6px; border: 0; border-radius: 50%; background: transparent; cursor: pointer; }
        .x:hover { background: rgba(var(--fg), .1); }
        .status { display: flex; align-items: flex-start; gap: 9px; margin: 10px 0 4px; font-size: 13px; }
        .status .dot { margin-top: 5px; }
        .tabs { display: flex; gap: 4px; margin: 12px 0 2px; padding: 3px; border-radius: 12px; background: rgba(var(--fg), .07); }
        .tab { flex: 1; min-width: 0; height: 28px; padding: 0 2px; border: 0; border-radius: 9px; background: transparent; font: 500 12px Roboto, Arial, sans-serif; cursor: pointer; opacity: .7;
               overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
        .tab[aria-selected="true"] { background: rgb(var(--bg)); box-shadow: 0 1px 3px rgba(0,0,0,.22); opacity: 1; }
        .log { margin: 8px 0 0; padding: 8px 10px; border-radius: 10px; background: rgba(var(--fg), .07); font: 11px/1.45 ui-monospace, Consolas, Menlo, monospace;
               white-space: pre-wrap; word-break: break-word; max-height: 170px; overflow: auto; }
        .btns { display: flex; gap: 8px; flex-wrap: wrap; margin-top: 8px; }
        .sec { margin: 14px 0 6px; font-size: 12.5px; font-weight: 600; }
        .seg { display: flex; flex-wrap: wrap; gap: 6px; }
        .opt { height: 28px; padding: 0 12px; border-radius: 14px; border: 1px solid rgba(var(--fg), .16); background: transparent;
               font: 500 12.5px Roboto, Arial, sans-serif; cursor: pointer; transition: background .12s; }
        .opt:hover { background: rgba(var(--fg), .08); }
        .opt[aria-checked="true"] { background: rgb(var(--fg)); color: rgb(var(--bg)); border-color: transparent; }
        .opt.na:not([aria-checked="true"]) { opacity: .4; }
        .hint { margin-top: 7px; font-size: 12px; line-height: 1.4; opacity: .62; }
        .rows { margin-top: 4px; }
        .row { display: flex; align-items: center; justify-content: space-between; gap: 14px; padding: 5px 0; }
        .row .hint { display: none; margin-top: 1px; }
        .row-title { font-size: 13px; font-weight: 500; }
        .sw { position: relative; width: 36px; height: 20px; flex: none; border: 0; border-radius: 10px; background: rgba(var(--fg), .25); cursor: pointer; transition: background .15s; }
        .sw::after { content: ""; position: absolute; top: 2px; left: 2px; width: 16px; height: 16px; border-radius: 50%; background: rgb(var(--bg)); transition: transform .15s; }
        .sw[aria-checked="true"] { background: rgb(var(--fg)); }
        .sw[aria-checked="true"]::after { transform: translateX(16px); }
        .stats { display: grid; grid-template-columns: 1fr 1fr; gap: 9px 16px; margin: 0; padding: 0; }
        .stat dt { font-size: 11.5px; opacity: .62; }
        .stat dd { margin: 1px 0 0; font-weight: 500; }
        .tools { display: flex; align-items: center; gap: 4px; }
        .btn { display: inline-flex; align-items: center; gap: 6px; height: 30px; padding: 0 14px; border: 0; border-radius: 15px;
               background: rgba(var(--fg), .1); font: 500 12.5px Roboto, Arial, sans-serif; cursor: pointer; }
        .btn:hover { background: rgba(var(--fg), .18); }
        kbd { padding: 1px 6px; border-radius: 5px; border: 1px solid rgba(var(--fg), .25); font: 500 11.5px Roboto, Arial, sans-serif; }
        .opt:focus-visible, .btn:focus-visible, .sw:focus-visible, .x:focus-visible, .tab:focus-visible { outline: 2px solid #3ea6ff; outline-offset: 2px; }
        /* Touch screens: bigger targets, and descriptions inline because there is no hover tooltip */
        @media (pointer: coarse) {
            .opt { height: 36px; padding: 0 15px; border-radius: 18px; font-size: 13.5px; }
            .btn { height: 36px; border-radius: 18px; font-size: 13.5px; }
            .tab { height: 36px; font-size: 13.5px; }
            .x { width: 40px; height: 40px; }
            .sw { width: 46px; height: 28px; border-radius: 14px; }
            .sw::after { top: 3px; left: 3px; width: 22px; height: 22px; }
            .sw[aria-checked="true"]::after { transform: translateX(18px); }
            .row { padding: 8px 0; }
            .row .hint { display: block; }
        }
    `;

    // ---- theme: derive everything from the page's real background so it is always readable ----
    const parseRGB = (s) => {
        const m = /rgba?\(([^)]+)\)/.exec(s || '');
        if (!m) return null;
        const v = m[1].split(',').map(parseFloat);
        return (v.length >= 3 && !v.slice(0, 3).some(isNaN)) ? v : null;
    };
    const lum = (c) => (0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]) / 255;
    const opaque = (c) => !!c && (c.length < 4 || c[3] > 0.5);

    function applyTheme() {
        let bg = null;
        for (const node of [document.querySelector('ytd-app'), document.body, document.documentElement]) {
            const c = node ? parseRGB(getComputedStyle(node).backgroundColor) : null;
            if (opaque(c)) { bg = c; break; }
        }
        if (!bg) bg = safe(() => matchMedia('(prefers-color-scheme: dark)').matches, false) ? [15, 15, 15] : [255, 255, 255];
        const fg = lum(bg) > 0.5 ? [15, 15, 15] : [255, 255, 255];
        const f = `${fg[0]},${fg[1]},${fg[2]}`;
        const b = `${Math.round(bg[0])},${Math.round(bg[1])},${Math.round(bg[2])}`;
        for (const h of [ui.chipHost, ui.toastHost, P && P.host]) {
            if (!h) continue;
            h.style.setProperty('--fg', f);
            h.style.setProperty('--bg', b);
        }
    }
    // <html> may not exist yet at document-start, so attach as soon as it does.
    const watchTheme = () => {
        const root = document.documentElement;
        if (!root) return false;
        new MutationObserver(applyTheme).observe(root, { attributes: true, attributeFilter: ['dark'] });
        return true;
    };
    if (!watchTheme()) document.addEventListener('DOMContentLoaded', watchTheme, { once: true });

    // ---- toast (optionally with one action button) ----
    function toast(text, action) {
        if (!document.body) return;
        if (!ui.toast) {
            const host = document.createElement('div');
            host.id = 'yb-toast-host';
            const root = host.attachShadow({ mode: 'open' });
            const style = document.createElement('style');
            style.textContent = TOAST_CSS;
            ui.toast = el('div', { class: 'toast', role: 'status', 'aria-live': 'polite' });
            root.appendChild(style);
            root.appendChild(ui.toast);
            document.body.appendChild(host);
            ui.toastHost = host;
            applyTheme();
        }
        // In fullscreen only the fullscreen element is drawn, so the toast has to live inside it.
        const want = document.fullscreenElement || document.body;
        if (ui.toastHost && ui.toastHost.parentNode !== want) want.appendChild(ui.toastHost);
        ui.toast.textContent = '';
        ui.toast.appendChild(document.createTextNode(text));
        if (action) {
            ui.toast.appendChild(el('button', {
                class: 'act', type: 'button',
                on: { click: () => { ui.toast.classList.remove('show'); try { action.run(); } catch (e) { noteError(e); } } }
            }, action.label));
        }
        ui.toast.classList.toggle('interactive', !!action);
        ui.toast.classList.add('show');
        clearTimeout(ui.toastT);
        ui.toastT = setTimeout(() => ui.toast.classList.remove('show'), action ? 7000 : 2600);
    }

    // Messages about things the script did on its own (can be switched off in Extras).
    function notify(text, action) { if (settings.notify) toast(text, action); }

    function resetSettings() {
        const keepDev = settings.devLog;
        Object.assign(settings, DEFAULTS);
        settings.devLog = keepDev;
        saveSettings();
        guard.capRank = 99;
        saveCap();
        applyQualityChoice();
        applyStyle();
        renderAll();
        toast('Settings reset to defaults');
    }

    // ---- Developer tools: a health check of everything this script depends on, and a diagnostic report ----
    const API_METHODS = ['getPlayerState', 'getCurrentTime', 'getDuration', 'getPlaybackQuality', 'getAvailableQualityData', 'getAvailableQualityLevels',
        'setPlaybackQualityRange', 'setPlaybackQuality', 'getVideoData', 'getStatsForNerds', 'seekTo', 'playVideo', 'pauseVideo', 'stopVideo', 'loadVideoById'];

    function runHealthCheck() {
        const out = [];
        // ok: true = found, false = broken, null = not applicable / cannot tell right now
        const add = (name, ok, detail) => out.push({ name, ok: ok === null ? null : !!ok, detail: String(detail === undefined ? '' : detail) });
        const p = getPlayer();
        const onShorts = isShorts();
        add('Player element', !!p, p ? (p.id || 'no id') : 'not found');
        if (p) {
            const miss = API_METHODS.filter((m) => typeof p[m] !== 'function');
            add('Player API', miss.length === 0, miss.length ? 'missing: ' + miss.join(', ') : API_METHODS.length + ' methods present');
            const vd = safe(() => p.getVideoData(), null);
            add('Video data', !!(vd && vd.video_id), vd ? 'keys: ' + Object.keys(vd).slice(0, 10).join(', ') : 'unavailable');
            const qd = safe(() => p.getAvailableQualityData(), null);
            const q0 = qd && qd[0];
            add('Quality list', !!(q0 && typeof q0.quality === 'string'), q0 ? `${qd.length} entries, keys: ${Object.keys(q0).join(', ')}` : (qd ? 'empty (normal before playback)' : 'unavailable'));
            const st = safe(() => p.getStatsForNerds(), null);
            const need = ['codecs', 'resolution', 'bandwidth_kbps'];
            add('Stats for nerds', !!st && need.every((k) => k in st),
                st ? 'missing: ' + (need.filter((k) => !(k in st)).join(', ') || 'none') + ' | resolution looks like "' + String(st.resolution).slice(0, 40) + '"' : 'not available (normal on m.youtube.com)');
            add('Ad marker class', null, 'ad-showing only exists while an ad plays; now: ' + adShowing(p));
        }
        const v = getVideo(p);
        add('Video element', !!v, v ? `${v.videoWidth}x${v.videoHeight}, readyState ${v.readyState}` : 'not found');
        const title = document.querySelector('ytd-watch-metadata #title');
        add('Title container (chip docking)', onShorts ? null : !!(title && shown(title)), onShorts ? 'not used on Shorts' : (title ? 'found' : 'not found: the chip will float'));
        add('Player gear button', !!document.querySelector('.ytp-settings-button'), '.ytp-settings-button');
        add('Quality menu (needs the menu open)', document.querySelector('.ytp-quality-menu') ? true : null, document.querySelector('.ytp-quality-menu') ? 'found' : 'open the gear > Quality menu, then run again');
        add('Up next toggle', onShorts ? null : !!document.querySelector('.ytp-autonav-toggle-button'), '.ytp-autonav-toggle-button');
        add('Popup container', !!document.querySelector('ytd-popup-container'), 'ytd-popup-container');
        add('Shorts markers', null, 'shelves: ' + document.querySelectorAll('ytd-rich-shelf-renderer[is-shorts]').length + ', sidebar entry: ' + document.querySelectorAll('ytd-guide-entry-renderer a[title="Shorts"]').length);
        add('Ad slots in the page', null, 'ytd-ad-slot-renderer: ' + document.querySelectorAll('ytd-ad-slot-renderer').length + ' (hidden by CSS), #masthead-ad: ' + !!document.querySelector('#masthead-ad'));
        const ipr = window.ytInitialPlayerResponse;
        add('Page data: player response', ipr ? true : null, ipr ? 'keys: ' + Object.keys(ipr).slice(0, 12).join(', ') + ' | ad fields left: ' + (PLAYER_AD_KEYS.filter((k) => k in ipr).join(',') || 'none') : 'absent (normal after in-page navigation)');
        add('Page data: ytInitialData', window.ytInitialData ? true : null, window.ytInitialData ? Object.keys(window.ytInitialData).slice(0, 8).join(', ') : 'absent');
        const msHooked = !!(window.MediaSource && window.MediaSource.__ybHooked);
        add('Codec hooks', msHooked, `MediaSource hooked: ${msHooked}, AV1 hardware: ${av1Hw}, last video type: ${lastVideoType || 'none yet'}`);
        add('Native app info', NATIVE ? true : null, NATIVE
            ? `${NATIVE.model || 'device'}: Max = ${NATIVE.ceilingTier} (${NATIVE.limitedBy} limit), hardware decode VP9 ${!!(NATIVE.codecs.vp9 && NATIVE.codecs.vp9.hw)}, AV1 ${!!(NATIVE.codecs.av1 && NATIVE.codecs.av1.hw)}, H.264 ${!!(NATIVE.codecs.avc && NATIVE.codecs.avc.hw)}`
            : 'not running inside the app (plain userscript)');
        const fHooked = !!(window.fetch && window.fetch.__yb);
        add('Ad-blocking hooks', fHooked, `fetch wrapped: ${fHooked}, removed so far: player ${adStats.player}, feed ${adStats.feed}, requests ${adStats.requests}, skipped ${adStats.skipped}, warnings ${adStats.enforcement}`);
        add('YouTube client version', null, safe(() => window.ytcfg.get('INNERTUBE_CLIENT_VERSION'), '') || 'unknown');
        return out;
    }

    function showHealth() {
        const res = runHealthCheck();
        const bad = res.filter((r) => r.ok === false);
        const lines = res.map((r) => (r.ok === true ? '\u2713 ' : r.ok === false ? '\u2717 ' : '- ') + r.name + (r.detail ? ': ' + r.detail : ''));
        lines.unshift(bad.length ? `${bad.length} problem${bad.length === 1 ? '' : 's'} found:` : 'Everything this script depends on was found.');
        if (P) P.devOut.textContent = lines.join('\n');
        devLog('health', bad.length + ' failing', bad.map((r) => r.name));
        return res;
    }

    function buildReport() {
        const p = getPlayer();
        return {
            script: { name: 'Better YouTube', version: VERSION, settings: Object.assign({}, settings), device: { phone: IS_PHONE, mobileSite: IS_MOBILE_SITE, saveData: SAVE_DATA, av1Hardware: av1Hw, native: NATIVE } },
            env: {
                userAgent: UA, page: location.hostname + location.pathname.replace(/\/[\w-]{6,}/g, '/\u2026'), shorts: isShorts(),
                viewport: `${innerWidth}x${innerHeight}@${window.devicePixelRatio}`, language: navigator.language, darkAttribute: document.documentElement.hasAttribute('dark'),
                ytClientVersion: safe(() => window.ytcfg.get('INNERTUBE_CLIENT_VERSION'), null), ytClientName: safe(() => window.ytcfg.get('INNERTUBE_CLIENT_NAME'), null),
                time: new Date().toISOString()
            },
            health: runHealthCheck(),
            playerMethodTypes: p ? API_METHODS.reduce((o, m) => { o[m] = typeof p[m]; return o; }, {}) : null,
            statsKeys: safe(() => Object.keys(p.getStatsForNerds()), null),
            youtubeStorage: safe(() => {
                const o = {};
                for (let i = 0; i < localStorage.length; i++) {
                    const k = localStorage.key(i);
                    if (/^yt-player-(quality|performance|bandwidth)/.test(k)) o[k] = String(localStorage.getItem(k)).slice(0, 200);
                }
                return o;
            }, {}),
            state: { vid, rec, relief, guard, adStats, audioStats, status: lastStatus, info,
                perf: { ticks: perf.n, avgTickMs: perf.n ? +(perf.sum / perf.n).toFixed(3) : 0, maxTickMs: +perf.max.toFixed(2), longTasksLastMinute: perf.long } },
            swallowedErrors: [...devErrors.entries()].map(([msg, v]) => ({ msg, count: v.n, where: v.stack })),
            recentLog: devRing.slice(-200).map((e) => Object.assign({}, e, { t: new Date(e.t).toISOString().slice(11, 23) }))
        };
    }

    async function copyReport() {
        const text = JSON.stringify(buildReport(), null, 2);
        try {
            await navigator.clipboard.writeText(text);
            toast('Report copied to the clipboard');
        } catch (e) {
            // Clipboard blocked: save it as a file instead.
            const a = document.createElement('a');
            a.href = URL.createObjectURL(new Blob([text], { type: 'application/json' }));
            a.download = 'better-youtube-report.json';
            document.body.appendChild(a);
            a.click();
            a.remove();
            setTimeout(() => URL.revokeObjectURL(a.href), 10000);
            toast('Report saved as better-youtube-report.json');
        }
    }

    // ---- chip ----
    function buildChip() {
        const host = document.createElement('span');
        host.id = 'yb-chip-host';
        const root = host.attachShadow({ mode: 'open' });
        const style = document.createElement('style');
        style.textContent = CHIP_CSS;

        ui.dot = el('span', { class: 'dot' });
        ui.q = el('span', { text: '…' });
        ui.div = el('span', { class: 'div' });
        ui.c = el('span', { text: '' });
        ui.badge = el('span', { class: 'badge' });
        ui.badge.style.display = 'none';
        ui.chip = el('button', {
            class: 'chip', type: 'button', 'aria-haspopup': 'dialog', 'aria-expanded': 'false',
            'aria-label': 'Better YouTube settings (Alt+B)', on: { click: togglePanel }
        }, ui.dot, ui.q, ui.div, ui.c, ui.badge, icon('M7 10l5 5 5-5', 14, 'chev'));

        root.appendChild(style);
        root.appendChild(ui.chip);
        ui.chipHost = host;
    }

    // Desktop layout: dock beside the video title. Phones, m.youtube.com and any layout we do not
    // recognise: a floating chip, so the controls are always reachable.
    function floatChip() {
        if (!document.body) return false;
        const h = ui.chipHost;
        h.setAttribute('data-float', '');
        h.style.cssText = 'position:fixed;left:12px;bottom:calc(12px + env(safe-area-inset-bottom, 0px));z-index:2147483000;';
        document.body.appendChild(h);
        ui.floating = true;
        return true;
    }

    function ensureUI() {
        if (isFullscreen()) return;          // never float a chip over fullscreen video
        const dock = IS_MOBILE_SITE ? null : findDock();
        const mode = dock ? dock.mode : 'float';
        const attached = ui.chipHost && ui.chipHost.isConnected;
        const inPlace = attached && ui.mode === mode && (mode === 'float' || (mode === 'shorts' ? ui.chipHost.previousElementSibling === dock.el : ui.chipHost.parentElement === dock.el));
        if (inPlace) return;                 // already in the right place
        if (!ui.chipHost) buildChip();
        const title = mode === 'title' ? dock.el : null;

        if (mode === 'shorts') {
            // Right of the channel name, inside the Short's own overlay: a dark pill that reads over video.
            ui.chipHost.removeAttribute('data-float');
            ui.chipHost.setAttribute('data-overlay', '');
            ui.chipHost.style.cssText = 'flex:none;align-self:center;margin-left:8px;';
            dock.el.after(ui.chipHost);
            ui.floating = false;
            ui.misses = 0;
        } else if (title) {
            ui.chipHost.removeAttribute('data-float');
            ui.chipHost.removeAttribute('data-overlay');
            ui.chipHost.style.cssText = '';
            // Title becomes a wrapping row: short titles share the line with the chip, long ones push it below.
            title.style.display = 'flex';
            title.style.flexWrap = 'wrap';
            title.style.alignItems = 'center';
            title.style.columnGap = '12px';
            title.appendChild(ui.chipHost);
            ui.floating = false;
            ui.misses = 0;
        } else if (IS_MOBILE_SITE || ++ui.misses >= 3) {
            ui.chipHost.removeAttribute('data-overlay');
            if (!floatChip()) return;
        } else {
            return;
        }
        ui.mode = mode;
        applyTheme();
        renderAll();
    }

    function setUIVisible(visible) {
        if (ui.chipHost) ui.chipHost.style.display = visible ? '' : 'none';
        if (!visible && panelOpen) closePanel();
    }

    function renderChip(p) {
        if (!ui.chip) return;
        const cur = playerReady(p) ? safe(() => p.getPlaybackQuality(), '') : '';
        const shown = playerReady(p) ? renderedHeight(p) : 0;
        if (ui.dot.dataset.l !== lastStatus.level) { ui.dot.className = 'dot ' + lastStatus.level; ui.dot.dataset.l = lastStatus.level; }
        setText(ui.q, shown ? `${shown}p${info.fps > 30 ? info.fps : ''}` : (QL[cur] || 'Auto'));
        setText(ui.c, info.codec);
        ui.div.style.display = info.codec ? '' : 'none';
        ui.c.style.display = info.codec ? '' : 'none';
        const nTxt = rec.count > 0 ? `↻ ${rec.count}` : '';
        setText(ui.badge, nTxt);
        ui.badge.style.display = nTxt ? '' : 'none';
        ui.chip.title = lastStatus.text;
    }

    // ---- panel ----
    function showTab(name) {
        if (!P) return;
        P.tab = name;
        for (const k of Object.keys(P.tabs)) {
            P.tabs[k].setAttribute('aria-selected', String(k === name));
            P.pages[k].hidden = k !== name;
        }
        positionPanel();
    }

    function buildPanel() {
        const host = document.createElement('div');
        host.id = 'yb-panel-host';
        const root = host.attachShadow({ mode: 'open' });
        const style = document.createElement('style');
        style.textContent = PANEL_CSS;

        const refs = { host, q: {}, c: {}, sw: {}, st: {}, tabs: {}, pages: {}, tab: 'play' };

        refs.statusDot = el('span', { class: 'dot' });
        refs.statusText = el('span', { text: '' });

        const seg = (label, options, store, key) => {
            const g = el('div', { class: 'seg', role: 'radiogroup', 'aria-label': label });
            for (const [k, text, tip] of options) {
                const b = el('button', { class: 'opt', type: 'button', role: 'radio', 'aria-checked': 'false', on: { click: () => setSetting(key, k) } }, text);
                if (tip) b.title = tip;
                store[k] = b;
                g.appendChild(b);
            }
            return g;
        };
        const qSeg = seg('Quality ceiling', QUALITY_OPTIONS, refs.q, 'quality');
        qSeg.title = 'Your choice in YouTube\'s own quality menu overrides this for that video.';
        const cSeg = seg('Video codec', CODEC_OPTIONS, refs.c, 'codec');
        refs.codecHint = el('div', { class: 'hint', text: '' });
        refs.qualityHint = el('div', { class: 'hint', text: NATIVE ? MAX_HINT : '' });
        if (!NATIVE) refs.qualityHint.style.display = 'none';

        const toggle = (key, title, desc) => {
            const b = el('button', { class: 'sw', type: 'button', role: 'switch', 'aria-checked': 'false', 'aria-label': title, title: desc, on: { click: () => setSetting(key, !settings[key]) } });
            refs.sw[key] = b;
            return el('div', { class: 'row', title: desc },
                el('div', {}, el('div', { class: 'row-title', text: title }), el('div', { class: 'hint', text: desc })), b);
        };

        const stat = (key, label) => {
            const dd = el('dd', { text: '–' });
            refs.st[key] = dd;
            return el('div', { class: 'stat' }, el('dt', { text: label }), dd);
        };

        const closeBtn = el('button', { class: 'x', type: 'button', 'aria-label': 'Close', on: { click: () => closePanel(true) } }, icon('M6 6l12 12M18 6L6 18', 16));
        const reloadBtn = el('button', { class: 'btn', type: 'button', title: 'Reload the stream and resume from here', on: { click: () => reloadStream() } }, icon('M20 12a8 8 0 1 1-2.3-5.7M20 4v5h-5', 14), 'Reload');
        const frameBtn = el('button', { class: 'btn', type: 'button', title: 'Save the current frame at the video\'s full resolution (Alt+S)', on: { click: () => saveFrame() } },
            icon('M4 8V6a2 2 0 0 1 2-2h2M16 4h2a2 2 0 0 1 2 2v2M20 16v2a2 2 0 0 1-2 2h-2M8 20H6a2 2 0 0 1-2-2v-2M9 12h6', 14), 'Save frame');

        refs.pages.play = el('div', { class: 'page' },
            el('div', { class: 'sec', text: 'Quality ceiling' }), qSeg, refs.qualityHint,
            el('div', { class: 'sec', text: 'Codec' }), cSeg, refs.codecHint,
            el('div', { class: 'sec', text: 'Smooth playback' }),
            el('div', { class: 'rows' },
                toggle('recovery', 'Fix stalls automatically', 'Nudge, then reload the stream when it freezes.'),
                toggle('adaptive', 'Adapt on slow connections', 'Allow a lower tier while buffering, then return.'),
                toggle('smoothGuard', 'Step down if frames drop', 'Lower the ceiling when this device cannot keep up.')),
            el('div', { class: 'sec', text: 'Audio' }),
            el('div', { class: 'rows' },
                toggle('audioFix', 'Fix audio dropouts', 'Re-syncs sound that cuts out while the video keeps playing, and after you switch headphones or speakers.'),
                toggle('audioOriginal', 'Original loudness', 'Stops YouTube turning loud videos down (Stable volume). Applies from the next video.')),
            el('div', { class: 'sec', text: 'Now playing' }),
            el('dl', { class: 'stats' },
                stat('res', 'Resolution'), stat('codec', 'Codec'), stat('buf', 'Buffer ahead'),
                stat('drop', 'Dropped frames'), stat('bw', 'Bandwidth'), stat('rec', 'Fixes')));

        const healthBtn = el('button', { class: 'btn', type: 'button', title: 'Check that everything this script relies on can still be found on this page', on: { click: () => showHealth() } }, 'Health check');
        const reportBtn = el('button', { class: 'btn', type: 'button', title: 'Copy a diagnostic report (no personal data) to attach to a bug report', on: { click: () => copyReport() } }, 'Copy report');
        const clearBtn = el('button', { class: 'btn', type: 'button', title: 'Empty the event log', on: { click: () => { devRing.length = 0; devErrors.clear(); renderDev(); } } }, 'Clear log');
        refs.devOut = el('pre', { class: 'log', text: 'Run the health check to see what this script can find on the current page.' });
        refs.devLogBox = el('pre', { class: 'log', text: '' });

        let armed = 0;
        const resetBtn = el('button', { class: 'btn', type: 'button', on: { click: () => {
            if (armed) { clearTimeout(armed); armed = 0; resetBtn.textContent = 'Reset settings'; resetSettings(); return; }
            resetBtn.textContent = 'Click again to confirm';
            armed = setTimeout(() => { armed = 0; resetBtn.textContent = 'Reset settings'; }, 3500);
        } } }, 'Reset settings');

        refs.pages.dev = el('div', { class: 'page' },
            el('div', { class: 'sec', text: 'Diagnostics' }),
            el('div', { class: 'hint', text: 'Records what this script sees on YouTube. If something stops working after a YouTube update, run the health check and copy the report.' }),
            el('div', { class: 'btns' }, healthBtn, reportBtn, clearBtn),
            refs.devOut,
            el('div', { class: 'sec', text: 'Performance' }),
            el('dl', { class: 'stats' }, stat('perfTick', 'Script cost per tick'), stat('perfLong', 'Page freezes (last minute)'), stat('audioState', 'Audio'), stat('loudness', 'Loudness gain')),
            el('div', { class: 'sec', text: 'Recent events' }),
            refs.devLogBox);
        refs.pages.dev.hidden = true;

        refs.pages.block = el('div', { class: 'page' },
            el('div', { class: 'sec', text: 'Ad blocking' }),
            el('div', { class: 'rows' },
                toggle('adBlock', 'Block ads', 'Removes ads from the data YouTube sends before they play, and hides ad slots and banners.'),
                toggle('adRequests', 'Block ad requests', 'Stops the ad and tracking requests this page makes.'),
                toggle('adSkip', 'Skip ads that slip through', 'Mutes and fast-forwards any video ad that still plays.')),
            el('div', { class: 'sec', text: 'Blocked this session' }),
            el('dl', { class: 'stats' }, stat('adsData', 'Ads removed'), stat('adsReq', 'Requests blocked'), stat('adsSkip', 'Ads skipped'), stat('adsWarn', 'Warnings removed')),
            el('div', { class: 'hint', text: 'This works inside the page, so it cannot match network-level blocking in a browser, and YouTube changes often. If it stops working, turn on the Developer log in Extras.' }));
        refs.pages.block.hidden = true;

        refs.pages.extras = el('div', { class: 'page' },
            el('div', { class: 'sec', text: 'Behaviour' }),
            el('div', { class: 'rows' },
                toggle('autoPlay', 'Start playing on load', 'Begin playback as soon as a video opens.'),
                toggle('autonavOff', 'Turn off Up next autoplay', 'Stops YouTube starting the next video on its own.'),
                toggle('keepWatching', 'Dismiss "Continue watching?"', 'Clicks Yes on the idle prompt so playback does not stop.'),
                toggle('hiddenDowngrade', 'Save data in background tabs', 'Lowest quality while the tab is hidden.'),
                toggle('notify', 'Show notifications', 'Brief messages when this script changes something on its own.')),
            el('div', { class: 'sec', text: 'Cleaner YouTube' }),
            el('div', { class: 'rows' },
                toggle('hideShorts', 'Hide Shorts', 'Removes Shorts shelves, results and the sidebar entry.'),
                toggle('shortsRedirect', 'Open Shorts in the normal player', 'Plays Shorts like regular videos, with full controls and your quality settings.'),
                toggle('hideOverlays', 'Hide end cards and pause suggestions', 'Removes the clutter over the video.')),
            el('div', { class: 'sec', text: 'Tools' }),
            el('div', { class: 'row' }, el('div', {}, el('div', { class: 'row-title', text: 'Save frame as PNG' }), el('div', { class: 'hint', style: 'display:block', text: 'Full video resolution, not window size.' })), frameBtn),
            el('div', { class: 'hint' }, 'Shortcuts: ', el('kbd', { text: 'Alt+B' }), ' panel, ', el('kbd', { text: 'Alt+S' }), ' save frame.'),
            el('div', { class: 'sec', text: 'Developer' }),
            el('div', { class: 'rows' },
                toggle('devLog', 'Developer log', 'Records what this script sees in YouTube, so a future breakage is easy to fix. Adds a Debug tab.')),
            el('div', { class: 'btns' }, resetBtn));
        refs.pages.extras.hidden = true;

        const tab = (key, label) => {
            const b = el('button', { class: 'tab', type: 'button', role: 'tab', 'aria-selected': String(key === 'play'), on: { click: () => showTab(key) } }, label);
            refs.tabs[key] = b;
            return b;
        };

        refs.panel = el('div', { class: 'panel', role: 'dialog', 'aria-label': 'Better YouTube settings' },
            el('div', { class: 'head' }, el('div', { class: 'title' }, 'Better YouTube', el('span', { class: 'ver', text: 'v' + VERSION })), el('div', { class: 'tools' }, reloadBtn, closeBtn)),
            el('div', { class: 'status' }, refs.statusDot, refs.statusText),
            el('div', { class: 'tabs', role: 'tablist' }, tab('play', 'Playback'), tab('block', 'Blocking'), tab('extras', 'Extras'), tab('dev', 'Debug')),
            refs.pages.play, refs.pages.block, refs.pages.extras, refs.pages.dev);

        refs.tabs.dev.hidden = !settings.devLog;   // the Diagnostics tab only exists while the developer log is on
        root.appendChild(style);
        root.appendChild(refs.panel);
        (document.body || document.documentElement).appendChild(host);
        P = refs;
    }

    function updatePanel(p) {
        if (!P) return;
        P.statusDot.className = 'dot ' + lastStatus.level;
        setText(P.statusText, lastStatus.text);

        // Dim tiers this video does not offer (still selectable).
        let maxAvail = 99;
        if (playerReady(p)) {
            const data = safe(() => p.getAvailableQualityData(), null);
            if (data && data.length) {
                maxAvail = -1;
                for (const q of data) if (q && q.isPlayable !== false && RANK[q.quality] > maxAvail) maxAvail = RANK[q.quality];
            }
        }
        for (const [key] of QUALITY_OPTIONS) {
            const b = P.q[key];
            b.setAttribute('aria-checked', String(settings.quality === key));
            b.classList.toggle('na', key !== 'max' && key !== 'auto' && RANK[key] > maxAvail);
        }
        for (const [key, , hint] of CODEC_OPTIONS) {
            P.c[key].setAttribute('aria-checked', String(settings.codec === key));
            if (settings.codec === key) {
                let h = hint;
                if (key === 'smart' && av1Hw !== null) h = av1Hw ? 'This device decodes AV1 in hardware, so AV1 is allowed.' : 'No AV1 hardware decode on this device: AV1 is hidden so YouTube serves VP9.';
                setText(P.codecHint, h);
            }
        }
        for (const key of Object.keys(P.sw)) P.sw[key].setAttribute('aria-checked', String(!!settings[key]));

        // Stats
        refreshInfo(p, true);
        const v = getVideo(p);
        setText(P.st.res, info.res || (v && v.videoWidth ? `${v.videoWidth}x${v.videoHeight}` : '–'));
        setText(P.st.codec, info.codec || '–');
        P.st.codec.title = info.raw;
        setText(P.st.buf, v ? `${bufferInfo(v).ahead.toFixed(1)} s` : '–');
        const q = v && safe(() => v.getVideoPlaybackQuality(), null);
        setText(P.st.drop, q && q.totalVideoFrames ? `${q.droppedVideoFrames} of ${q.totalVideoFrames}` : '–');
        setText(P.st.bw, isNaN(info.bw) ? '–' : `${(info.bw / 1000).toFixed(1)} Mbps`);
        setText(P.st.rec, `${rec.count} reload${rec.count === 1 ? '' : 's'}, ${rec.nudges} nudge${rec.nudges === 1 ? '' : 's'}`);
        setText(P.st.adsData, String(adStats.player + adStats.feed));
        setText(P.st.adsReq, String(adStats.requests));
        setText(P.st.adsSkip, String(adStats.skipped));
        setText(P.st.adsWarn, String(adStats.enforcement));
        P.tabs.dev.hidden = !settings.devLog;
        if (!settings.devLog && P.tab === 'dev') showTab('extras');
        if (P.tab === 'dev') renderDev();
    }

    // Refresh the Diagnostics tab: script cost, page freezes, audio state, loudness gain, and the latest events.
    function renderDev() {
        if (!P || !P.devLogBox) return;
        const avg = perf.n ? perf.sum / perf.n : 0;
        setText(P.st.perfTick, `${avg.toFixed(2)} ms avg, ${perf.max.toFixed(1)} ms max`);
        const worst = perf.long.reduce((m, e) => Math.max(m, e.d), 0);
        setText(P.st.perfLong, perf.long.length ? `${perf.long.length} (worst ${worst} ms)` : 'none');
        const v = getVideo(getPlayer());
        const measurable = !!v && typeof v.webkitAudioDecodedByteCount === 'number';
        setText(P.st.audioState, !measurable ? 'not measurable here'
            : (audioStats.dropouts ? `${audioStats.dropouts} dropout${audioStats.dropouts === 1 ? '' : 's'} fixed` : 'no dropouts')
              + (audioStats.deviceNudges ? `, ${audioStats.deviceNudges} device re-sync` : ''));
        const vol = safe(() => getPlayer().getStatsForNerds().volume, '');
        setText(P.st.loudness, vol ? String(vol).slice(0, 40) : '\u2013');
        const lines = devRing.slice(-40).map((e) => new Date(e.t).toISOString().slice(11, 19) + ' ' + e.tag + ' ' + e.msg + (e.data !== undefined ? ' ' + JSON.stringify(e.data).slice(0, 80) : ''));
        const text = lines.join('\n') || 'No events yet.';
        if (P.devLogBox.textContent !== text) { P.devLogBox.textContent = text; P.devLogBox.scrollTop = P.devLogBox.scrollHeight; }
    }

    function positionPanel() {
        if (!P || !panelOpen || !ui.chip) return;

        // Narrow screens (phones, split view): a bottom sheet, anchored by CSS.
        if (window.innerWidth < 560) {
            P.panel.classList.add('sheet');
            P.panel.style.left = '';
            P.panel.style.top = '';
            P.panel.style.maxHeight = Math.round(window.innerHeight * 0.82) + 'px';
            return;
        }
        P.panel.classList.remove('sheet');

        const r = ui.chip.getBoundingClientRect();
        if (r.bottom < 0 || r.top > window.innerHeight) { closePanel(); return; }
        const pw = P.panel.offsetWidth || 336;
        const natural = P.panel.scrollHeight || 560;
        const left = Math.min(Math.max(8, r.left), Math.max(8, window.innerWidth - pw - 8));
        const below = window.innerHeight - r.bottom - 16;
        const above = r.top - 16;
        // Prefer below the chip, then above it; if neither fits, use the roomier side and scroll.
        let top, room;
        if (natural <= below) { top = r.bottom + 8; room = below; }
        else if (natural <= above) { top = r.top - 8 - natural; room = above; }
        else if (below >= above) { top = r.bottom + 8; room = below; }
        else { room = above; top = Math.max(8, r.top - 8 - room); }
        P.panel.style.maxHeight = Math.max(200, room) + 'px';
        P.panel.style.left = left + 'px';
        P.panel.style.top = top + 'px';
    }

    const onOutside = (e) => {
        const path = e.composedPath();
        if (path.includes(P.host) || path.includes(ui.chipHost)) return;
        closePanel();
    };
    const onKey = (e) => {
        if (e.key === 'Escape') { e.stopPropagation(); closePanel(true); }
    };

    function openPanel() {
        if (!ui.chip) return;
        if (!P) buildPanel();
        applyTheme();
        panelOpen = true;
        ui.chip.setAttribute('aria-expanded', 'true');
        updatePanel(getPlayer());
        P.panel.classList.add('open');
        positionPanel();
        document.addEventListener('pointerdown', onOutside, true);
        document.addEventListener('keydown', onKey, true);
        window.addEventListener('resize', positionPanel);
        window.addEventListener('scroll', positionPanel, true);
        const first = P.panel.querySelector('.opt[aria-checked="true"]');
        if (first && P.tab === 'play') first.focus({ preventScroll: true });
    }

    function closePanel(refocus) {
        if (!panelOpen) return;
        panelOpen = false;
        if (P) P.panel.classList.remove('open');
        if (ui.chip) ui.chip.setAttribute('aria-expanded', 'false');
        document.removeEventListener('pointerdown', onOutside, true);
        document.removeEventListener('keydown', onKey, true);
        window.removeEventListener('resize', positionPanel);
        window.removeEventListener('scroll', positionPanel, true);
        if (refocus && ui.chip) ui.chip.focus({ preventScroll: true });
    }

    function togglePanel() { if (panelOpen) closePanel(); else openPanel(); }

    function renderAll() {
        const p = getPlayer();
        renderChip(p);
        if (panelOpen) updatePanel(p);
    }

    // ==========================================
    // MAIN TICK (1 Hz)
    // ==========================================
    const tick = () => {
        // Page-wide extras run everywhere, not only on watch pages.
        if (++tickCount % 3 === 0) applyStyle();
        if (settings.keepWatching && tickCount % 2 === 0) dismissIdlePrompt();
        if (settings.adBlock && tickCount % 2 === 1) removeEnforcement();
        if (maybeRedirectShorts()) return;

        if (!isWatchPage()) { setUIVisible(false); return; }
        const p = getPlayer();
        if (!playerReady(p)) return;

        setUIVisible(!isFullscreen());
        ensureUI();
        watchPlayerAds(p);

        if (!welcomed) {
            welcomed = true;
            if (!safe(() => localStorage.getItem('ybWelcomed'), '1')) {
                safe(() => localStorage.setItem('ybWelcomed', '1'));
                toast('Better YouTube is on. Press Alt+B for settings.');
            }
        }

        const ad = adShowing(p);
        if (ad) adStart();
        if (!ad) {
            // Backup for in-player video changes that do not fire a navigation event.
            // (Skipped during ads: the ad can report its own id.)
            const id = safe(() => p.getVideoData().video_id, '');
            if (id && id !== vid.id) { vid.id = id; resetForNewVideo(GRACE_MS); }
        }

        if (document.hidden) { markIdle(); return; }

        const now = Date.now();
        const state = p.getPlayerState();
        lastState = state;
        if (state !== lastLogged) { devLog('state', `${lastLogged} -> ${state}`, { t: Math.round(safe(() => p.getCurrentTime(), 0)), q: safe(() => p.getPlaybackQuality(), '') }); lastLogged = state; }
        maybeAutoplay(p, state);

        if (ad) {
            markIdle();
            lastStatus = computeStatus(p, true);
            renderAll();
            return;
        }

        maybeDisableAutonav();
        ensureQuality(p);
        watchAudio(p, state, now);
        if (settings.smoothGuard && tickCount % 5 === 0 && state === 1 && !relief.active && now - vid.startedAt > 8000 && now >= rec.graceUntil) watchDrops(p);
        refreshInfo(p, !!(info.optH && info.curH && info.curH < info.optH));   // poll faster while a switch is in flight
        watchSwitch(p, state, now);
        manageRelief(p, state, now);
        lastStatus = computeStatus(p, false);
        renderAll();
        if (tickCount % 10 === 0) applyTheme();

        if (settings.recovery) watchPlayback(p, state, now);
    };

    // ==========================================
    // EVENTS
    // ==========================================
    const onNavStart = () => {
        // Suspend monitoring while the page transitions.
        rec.graceUntil = Date.now() + NAV_SUSPEND_MS;
        markIdle();
    };
    const onNavFinish = () => {
        devLog('nav', 'finish', location.pathname.replace(/\/[\w-]{6,}/g, '/\u2026'));
        if (maybeRedirectShorts()) return;
        resetForNewVideo(GRACE_MS + 1500);
        ensureUI();
        applyStyle();
        setTimeout(tick, 600);
    };
    document.addEventListener('yt-navigate-start', onNavStart);
    document.addEventListener('yt-navigate-finish', onNavFinish);
    document.addEventListener('state-navigatestart', onNavStart);    // m.youtube.com
    document.addEventListener('state-navigatefinish', onNavFinish);  // m.youtube.com
    document.addEventListener('yt-page-data-updated', () => ensureUI());
    document.addEventListener('fullscreenchange', () => { if (document.fullscreenElement) closePanel(); setUIVisible(!isFullscreen()); });

    document.addEventListener('visibilitychange', () => {
        if (!document.hidden) {
            // Back in the tab: timers from before are meaningless, give playback a moment.
            markIdle();
            rec.graceUntil = Math.max(rec.graceUntil, Date.now() + 2000);
            guard.lastTotal = -1;
        }
        if (!settings.hiddenDowngrade || !isWatchPage()) return;
        const p = getPlayer();
        if (!playerReady(p)) return;
        if (document.hidden) {
            vid.hiddenApplied = true;
            safe(() => p.setPlaybackQualityRange('tiny', 'tiny'));
        } else if (vid.hiddenApplied) {
            vid.hiddenApplied = false;
            applyQualityChoice();
        }
    });

    setInterval(() => {
        const t0 = performance.now();
        safe(tick);
        const d = performance.now() - t0;
        perf.n++;
        perf.sum += d;
        if (d > perf.max) perf.max = d;
    }, 1000);

    // Small debug / config handle
    window.betterYT = {
        get settings() { return Object.assign({}, settings); },
        get device() { return { phone: IS_PHONE, mobileSite: IS_MOBILE_SITE, saveData: SAVE_DATA, av1Hardware: av1Hw, native: NATIVE, qualityCap: guard.capRank, userQuality: vid.userQuality }; },
        set(key, value) {
            if (!(key in DEFAULTS)) { console.warn('[BetterYT] Unknown setting:', key); return; }
            setSetting(key, value);
        },
        reload() { reloadStream(); },
        saveFrame() { saveFrame(); },
        health() { return showHealth(); },
        report() { return buildReport(); },
        copyReport() { return copyReport(); },
        get ads() { return Object.assign({}, adStats); }
    };

    console.log(`[BetterYT] v${VERSION} loaded - quality: ${settings.quality} | codec: ${settings.codec}`);
})();
