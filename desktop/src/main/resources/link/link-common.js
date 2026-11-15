/* Lyrenne Link: shared by the overlay and the phone remote. Plain JS, no build step. */
"use strict";

var Link = (function () {
  /** The pairing token, from `#t=` (the QR code) or remembered from an earlier visit. */
  function token() {
    var m = /[#&?]t=([A-Za-z0-9_-]{20,})/.exec(location.hash || "") || /[?&]t=([A-Za-z0-9_-]{20,})/.exec(location.search || "");
    var t = m ? m[1] : null;
    try {
      if (t) localStorage.setItem("lyrenne-token", t);
      else t = localStorage.getItem("lyrenne-token");
    } catch (e) { /* storage blocked: the hash still works */ }
    return t;
  }

  function forgetToken() {
    try { localStorage.removeItem("lyrenne-token"); } catch (e) {}
  }

  function fmt(ms) {
    if (!(ms > 0)) return "0:00";
    var s = Math.floor(ms / 1000), m = Math.floor(s / 60);
    s = s % 60;
    if (m >= 60) return Math.floor(m / 60) + ":" + String(m % 60).padStart(2, "0") + ":" + String(s).padStart(2, "0");
    return m + ":" + String(s).padStart(2, "0");
  }

  /** Index of the line playing at `pos`, or -1 before the first. Binary search, lines are sorted. */
  function lineAt(lines, pos) {
    if (!lines || !lines.length) return -1;
    var lo = 0, hi = lines.length - 1, ans = -1;
    while (lo <= hi) {
      var mid = (lo + hi) >> 1;
      if (lines[mid].t <= pos) { ans = mid; lo = mid + 1; } else hi = mid - 1;
    }
    return ans;
  }

  /** How far through a word or line `pos` is, 0 to 1. */
  function progress(start, end, pos) {
    if (pos <= start) return 0;
    if (end <= start || pos >= end) return 1;
    return (pos - start) / (end - start);
  }

  /**
   * Live connection to Lyrenne. Events arrive on change, plus a heartbeat every 5 s, so the
   * position is interpolated here between them rather than streamed.
   *
   * Reconnects on its own: EventSource retries a dropped stream, and a watchdog replaces one that
   * went quiet (a phone waking from sleep often holds a dead socket that never errors).
   */
  function Live(opts) {
    this.opts = opts;
    this.state = null;
    this.receivedAt = 0;
    this.lastEvent = 0;
    this.es = null;
    this.backoff = 1000;
    this.status = "connecting";
    var self = this;
    document.addEventListener("visibilitychange", function () {
      if (document.visibilityState === "visible" && Date.now() - self.lastEvent > 7000) self.reconnect(0);
    });
    window.addEventListener("online", function () { self.reconnect(0); });
    setInterval(function () {
      if (document.visibilityState === "visible" && self.es && Date.now() - self.lastEvent > 16000) self.reconnect(0);
    }, 4000);
  }

  Live.prototype.setStatus = function (s) {
    if (this.status === s) return;
    this.status = s;
    if (this.opts.onStatus) this.opts.onStatus(s);
  };

  Live.prototype.connect = function () {
    var self = this, t = this.opts.token;
    if (this.es) { this.es.close(); this.es = null; }
    var es = new EventSource("/events" + (t ? "?t=" + encodeURIComponent(t) : ""));
    this.es = es;
    this.lastEvent = Date.now();
    function on(name, fn) {
      es.addEventListener(name, function (e) {
        if (self.es !== es) return;
        self.lastEvent = Date.now();
        self.backoff = 1000;
        self.setStatus("live");
        var data;
        try { data = JSON.parse(e.data); } catch (err) { return; }
        fn(data);
      });
    }
    on("state", function (s) {
      self.state = s;
      self.receivedAt = performance.now();
      if (self.opts.onState) self.opts.onState(s);
    });
    on("lyrics", function (l) { if (self.opts.onLyrics) self.opts.onLyrics(l); });
    on("queue", function (q) { if (self.opts.onQueue) self.opts.onQueue(q); });
    es.onerror = function () {
      if (self.es !== es) return;
      self.setStatus("reconnecting");
      // CLOSED means the server answered with an error rather than dropping: find out which.
      if (es.readyState === 2) { es.close(); self.es = null; self.check(); }
    };
  };

  /** After a refused stream: unpaired (401), remote switched off (403), or just not up yet. */
  Live.prototype.check = function () {
    var self = this;
    var headers = this.opts.token ? { "X-Lyrenne-Token": this.opts.token } : {};
    fetch(this.opts.probe || "/api/now", { headers: headers, cache: "no-store" }).then(function (r) {
      if (r.status === 401) { self.setStatus("unpaired"); return; }
      if (r.status === 403) { self.setStatus("off"); self.reconnect(self.backoff = Math.min(self.backoff * 2, 15000)); return; }
      self.reconnect(self.backoff);
    }).catch(function () {
      self.reconnect(self.backoff = Math.min(self.backoff * 2, 15000));
    });
  };

  Live.prototype.reconnect = function (delay) {
    var self = this;
    if (this.status === "unpaired") return;
    clearTimeout(this.timer);
    this.timer = setTimeout(function () { self.connect(); }, delay || 0);
  };

  /** The playing position now, moved on from the last report by the time since. */
  Live.prototype.position = function () {
    var s = this.state;
    if (!s) return 0;
    var p = s.positionMs;
    if (s.playing) p += performance.now() - this.receivedAt;
    return s.durationMs > 0 ? Math.min(p, s.durationMs) : p;
  };

  /** Applies a change locally before Lyrenne confirms it, so taps feel instant. */
  Live.prototype.patch = function (fields) {
    if (!this.state) return;
    var pos = this.position();
    for (var k in fields) this.state[k] = fields[k];
    if (!("positionMs" in fields)) this.state.positionMs = pos;
    this.receivedAt = performance.now();
    if (this.opts.onState) this.opts.onState(this.state);
  };

  function api(path, tok, body) {
    var init = { headers: { "X-Lyrenne-Token": tok || "" }, cache: "no-store" };
    if (body !== undefined) {
      init.method = "POST";
      init.headers["Content-Type"] = "application/json";
      init.body = JSON.stringify(body);
    }
    return fetch(path, init).then(function (r) {
      return r.json().catch(function () { return {}; }).then(function (j) {
        if (!r.ok) { var e = new Error(j.error || ("HTTP " + r.status)); e.status = r.status; throw e; }
        return j;
      });
    });
  }

  /** `#rrggbb` to [r, g, b]. */
  function rgb(hex) {
    var m = /^#?([0-9a-f]{6})$/i.exec(hex || "");
    if (!m) return null;
    var n = parseInt(m[1], 16);
    return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
  }

  return { token: token, forgetToken: forgetToken, fmt: fmt, lineAt: lineAt, progress: progress, Live: Live, api: api, rgb: rgb };
})();
