// Refuerzo de viewport (aquí el <head> ya existe): borra el viewport de TikTok y fija width=1280.
// Es el "cinturón y tirantes" del arreglo de recorte que también va en onPageStarted.
(function () {
    try {
        var head = document.head || document.documentElement;
        if (head) {
            var old = document.querySelectorAll('meta[name="viewport"]');
            for (var i = 0; i < old.length; i++) { if (old[i].parentNode) old[i].parentNode.removeChild(old[i]); }
            var m = document.createElement('meta');
            m.name = 'viewport';
            m.content = 'width=1280';
            head.appendChild(m);
        }
    } catch (e) {}
})();

(function () {
    'use strict';

    if (window.__TKTV_LOADED__) return;
    window.__TKTV_LOADED__ = true;

    // Settings pushed by the app before this script runs (window.__TKTV_CFG__).
    var cfg = window.__TKTV_CFG__ || {};
    var autoNext = !!cfg.autoNext;
    var userMuted = null;   // null = leave TikTok's own mute state alone

    // Optional pure-black surfaces for OLED/AMOLED panels. Keep TikTok content and controls intact.
    if (cfg.amoled) try {
        var amoledStyle = document.createElement('style');
        amoledStyle.id = 'tktv-amoled-style';
        amoledStyle.textContent = 'html, body { background-color: #000000 !important; }';
        (document.head || document.documentElement).appendChild(amoledStyle);
        document.documentElement.style.setProperty('background-color', '#000000', 'important');
        if (document.body) document.body.style.setProperty('background-color', '#000000', 'important');
    } catch (e) {}

    // --- Disable AV1: forces H.264 HARDWARE decoding (key on Amlogic boxes). Can be turned off in app settings. ---
    if (cfg.disableAv1 !== false) try {
        var origIsTypeSupported = window.MediaSource && window.MediaSource.isTypeSupported;
        if (origIsTypeSupported) {
            window.MediaSource.isTypeSupported = function (type) {
                if (type && (type.toLowerCase().indexOf('av01') !== -1 || type.toLowerCase().indexOf('av1') !== -1)) return false;
                return origIsTypeSupported.call(window.MediaSource, type);
            };
        }
        var origCanPlayType = window.HTMLMediaElement && window.HTMLMediaElement.prototype.canPlayType;
        if (origCanPlayType) {
            window.HTMLMediaElement.prototype.canPlayType = function (type) {
                if (type && (type.toLowerCase().indexOf('av01') !== -1 || type.toLowerCase().indexOf('av1') !== -1)) return '';
                return origCanPlayType.call(this, type);
            };
        }
        if (window.navigator && window.navigator.mediaCapabilities && window.navigator.mediaCapabilities.decodingInfo) {
            var origDecodingInfo = window.navigator.mediaCapabilities.decodingInfo;
            window.navigator.mediaCapabilities.decodingInfo = function (configuration) {
                if (configuration && configuration.video) {
                    var ct = (configuration.video.contentType || '').toLowerCase();
                    if (ct.indexOf('av01') !== -1 || ct.indexOf('av1') !== -1) {
                        return Promise.resolve({ supported: false, smooth: false, powerEfficient: false });
                    }
                }
                return origDecodingInfo.call(window.navigator.mediaCapabilities, configuration);
            };
        }
    } catch (e) { }

    // Inject Layout Shield for Video Constraints
    try {
        var style = document.createElement('style');
        style.id = 'tktv-layout-shield';
        style.innerHTML = `
            /* GPU: elimina sombras costosas de calcular en cada frame */
            * { box-shadow: none !important; text-shadow: none !important; }

            /* Core Fix: Force feed items to max 100vh height to fix TV Canvas stretching */
            section[data-e2e="feed-video"] {
                max-height: 100vh !important;
                display: flex !important;
                justify-content: center !important;
                align-items: center !important;
                overflow: hidden !important;
            }
            /* Restaurar mecanismo de Canvas para respetar la UI de PC sin asfixiar la caja */
            section[data-e2e="feed-video"] canvas {
                display: block !important;
                height: 100vh !important;
                max-height: 100vh !important;
                width: auto !important;
                max-width: 100vw !important;
            }
            
            /* Tope de altura maestro sin rebasar pantalla */
            section[data-e2e="feed-video"] {
                max-height: 100vh !important;
                min-height: 0 !important; /* BUGFIX FATAL: TikTok impone min-height de 623px, destrozando el max-height! */
                min-width: 0 !important; /* BUGFIX: TikTok Desktop impone min-width: 348px */
                border-radius: 0 !important; /* Fix Hardware Rendering Black Screen */
            }
            
            /* Purga de bordes para que los videos Hardware-accelerated no exploten en negro */
            div[class*="DivVideoPlayerContainer"], 
            div[class*="BasePlayerContainer"],
            div[class*="DivContainer"],
            div[class*="DivBasicPlayerWrapper"] {
                max-height: 100vh !important;
                min-height: 0 !important;
                min-width: 0 !important;
                border-radius: 0 !important;
            }
            

            /* Video proporcional dentro del bounds (Contain, JAMÁS cover).
               Selector de alta especificidad para ganarle a las reglas .clase{object-fit:cover} de TikTok. */
            html body section[data-e2e="feed-video"] video,
            html body video {
                max-height: 100vh !important;
                object-fit: contain !important;
                border-radius: 0 !important;
                transform: translateZ(0);
                backface-visibility: hidden;
            }

            /* TV: make the focused sidebar button obvious from the couch */
            button:focus, [role="button"]:focus, [data-e2e$="-icon"]:focus {
                outline: 3px solid #25F4EE !important;
                outline-offset: 2px !important;
                border-radius: 8px;
            }
        `;
        document.head.appendChild(style);
    } catch (e) { }

    function sendKey(key, code, charCode) {
        try {
            var params = { key: key, code: code, keyCode: charCode, which: charCode, bubbles: true, cancelable: true, view: window };
            var event = new KeyboardEvent('keydown', params);
            (document.activeElement || document.body).dispatchEvent(event);
            document.dispatchEvent(event);
            window.dispatchEvent(event);
        } catch (e) { }
    }

    var sidebarMode = false;

    // Blindaje anti-recorte: fuerza object-fit:contain INLINE (gana a cualquier hoja de estilo de TikTok)
    function hardenVideos() {
        try {
            var vs = document.getElementsByTagName('video');
            for (var i = 0; i < vs.length; i++) {
                vs[i].style.setProperty('object-fit', 'contain', 'important');
                vs[i].style.setProperty('max-height', '100vh', 'important');
                vs[i].style.setProperty('max-width', '100vw', 'important');
                if (userMuted !== null) vs[i].muted = userMuted;
            }
        } catch (e) {}
    }

    // --- Detección de la X de un modal por POSICIÓN (robusto ante clases ofuscadas) ---
    function elVisible(el) {
        if (!el) return false;
        var r = el.getBoundingClientRect();
        if (r.width < 1 || r.height < 1) return false;
        var s = window.getComputedStyle(el);
        return s.visibility !== 'hidden' && s.display !== 'none' && parseFloat(s.opacity || '1') > 0.05;
    }

    function findTopModal() {
        var list = document.querySelectorAll('[role="dialog"], [aria-modal="true"]');
        var best = null, bestZ = -1;
        for (var i = 0; i < list.length; i++) {
            var d = list[i];
            if (!elVisible(d)) continue;
            var r = d.getBoundingClientRect();
            if (r.width < window.innerWidth * 0.25 || r.height < window.innerHeight * 0.15) continue;
            var z = parseInt(window.getComputedStyle(d).zIndex) || 0;
            if (z >= bestZ) { bestZ = z; best = d; }
        }
        if (best) return best;
        // Fallback: contenedor fixed/absolute grande y centrado con z-index alto
        var all = document.querySelectorAll('div, section');
        for (var k = 0; k < all.length; k++) {
            var el = all[k];
            var st = window.getComputedStyle(el);
            if (st.position !== 'fixed' && st.position !== 'absolute') continue;
            if ((parseInt(st.zIndex) || 0) < 100) continue;
            if (!elVisible(el)) continue;
            var rr = el.getBoundingClientRect();
            if (rr.width > window.innerWidth * 0.3 && rr.width < window.innerWidth * 0.98 &&
                rr.height > window.innerHeight * 0.25 &&
                rr.top < window.innerHeight * 0.5 && rr.left < window.innerWidth * 0.5) {
                return el;
            }
        }
        return null;
    }

    function findCloseInTopRight(modal) {
        var mr = modal.getBoundingClientRect();
        var cands = modal.querySelectorAll('button, svg, [role="button"], [aria-label], span[class], div[class]');
        var best = null, bestScore = Infinity;
        for (var i = 0; i < cands.length; i++) {
            var el = cands[i];
            if (!elVisible(el)) continue;
            var r = el.getBoundingClientRect();
            if (r.width > 80 || r.height > 80) continue;        // debe ser un icono pequeño
            if (r.width > mr.width * 0.35) continue;
            var cx = r.left + r.width / 2, cy = r.top + r.height / 2;
            if (cx < mr.left + mr.width * 0.55) continue;       // mitad derecha
            if (cy > mr.top + mr.height * 0.28) continue;       // franja superior
            var dx = mr.right - cx, dy = cy - mr.top;           // cercanía a la esquina sup-derecha
            var score = dx * dx + dy * dy;
            if (score < bestScore) { bestScore = score; best = el; }
        }
        if (best) return best.closest('button') || best.closest('[role="button"]') || best;
        return null;
    }

    function getActiveItem() {
        try {
            var containers = document.querySelectorAll(FEED_ITEM_SELECTOR);
            var active = null;
            var minDistance = Infinity;
            var midScreen = window.innerHeight / 2;

            for (var i = 0; i < containers.length; i++) {
                var rect = containers[i].getBoundingClientRect();
                var center = rect.top + (rect.height / 2);
                var dist = Math.abs(center - midScreen);
                if (dist < minDistance) {
                    minDistance = dist;
                    active = containers[i];
                }
            }
            return active;
        } catch (e) { return null; }
    }

    var FEED_ITEM_SELECTOR = 'section[data-e2e="feed-video"], [data-e2e="recommend-list-item-container"], div[class*="DivItemContainer"]';
    var SIDEBAR_SELECTOR = '[data-e2e="like-icon"], [data-e2e="browse-like-icon"], [data-e2e="comment-icon"], ' +
        '[data-e2e="browse-comment-icon"], [data-e2e="undefined-icon"], [data-e2e="share-icon"], [data-e2e="browse-share-icon"]';
    var lastFeedStepAt = 0;
    var lastAutoNextAt = 0;

    // ---- Clean screen: hide everything except the playing video --------------------------------
    // Generic on purpose (TikTok's class names are obfuscated): walk from the active <video> up to <body>
    // and hide every sibling along the way (sidebar, caption, progress bar, header, side nav ...), except
    // anything that contains a <video> (the neighbouring feed items). Hidden = opacity 0 + no pointer
    // events, so the layout does not move and programmatic clicks (next video etc.) still work.
    var CLEAN_ATTR = 'data-tktv-clean';
    var cleanTimer = null;

    function ensureCleanStyle() {
        if (document.getElementById('tktv-clean-style')) return;
        var st = document.createElement('style');
        st.id = 'tktv-clean-style';
        st.textContent = '[' + CLEAN_ATTR + '="1"]{opacity:0 !important;pointer-events:none !important;transition:none !important;}';
        (document.head || document.documentElement).appendChild(st);
    }

    function clearClean() {
        var n = document.querySelectorAll('[' + CLEAN_ATTR + ']');
        for (var i = 0; i < n.length; i++) n[i].removeAttribute(CLEAN_ATTR);
    }

    function applyClean() {
        try {
            var v = activeVideo();
            if (!v) return;
            var chain = [];
            for (var a = v; a && a !== document.documentElement; a = a.parentElement) chain.push(a);
            for (var k = 0; k < chain.length; k++) chain[k].removeAttribute(CLEAN_ATTR);
            for (var i = 1; i < chain.length; i++) {
                var kids = chain[i].children, from = chain[i - 1];
                for (var j = 0; j < kids.length; j++) {
                    var c = kids[j];
                    if (c === from) continue;
                    var tag = c.tagName;
                    if (tag === 'SCRIPT' || tag === 'STYLE' || tag === 'LINK' || tag === 'META' || tag === 'NOSCRIPT') continue;
                    if (c.querySelector('video')) continue;   // other feed items stay visible
                    c.setAttribute(CLEAN_ATTR, '1');
                }
            }
        } catch (e) { }
    }

    function activeVideo() {
        try {
            var item = getActiveItem();
            var v = item ? item.querySelector('video') : null;
            if (!v) {
                var list = document.getElementsByTagName('video');
                for (var i = 0; i < list.length; i++) {
                    if (list[i].offsetWidth > 0 && list[i].offsetHeight > 0) { v = list[i]; break; }
                }
                if (!v && list.length) v = list[0];
            }
            return v || null;
        } catch (e) { return null; }
    }

    // Next / previous video: arrow buttons first, keyboard event as fallback.
    function feedStep(dir) {
        var sel = dir > 0
            ? 'button[data-e2e="arrow-down"], [class*="ButtonArrow"][class*="Down"]'
            : 'button[data-e2e="arrow-up"], [class*="ButtonArrow"][class*="Up"]';
        var btn = document.querySelector(sel);
        if (btn) btn.click();
        else if (dir > 0) sendKey('ArrowDown', 'ArrowDown', 40);
        else sendKey('ArrowUp', 'ArrowUp', 38);
        lastFeedStepAt = Date.now();
        setTimeout(hardenVideos, 350); // the new video enters after the scroll animation
        if (cleanTimer) setTimeout(applyClean, 450);
        return dir > 0 ? 'down' : 'up';
    }

    // Visible like / comment / save / share buttons of the active video, in DOM order.
    function sidebarButtons() {
        var out = [];
        try {
            var root = getActiveItem() || document;
            var nodes = root.querySelectorAll(SIDEBAR_SELECTOR);
            for (var i = 0; i < nodes.length; i++) {
                var b = nodes[i].closest('button, [role="button"]') || nodes[i];
                if (out.indexOf(b) === -1 && elVisible(b)) out.push(b);
            }
        } catch (e) { }
        return out;
    }

    // Moves D-pad focus between sidebar buttons. Returns false when there is nothing to move between.
    function stepSidebarFocus(dir) {
        try {
            var list = sidebarButtons();
            if (!list.length) return false;
            var cur = list.indexOf(document.activeElement);
            if (cur < 0) {
                for (var i = 0; i < list.length; i++) {
                    if (list[i].contains(document.activeElement)) { cur = i; break; }
                }
            }
            var next = cur < 0 ? 0 : Math.min(list.length - 1, Math.max(0, cur + dir));
            list[next].focus();
            try { list[next].scrollIntoView({ block: 'nearest' }); } catch (e) { }
            return true;
        } catch (e) { return false; }
    }

    function isScrollable(el) {
        try {
            var st = window.getComputedStyle(el);
            return (st.overflowY === 'auto' || st.overflowY === 'scroll') && el.scrollHeight > el.clientHeight + 4;
        } catch (e) { return false; }
    }

    // Auto-next: when the visible video is about to end, move to the next one.
    function maybeAutoNext(v) {
        try {
            if (!autoNext || !v || sidebarMode) return;
            var d = v.duration;
            if (!d || !isFinite(d) || d < 2) return;
            var r = v.getBoundingClientRect();
            var mid = window.innerHeight / 2;
            if (r.top > mid || r.bottom < mid) return;          // not the video on screen
            if (v.currentTime < 1) { v.__tktvNext = false; return; }
            if (v.__tktvNext) return;
            if (!v.ended && d - v.currentTime > 0.6) return;
            v.__tktvNext = true;
            var now = Date.now();
            if (now - lastAutoNextAt < 1500) return;
            lastAutoNextAt = now;
            feedStep(1);
        } catch (e) { }
    }
    document.addEventListener('timeupdate', function (e) {
        if (e.target && e.target.tagName === 'VIDEO') maybeAutoNext(e.target);
    }, true);
    document.addEventListener('ended', function (e) {
        if (e.target && e.target.tagName === 'VIDEO') maybeAutoNext(e.target);
    }, true);

    window.TikTokTV = {
        enterSidebar: function () {
            sidebarMode = true;
            try {
                var list = sidebarButtons();
                if (list.length > 0) {
                    list[0].focus();
                } else {
                    var activeItem = getActiveItem();
                    var root = activeItem || document;
                    var buttons = root.querySelectorAll('[data-e2e="like-icon"], [data-e2e="comment-icon"], [data-e2e="share-icon"], button[aria-label*="Like"]');
                    if (buttons.length > 0) {
                        var target = buttons[0].tagName === 'BUTTON' ? buttons[0] : (buttons[0].closest('button') || buttons[0]);
                        target.focus();
                    }
                }
            } catch (e) { }
            return 'sidebar_on';
        },
        exitSidebar: function () {
            sidebarMode = false;
            if (document.activeElement) document.activeElement.blur();
            return 'sidebar_off';
        },
        getShareUrl: function () {
            try {
                var item = getActiveItem();
                if (item) {
                    var links = item.querySelectorAll('a[href*="/video/"]');
                    for (var i = 0; i < links.length; i++) {
                        var href = links[i].href;
                        if (href && href.indexOf('tiktok.com/') !== -1) return href;
                    }
                }
                return window.location.href;
            } catch (e) {
                return window.location.href;
            }
        },

        select: function () {
            try {
                // Native Android share: intercept the focused TikTok share button.
                if (sidebarMode && document.activeElement && document.activeElement !== document.body) {
                    var focused = document.activeElement;
                    var shareSelector = '[data-e2e="share-icon"], [data-e2e="browse-share-icon"]';
                    var shareButton = null;

                    try {
                        if (focused.matches && focused.matches(shareSelector)) shareButton = focused;
                        if (!shareButton && focused.querySelector) shareButton = focused.querySelector(shareSelector);
                        if (!shareButton && focused.closest) shareButton = focused.closest(shareSelector);
                        if (shareButton) return 'native_share';
                    } catch (e) { }

                    focused.click();
                    return 'sidebar_clicked';
                }

                // Context-aware video toggle
                var v = activeVideo();

                if (v) {
                    if (v.paused) v.play().catch(function () { });
                    else v.pause();
                    return 'v_toggled';
                }
            } catch (e) { }

            // 3. Last resort: Space key
            sendKey(' ', 'Space', 32);
            return 'ok';
        },
        scrollDown: function () {
            if (sidebarMode) {
                // Inteligencia para encontrar contenedores de scroll nativos en el panel lateral
                var scrollables = document.querySelectorAll('[class*="CommentListContainer"], [class*="DivCommentContainer"], [class*="DivSidebarContainer"], .tiktok-scrollbar');
                for (var i = 0; i < scrollables.length; i++) {
                    if (scrollables[i].scrollHeight > scrollables[i].clientHeight) {
                        scrollables[i].scrollBy({ top: 250, behavior: 'smooth' });
                        return 'sidebar_down_native';
                    }
                }
                // Fallback manual a cualquier caja deslizable lateral enfocada
                if (document.activeElement && document.activeElement.scrollHeight > document.activeElement.clientHeight) {
                    document.activeElement.scrollBy({ top: 250, behavior: 'smooth' });
                    return 'sidebar_down_active';
                }
                if (stepSidebarFocus(1)) return 'sidebar_focus_next';
                sendKey('ArrowDown', 'ArrowDown', 40);
                return 'sidebar_down';
            }
            return feedStep(1);
        },
        scrollUp: function () {
            if (sidebarMode) {
                var scrollables = document.querySelectorAll('[class*="CommentListContainer"], [class*="DivCommentContainer"], [class*="DivSidebarContainer"], .tiktok-scrollbar');
                for (var i = 0; i < scrollables.length; i++) {
                    if (scrollables[i].scrollHeight > scrollables[i].clientHeight) {
                        scrollables[i].scrollBy({ top: -250, behavior: 'smooth' });
                        return 'sidebar_up_native';
                    }
                }
                if (document.activeElement && document.activeElement.scrollHeight > document.activeElement.clientHeight) {
                    document.activeElement.scrollBy({ top: -250, behavior: 'smooth' });
                    return 'sidebar_up_active';
                }
                if (stepSidebarFocus(-1)) return 'sidebar_focus_prev';
                sendKey('ArrowUp', 'ArrowUp', 38);
                return 'sidebar_up';
            }
            return feedStep(-1);
        },
        playIfPaused: function () {
            hardenVideos();
            var v = activeVideo();
            if (v && v.paused) v.play().catch(function () { });
            return 'checked';
        },
        togglePlay: function () {
            var v = activeVideo();
            if (!v) return 'novideo';
            if (v.paused) { v.play().catch(function () { }); return 'playing'; }
            v.pause();
            return 'paused';
        },
        play: function () {
            var v = activeVideo();
            if (v && v.paused) v.play().catch(function () { });
            return v ? 'playing' : 'novideo';
        },
        pause: function () {
            var v = activeVideo();
            if (v) v.pause();
            return v ? 'paused' : 'novideo';
        },
        // Called when the app goes to the background: silence every media element.
        pauseAll: function () {
            var list = document.querySelectorAll('video, audio');
            for (var i = 0; i < list.length; i++) { try { list[i].pause(); } catch (e) { } }
            return 'paused_all';
        },
        seek: function (delta) {
            var v = activeVideo();
            if (!v) return 'novideo';
            try {
                var t = v.currentTime + delta;
                var d = v.duration;
                if (isFinite(d)) t = Math.min(t, Math.max(0, d - 0.3));
                v.currentTime = Math.max(0, t);
                v.__tktvNext = false; // allow auto-next again after seeking back
            } catch (e) { return 'error'; }
            return 'seek';
        },
        toggleMute: function () {
            var list = document.getElementsByTagName('video');
            if (!list.length) return 'novideo';
            var ref = activeVideo() || list[0];
            userMuted = !ref.muted;
            for (var i = 0; i < list.length; i++) { list[i].muted = userMuted; }
            return userMuted ? 'muted' : 'unmuted';
        },
        // Search results load asynchronously: poll (up to ~12 s) and open the first video result.
        openFirstResult: function () {
            var sel = '[data-e2e="search_video-item"] a[href*="/video/"], [data-e2e="search_top-item"] a[href*="/video/"], ' +
                '[data-e2e="search-card-item"] a[href*="/video/"], [data-e2e="search_video-item"], a[href*="/video/"]';
            var tries = 0;
            var timer = setInterval(function () {
                tries++;
                try {
                    var el = document.querySelector(sel);
                    if (el && el.offsetWidth > 0 && el.offsetHeight > 0) {
                        clearInterval(timer);
                        (el.closest('a') || el).click();
                        return;
                    }
                } catch (e) { }
                if (tries > 24) clearInterval(timer);
            }, 500);
            return 'polling';
        },
        setCleanMode: function (on) {
            if (on) {
                ensureCleanStyle();
                applyClean();
                if (!cleanTimer) cleanTimer = setInterval(applyClean, 700);
                return 'clean_on';
            }
            if (cleanTimer) { clearInterval(cleanTimer); cleanTimer = null; }
            clearClean();
            return 'clean_off';
        },
        setAutoNext: function (value) {
            autoNext = !!value;
            return autoNext ? 'auto_next_on' : 'auto_next_off';
        },
        // Scrolls whatever is under the (CSS-mapped) cursor: comment lists, panels, or the feed itself.
        scrollAt: function (xPx, yPx, viewW, viewH, dy) {
            try {
                var cx = xPx * window.innerWidth / (viewW || window.innerWidth);
                var cy = yPx * window.innerHeight / (viewH || window.innerHeight);
                var el = document.elementFromPoint(cx, cy);
                var feed = false;
                for (var e = el; e && e !== document.body && e !== document.documentElement; e = e.parentElement) {
                    if (!isScrollable(e)) continue;
                    if (e.querySelector(FEED_ITEM_SELECTOR)) { feed = true; break; }   // the feed scroller itself
                    e.scrollBy({ top: dy, behavior: 'smooth' });
                    return 'scrolled';
                }
                if (feed || (el && el.closest && el.closest(FEED_ITEM_SELECTOR))) {
                    if (Date.now() - lastFeedStepAt < 700) return 'throttled';
                    return feedStep(dy > 0 ? 1 : -1);
                }
                window.scrollBy({ top: dy, behavior: 'smooth' });
                return 'page';
            } catch (e) { return 'error'; }
        },
        // Counts the elements this script depends on, so a TikTok DOM change shows up in logcat.
        selfTest: function () {
            var q = function (s) { try { return document.querySelectorAll(s).length; } catch (e) { return -1; } };
            return 'video=' + q('video') +
                ';feed=' + q(FEED_ITEM_SELECTOR) +
                ';active=' + (getActiveItem() ? 1 : 0) +
                ';sidebar=' + sidebarButtons().length +
                ';arrows=' + q('button[data-e2e="arrow-down"], button[data-e2e="arrow-up"]');
        },
        back: function () {
            if (sidebarMode) {
                sidebarMode = false;
                if (document.activeElement) document.activeElement.blur();
                return 'sidebar_off';
            }

            // 1) Botón de cerrar visible (lista amplia, case-insensitive)
            var closeSelectors = [
                '[data-e2e="modal-close-inner-button"]',
                '[data-e2e="close-icon"]',
                '[data-e2e="close-button"]',
                '[data-e2e="browse-close"]',
                '[data-e2e*="close" i]',
                '[aria-label="Close"]',
                '[aria-label="Cerrar"]',
                '[aria-label*="close" i]',
                '[aria-label*="cerrar" i]',
                '.tiktok-overlay-close-button',
                'button[class*="close" i]',
                'div[class*="CloseIcon" i]',
                'div[class*="DivCloseWrapper" i]',
                'svg[class*="close" i]'
            ];

            for (var i = 0; i < closeSelectors.length; i++) {
                var buttons = document.querySelectorAll(closeSelectors[i]);
                for (var j = 0; j < buttons.length; j++) {
                    var btn = buttons[j];
                    // Only click if element is actually visible on screen
                    if (btn && btn.offsetWidth > 0 && btn.offsetHeight > 0) {
                        var clickTarget = btn.tagName === 'BUTTON' ? btn : (btn.closest('button') || btn);
                        clickTarget.click();
                        return 'closed_modal';
                    }
                }
            }

            // 2) Buscar la X por POSICIÓN (arriba-derecha del modal): robusto ante clases ofuscadas
            var modal = findTopModal();
            if (modal) {
                var xBtn = findCloseInTopRight(modal);
                if (xBtn) { xBtn.click(); return 'closed_modal'; }
                sendKey('Escape', 'Escape', 27); // sin X visible: último recurso
                return 'closed_modal';
            }

            return 'nav_back';
        }
    };

})();
