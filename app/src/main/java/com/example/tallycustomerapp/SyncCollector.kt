package com.example.tallycustomerapp.web

/**
 * Full-page mirror collector.
 *
 * The app no longer decides that a page is "syncable" only when it contains a
 * Ledger/Voucher/Stock HTML table. Every authenticated portal page is mirrored
 * as rendered HTML and grouped under the detected Tally company. A company
 * crawl can follow safe same-origin GET links and capture the pages it visits.
 *
 * No accounting values are fabricated by this collector.
 */
object SyncCollector {
    fun script(): String = """
        (function () {
            if (window.__tallyOfflinePageMirrorInstalled) {
                if (window.TallyOfflinePageMirror) window.TallyOfflinePageMirror.installUi();
                return;
            }
            window.__tallyOfflinePageMirrorInstalled = true;

            var BUTTON_ID = 'tally-offline-sync-company';
            var PAGE_BUTTON_ID = 'tally-offline-save-page';
            var STATUS_ID = 'tally-offline-mirror-status';
            var STATE_KEY = 'tally-offline-company-sync-state';
            var CHUNK_SIZE = 140000;
            var MAX_PAGES = 250;

            function clean(value) {
                return String(value == null ? '' : value)
                    .replace(/\u00a0/g, ' ')
                    .replace(/\s+/g, ' ')
                    .trim();
            }

            function safeStorage(storage) {
                try { return storage; } catch (e) { return null; }
            }

            function hash(value) {
                var h = 2166136261;
                value = String(value || '');
                for (var i = 0; i < value.length; i++) {
                    h ^= value.charCodeAt(i);
                    h = Math.imul(h, 16777619);
                }
                return (h >>> 0).toString(16);
            }

            function readStoredCompany() {
                var storages = [safeStorage(window.localStorage), safeStorage(window.sessionStorage)];
                var keys = [
                    'tally_company_name', 'companyName', 'selectedCompany', 'currentCompany',
                    'company_name', 'selected_company', 'activeCompany', 'active_company'
                ];
                for (var s = 0; s < storages.length; s++) {
                    var store = storages[s];
                    if (!store) continue;
                    for (var k = 0; k < keys.length; k++) {
                        try {
                            var value = clean(store.getItem(keys[k]));
                            if (value && !/^null$|^undefined$/i.test(value)) return value;
                        } catch (e) {}
                    }
                    try {
                        for (var i = 0; i < store.length; i++) {
                            var key = store.key(i) || '';
                            if (/company/i.test(key)) {
                                var candidate = clean(store.getItem(key));
                                if (candidate && candidate.length >= 2 && candidate.length <= 160 &&
                                    !/password|token|cookie|login|status/i.test(candidate)) return candidate;
                            }
                        }
                    } catch (e2) {}
                }
                return '';
            }

            function extractCompanyContext() {
                var companyName = readStoredCompany();
                var serialNumber = '';
                var gstin = '';
                var financialYearFrom = '';
                var bodyText = clean(document.body ? document.body.innerText : '');

                var patterns = [
                    /Company\s*Name\s*[:\-]\s*([^\n\r]+)/i,
                    /Current\s*Company\s*[:\-]\s*([^\n\r]+)/i,
                    /Company\s*[:\-]\s*([^\n\r]+)/i
                ];
                for (var p = 0; p < patterns.length && !companyName; p++) {
                    var m = bodyText.match(patterns[p]);
                    if (m && clean(m[1])) companyName = clean(m[1]);
                }

                var selectors = [
                    '[data-company-name]', '#companyName', '.company-name', '.companyName',
                    '[class*="company-name"]', '[class*="companyName"]',
                    '[aria-label*="company" i]', '[title*="company" i]'
                ];
                for (var j = 0; j < selectors.length && !companyName; j++) {
                    try {
                        var nodes = document.querySelectorAll(selectors[j]);
                        for (var n = 0; n < nodes.length; n++) {
                            var t = clean(nodes[n].getAttribute('data-company-name') || nodes[n].getAttribute('aria-label') || nodes[n].textContent);
                            if (t && !/^company$/i.test(t) && !/change company|select company/i.test(t)) {
                                companyName = t;
                                break;
                            }
                        }
                    } catch (e3) {}
                }

                try {
                    var labels = document.querySelectorAll('label,th,dt,span,div');
                    for (var q = 0; q < labels.length; q++) {
                        var label = clean(labels[q].textContent);
                        if (!serialNumber && /serial\s*(number|no\.?)/i.test(label)) {
                            var parent = labels[q].parentElement;
                            if (parent) serialNumber = clean(parent.innerText).replace(/.*serial\s*(number|no\.?)\s*[:\-]?/i, '');
                        }
                        if (!gstin && /GSTIN/i.test(label)) {
                            var parentG = labels[q].parentElement;
                            if (parentG) {
                                var gm = clean(parentG.innerText).match(/[0-9A-Z]{15}/i);
                                if (gm) gstin = gm[0].toUpperCase();
                            }
                        }
                    }
                } catch (e4) {}

                var title = clean(document.title);
                if (!companyName && title && !/login|sign\s*in|customer portal|tally/i.test(title)) {
                    // Last resort: use a stored/current title only when it plausibly names a company.
                    if (/private limited|ltd\.?|pvt\.?|llp|traders|enterprises|distributors|distribution/i.test(title)) {
                        companyName = title;
                    }
                }

                if (!companyName) companyName = 'Tally Company';
                if (!serialNumber) serialNumber = 'name:' + hash(companyName);
                return {
                    companyName: clean(companyName),
                    serialNumber: clean(serialNumber),
                    gstin: clean(gstin),
                    financialYearFrom: clean(financialYearFrom)
                };
            }

            function setStoredCompany(name) {
                try { window.localStorage.setItem('tally_offline_company_name', name); } catch (e) {}
                try { window.sessionStorage.setItem('tally_offline_company_name', name); } catch (e2) {}
            }

            function getState() {
                try {
                    var raw = window.localStorage.getItem(STATE_KEY);
                    return raw ? JSON.parse(raw) : null;
                } catch (e) { return null; }
            }

            function setState(state) {
                try { window.localStorage.setItem(STATE_KEY, JSON.stringify(state)); } catch (e) {}
            }

            function clearState() {
                try { window.localStorage.removeItem(STATE_KEY); } catch (e) {}
            }

            function pageRoute(url) {
                try {
                    var u = new URL(url, location.href);
                    return u.pathname + u.search + u.hash;
                } catch (e) { return String(url || ''); }
            }

            function pageKey(url) {
                return 'page:' + hash(pageRoute(url));
            }

            function setStatus(text) {
                var el = document.getElementById(STATUS_ID);
                if (el) el.textContent = text;
                if (window.AndroidBridge && window.AndroidBridge.setSyncStatus) {
                    window.AndroidBridge.setSyncStatus(String(text));
                }
            }

            function samePortalOrigin(url) {
                try {
                    var u = new URL(url, location.href);
                    return u.origin === location.origin && u.pathname.indexOf('/customerapp/') === 0;
                } catch (e) { return false; }
            }

            function isUnsafeLink(url, anchor) {
                if (!url || !samePortalOrigin(url)) return true;
                if (/^(javascript:|mailto:|tel:|data:|blob:)/i.test(url)) return true;
                var text = clean(anchor ? anchor.innerText : '');
                var hay = (url + ' ' + text).toLowerCase();
                if (/logout|log-out|signout|sign-out|delete|remove|destroy|submit|save|update|create|edit|post|upload|download|export|import/.test(hay)) return true;
                if (anchor && anchor.hasAttribute('download')) return true;
                return false;
            }

            function discoverLinks() {
                var links = [];
                var seen = {};
                var anchors = Array.prototype.slice.call(document.querySelectorAll('a[href]'));
                for (var i = 0; i < anchors.length; i++) {
                    var a = anchors[i];
                    var href = '';
                    try { href = a.href; } catch (e) { continue; }
                    if (isUnsafeLink(href, a)) continue;
                    try {
                        href = new URL(href, location.href).href;
                    } catch (e2) { continue; }
                    if (!seen[href] && href !== location.href) {
                        seen[href] = true;
                        links.push(href);
                    }
                }

                // Many SPA menus use role="link" on buttons/elements without a normal <a>.
                var roleLinks = Array.prototype.slice.call(document.querySelectorAll('[role="link"][data-href],[role="link"][data-url]'));
                for (var r = 0; r < roleLinks.length; r++) {
                    var el = roleLinks[r];
                    var target = el.getAttribute('data-href') || el.getAttribute('data-url') || '';
                    if (!target) continue;
                    try { target = new URL(target, location.href).href; } catch (e3) { continue; }
                    if (!isUnsafeLink(target, el) && !seen[target] && target !== location.href) {
                        seen[target] = true;
                        links.push(target);
                    }
                }
                return links.slice(0, MAX_PAGES);
            }

            function buildOfflineHtml() {
                var cloned = document.documentElement.cloneNode(true);
                try {
                    var removable = cloned.querySelectorAll('script, iframe, object, embed, video, audio, form, noscript');
                    for (var i = removable.length - 1; i >= 0; i--) removable[i].remove();
                    var anchors = cloned.querySelectorAll('a');
                    for (var a = 0; a < anchors.length; a++) {
                        anchors[a].removeAttribute('href');
                        anchors[a].removeAttribute('onclick');
                    }
                    var links = cloned.querySelectorAll('link[rel="stylesheet"], base');
                    for (var l = links.length - 1; l >= 0; l--) links[l].remove();

                    var css = '';
                    var sheets = Array.prototype.slice.call(document.styleSheets || []);
                    for (var s = 0; s < sheets.length; s++) {
                        try {
                            var rules = sheets[s].cssRules;
                            for (var c = 0; c < rules.length; c++) css += rules[c].cssText + '\n';
                        } catch (cssError) {}
                    }
                    if (css) {
                        var style = document.createElement('style');
                        style.setAttribute('data-offline-captured', '1');
                        style.textContent = css;
                        var head = cloned.querySelector('head') || cloned;
                        head.appendChild(style);
                    }
                } catch (e) {}
                return '<!doctype html>\n' + cloned.outerHTML;
            }

            function sendPagePayload(payload) {
                if (!window.AndroidBridge) {
                    setStatus('Android bridge not available');
                    return;
                }
                var raw = JSON.stringify(payload);
                if (raw.length <= CHUNK_SIZE && window.AndroidBridge.savePageSnapshot) {
                    window.AndroidBridge.savePageSnapshot(raw);
                    return;
                }
                if (!window.AndroidBridge.beginPageSnapshot || !window.AndroidBridge.pushPageSnapshotChunk || !window.AndroidBridge.commitPageSnapshot) {
                    setStatus('Page too large for current bridge');
                    return;
                }
                var syncId = 'page-' + Date.now() + '-' + Math.random().toString(16).slice(2);
                window.AndroidBridge.beginPageSnapshot(syncId);
                for (var i = 0; i < raw.length; i += CHUNK_SIZE) {
                    window.AndroidBridge.pushPageSnapshotChunk(syncId, raw.substring(i, Math.min(i + CHUNK_SIZE, raw.length)));
                }
                window.AndroidBridge.commitPageSnapshot(syncId);
            }

            function capturePage() {
                try {
                    var context = extractCompanyContext();
                    if (context.companyName && context.companyName !== 'Tally Company') setStoredCompany(context.companyName);
                    var html = buildOfflineHtml();
                    var payload = {
                        companyName: context.companyName,
                        serialNumber: context.serialNumber,
                        gstin: context.gstin || null,
                        financialYearFrom: context.financialYearFrom || null,
                        pageKey: pageKey(location.href),
                        title: clean(document.title) || 'Tally Page',
                        url: location.href,
                        route: pageRoute(location.href),
                        contentHash: hash(html),
                        capturedAt: Date.now(),
                        html: html
                    };
                    sendPagePayload(payload);
                    setStatus('Offline page saved: ' + (payload.title || payload.route));
                    return payload;
                } catch (e) {
                    setStatus('Page capture error: ' + (e && e.message ? e.message : e));
                    return null;
                }
            }

            function mergeDiscoveredLinks(state) {
                var links = discoverLinks();
                var known = {};
                for (var i = 0; i < state.queue.length; i++) known[state.queue[i]] = true;
                for (var v = 0; v < state.visited.length; v++) known[state.visited[v]] = true;
                for (var j = 0; j < links.length && state.visited.length + state.queue.length < MAX_PAGES; j++) {
                    if (!known[links[j]]) {
                        state.queue.push(links[j]);
                        known[links[j]] = true;
                    }
                }
            }

            function processCompanySync() {
                var state = getState();
                if (!state || !state.active) return;

                var current = location.href;
                if (state.visited.indexOf(current) < 0) state.visited.push(current);
                mergeDiscoveredLinks(state);
                setState(state);
                capturePage();

                setTimeout(function () {
                    var nextState = getState();
                    if (!nextState || !nextState.active) return;
                    while (nextState.queue.length) {
                        var next = nextState.queue.shift();
                        if (nextState.visited.indexOf(next) < 0) {
                            setState(nextState);
                            setStatus('Opening company page ' + (nextState.visited.length + 1) + '…');
                            try {
                                location.href = next;
                            } catch (e) {
                                setStatus('Navigation failed: ' + (e && e.message ? e.message : e));
                            }
                            return;
                        }
                    }
                    nextState.active = false;
                    setState(nextState);
                    setStatus('Company page sync complete: ' + nextState.visited.length + ' pages');
                }, 900);
            }

            function startCompanySync() {
                var context = extractCompanyContext();
                if (context.companyName !== 'Tally Company') setStoredCompany(context.companyName);
                var state = {
                    active: true,
                    companyName: context.companyName,
                    serialNumber: context.serialNumber,
                    returnUrl: location.href,
                    queue: [],
                    visited: [],
                    startedAt: Date.now()
                };
                mergeDiscoveredLinks(state);
                state.queue.unshift(location.href);
                setState(state);
                setStatus('Starting full company page sync…');
                processCompanySync();
            }

            function stopCompanySync() {
                var state = getState();
                if (state) {
                    state.active = false;
                    setState(state);
                    setStatus('Company page sync stopped');
                }
            }

            function installUi() {
                if (!document.body) return;
                if (!document.getElementById(BUTTON_ID)) {
                    var btn = document.createElement('button');
                    btn.id = BUTTON_ID;
                    btn.textContent = 'SYNC WHOLE COMPANY';
                    btn.style.cssText = 'position:fixed;top:10px;right:10px;z-index:2147483647;background:#0d6efd;color:#fff;border:2px solid #ffc107;padding:11px 14px;border-radius:12px;font-weight:800;box-shadow:0 3px 12px rgba(0,0,0,.3);font-size:14px;';
                    btn.onclick = function (e) {
                        e.preventDefault();
                        e.stopPropagation();
                        var state = getState();
                        if (state && state.active) stopCompanySync(); else startCompanySync();
                    };
                    document.body.appendChild(btn);
                }
                if (!document.getElementById(PAGE_BUTTON_ID)) {
                    var pageBtn = document.createElement('button');
                    pageBtn.id = PAGE_BUTTON_ID;
                    pageBtn.textContent = 'SAVE THIS PAGE';
                    pageBtn.style.cssText = 'position:fixed;top:58px;right:10px;z-index:2147483647;background:#198754;color:#fff;border:none;padding:8px 12px;border-radius:8px;font-weight:700;box-shadow:0 2px 8px rgba(0,0,0,.25);font-size:12px;';
                    pageBtn.onclick = function (e) {
                        e.preventDefault();
                        e.stopPropagation();
                        capturePage();
                    };
                    document.body.appendChild(pageBtn);
                }
                if (!document.getElementById(STATUS_ID)) {
                    var status = document.createElement('div');
                    status.id = STATUS_ID;
                    status.textContent = 'Auto offline mirror ready';
                    status.style.cssText = 'position:fixed;top:95px;right:10px;z-index:2147483647;background:rgba(255,255,255,.97);color:#222;border:1px solid #ddd;padding:6px 9px;border-radius:7px;font:12px sans-serif;max-width:330px;box-shadow:0 2px 8px rgba(0,0,0,.18);';
                    document.body.appendChild(status);
                }
            }

            window.TallyOfflinePageMirror = {
                installUi: installUi,
                capturePage: capturePage,
                startCompanySync: startCompanySync,
                stopCompanySync: stopCompanySync
            };

            installUi();
            setTimeout(installUi, 500);
            setTimeout(installUi, 1500);

            var currentState = getState();
            if (currentState && currentState.active) {
                setTimeout(processCompanySync, 1000);
            } else {
                setTimeout(capturePage, 800);
            }
        })();
    """.trimIndent()
}
