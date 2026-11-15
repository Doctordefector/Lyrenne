/* Lyrenne Link phone remote. */
"use strict";
(function () {
  var P = {
    play: "M8 5v14l11-7z",
    pause: "M6 19h4V5H6v14zm8-14v14h4V5h-4z",
    next: "M6 18l8.5-6L6 6v12zM16 6v12h2V6h-2z",
    prev: "M6 6h2v12H6zm3.5 6l8.5 6V6z",
    shuffle: "M10.59 9.17L5.41 4 4 5.41l5.17 5.17 1.42-1.41zM14.5 4l2.04 2.04L4 18.59 5.41 20 17.96 7.46 20 9.5V4h-5.5zm.33 9.41l-1.41 1.41 3.13 3.13L14.5 20H20v-5.5l-2.04 2.04-3.13-3.13z",
    repeat: "M7 7h10v3l4-4-4-4v3H5v6h2V7zm10 10H7v-3l-4 4 4 4v-3h12v-6h-2v4z",
    repeatOne: "M7 7h10v3l4-4-4-4v3H5v6h2V7zm10 10H7v-3l-4 4 4 4v-3h12v-6h-2v4zm-4-2V9h-1l-2 1v1h1.5v4H13z",
    heart: "M12 21.35l-1.45-1.32C5.4 15.36 2 12.28 2 8.5 2 5.42 4.42 3 7.5 3c1.74 0 3.41.81 4.5 2.09C13.09 3.81 14.76 3 16.5 3 19.58 3 22 5.42 22 8.5c0 3.78-3.4 6.86-8.55 11.54L12 21.35z",
    heartOutline: "M16.5 3c-1.74 0-3.41.81-4.5 2.09C10.91 3.81 9.24 3 7.5 3 4.42 3 2 5.42 2 8.5c0 3.78 3.4 6.86 8.55 11.54L12 21.35l1.45-1.32C18.6 15.36 22 12.28 22 8.5 22 5.42 19.58 3 16.5 3zm-4.4 15.55l-.1.1-.1-.1C7.14 14.24 4 11.39 4 8.5 4 6.5 5.5 5 7.5 5c1.54 0 3.04.99 3.57 2.36h1.87C13.46 5.99 14.96 5 16.5 5c2 0 3.5 1.5 3.5 3.5 0 2.89-3.14 5.74-7.9 10.05z",
    volume: "M3 9v6h4l5 5V4L7 9H3zm13.5 3c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 2.5-2.25 2.5-4.02zM14 3.23v2.06c2.89.86 5 3.54 5 6.71s-2.11 5.85-5 6.71v2.06c4.01-.91 7-4.49 7-8.77s-2.99-7.86-7-8.77z",
    disc: "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 14.5c-2.49 0-4.5-2.01-4.5-4.5S9.51 7.5 12 7.5s4.5 2.01 4.5 4.5-2.01 4.5-4.5 4.5zm0-5.5c-.55 0-1 .45-1 1s.45 1 1 1 1-.45 1-1-.45-1-1-1z",
    lyrics: "M14 17H4v2h10v-2zm6-8H4v2h16V9zM4 15h16v-2H4v2zM4 5v2h16V5H4z",
    queue: "M15 6H3v2h12V6zm0 4H3v2h12v-2zM3 16h8v-2H3v2zM17 6v8.18c-.31-.11-.65-.18-1-.18-1.66 0-3 1.34-3 3s1.34 3 3 3 3-1.34 3-3V8h3V6h-5z",
    search: "M15.5 14h-.79l-.28-.27C15.41 12.59 16 11.11 16 9.5 16 5.91 13.09 3 9.5 3S3 5.91 3 9.5 5.91 16 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z",
    addQueue: "M14 10H2v2h12v-2zm0-4H2v2h12V6zm4 8v-4h-2v4h-4v2h4v4h2v-4h4v-2h-4zM2 16h8v-2H2v2z",
    playNext: "M4 10h12v2H4zm0-4h12v2H4zm0 8h8v2H4zm10 0v6l5-3z",
    note: "M12 3v10.55c-.59-.34-1.27-.55-2-.55-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4V7h4V3h-6z"
  };
  function svg(name) { return '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="' + P[name] + '"/></svg>'; }
  var $ = function (s) { return document.querySelector(s); };
  function setIcon(el, name) { if (el.dataset.icon !== name) { el.innerHTML = svg(name); el.dataset.icon = name; } }

  // Static icons
  setIcon($("#prev"), "prev");
  setIcon($("#next"), "next");
  setIcon($("#shuffle"), "shuffle");
  setIcon($("#volicon"), "volume");
  $(".noart").innerHTML = '<svg viewBox="0 0 24 24" width="96" height="96"><path d="' + P.note + '"/></svg>';
  document.querySelectorAll("nav .ni").forEach(function (el) { el.innerHTML = svg(el.dataset.i); });

  var token = Link.token();
  // Ask the browser for an install manifest that opens straight back into this pairing.
  if (token) {
    var link = document.createElement("link");
    link.rel = "manifest";
    link.href = "/manifest.webmanifest?t=" + encodeURIComponent(token);
    document.head.appendChild(link);
    if (!/t=/.test(location.hash)) history.replaceState(null, "", "/remote#t=" + token);
  }

  var lyrics = null, lines = [], lineEls = [], curLine = -2, offset = 0;
  var queue = { index: -1, items: [] };
  var songId = null;
  var seeking = false, volDragging = false, view = "now";
  var userScrolledAt = 0;

  var live = new Link.Live({
    token: token,
    probe: "/api/state",
    onState: onState,
    onLyrics: onLyrics,
    onQueue: onQueue,
    onStatus: onStatus
  });

  function cmd(op, value) {
    return Link.api("/api/cmd", token, value === undefined ? { op: op } : { op: op, value: value }).catch(fail);
  }
  function fail(e) {
    if (e && e.status === 401) onStatus("unpaired");
    else toast(e && e.message ? e.message : "Couldn't reach Lyrenne");
  }

  var toastTimer;
  function toast(text) {
    var t = $("#toast");
    t.textContent = text;
    t.classList.add("on");
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { t.classList.remove("on"); }, 2200);
  }

  function onStatus(s) {
    var pill = $("#conn");
    pill.hidden = s === "live";
    pill.textContent = s === "connecting" ? "Connecting" : s === "off" ? "Remote is off" : "Reconnecting";
    var b = $("#blocker");
    if (s === "unpaired" || !token) {
      b.hidden = false;
      $("#bretry").hidden = !token;
      $("#btitle").textContent = "Not paired";
      $("#btext").textContent = token
        ? "This phone's pairing was reset. In Lyrenne on your PC, open Settings, Lyrenne Link, and scan the QR code again."
        : "Open Lyrenne on your PC, go to Settings, Lyrenne Link, turn on the phone remote and scan the QR code.";
    } else if (s === "off") {
      b.hidden = false;
      $("#bretry").hidden = false;
      $("#btitle").textContent = "Phone remote is off";
      $("#btext").textContent = "Turn it back on in Lyrenne's Settings, Lyrenne Link. This page reconnects by itself.";
    } else b.hidden = true;
  }
  $("#bretry").onclick = function () { live.status = "reconnecting"; $("#blocker").hidden = true; live.reconnect(0); };

  function setArt(url) {
    // Images cannot send a header; from the LAN the cover needs the pairing code in its URL.
    if (url && token) url += "&t=" + encodeURIComponent(token);
    var imgs = document.querySelectorAll(".art img");
    var on = imgs[0].classList.contains("on") ? 0 : 1, next = imgs[1 - on];
    if (!url) { imgs[0].classList.remove("on"); imgs[1].classList.remove("on"); return; }
    next.onload = function () { next.classList.add("on"); imgs[on].classList.remove("on"); };
    next.src = url;
    $("#mini img").src = url;
  }

  function onState(s) {
    var song = s.song;
    $("#title").textContent = song ? song.title : "Nothing playing";
    $("#artist").textContent = song ? song.artist : "Pick something in Search";
    $("#mini .mt").textContent = song ? song.title : "";
    $("#mini .ma").textContent = song ? song.artist : "";
    if ((song && song.id) !== songId) {
      songId = song ? song.id : null;
      setArt(song ? song.art : null);
      if (!lyrics || lyrics.songId !== songId) renderLyrics(null);
      document.title = song ? song.title + " · Lyrenne" : "Lyrenne Remote";
    }
    var rgb = Link.rgb(s.accent) || [163, 124, 67];
    document.documentElement.style.setProperty("--tint", rgb.join(", "));
    var playIcon = s.playing ? "pause" : "play";
    setIcon($("#toggle"), playIcon);
    setIcon($("#mtoggle"), playIcon);
    $("#toggle").setAttribute("aria-label", s.playing ? "Pause" : "Play");
    setIcon($("#like"), s.liked ? "heart" : "heartOutline");
    $("#like").classList.toggle("on", !!s.liked);
    $("#like").setAttribute("aria-label", s.liked ? "Remove from liked songs" : "Add to liked songs");
    $("#shuffle").classList.toggle("on", !!s.shuffle);
    setIcon($("#repeat"), s.repeat === "ONE" ? "repeatOne" : "repeat");
    $("#repeat").classList.toggle("on", s.repeat !== "OFF");
    if (!volDragging) setRange($("#volume"), Math.round((s.volume || 0) * 100));
    offset = s.lyricsOffsetMs || 0;
    $("#mini").hidden = view === "now" || !song;
  }

  function setRange(el, v) {
    el.value = v;
    el.style.setProperty("--v", ((v - el.min) / (el.max - el.min)) * 100 + "%");
  }

  // --- Controls ---
  $("#toggle").onclick = $("#mtoggle").onclick = function () {
    if (!live.state || !live.state.song) return;
    live.patch({ playing: !live.state.playing });
    cmd("toggle");
  };
  $("#next").onclick = function () { cmd("next"); };
  $("#prev").onclick = function () { cmd("prev"); };
  $("#shuffle").onclick = function () { if (live.state) live.patch({ shuffle: !live.state.shuffle }); cmd("shuffle"); };
  $("#repeat").onclick = function () {
    var order = { OFF: "ALL", ALL: "ONE", ONE: "OFF" };
    var next = order[(live.state && live.state.repeat) || "OFF"];
    if (live.state) live.patch({ repeat: next });
    cmd("repeat", next);
  };
  $("#like").onclick = function () {
    if (!live.state || !live.state.song) return;
    var want = !live.state.liked;
    live.patch({ liked: want });
    cmd("like", want);
    toast(want ? "Added to liked songs" : "Removed from liked songs");
  };

  var seek = $("#seek");
  seek.addEventListener("input", function () {
    seeking = true;
    var d = (live.state && live.state.durationMs) || 0;
    setRange(seek, seek.value);
    $("#cur").textContent = Link.fmt((seek.value / 1000) * d);
  });
  seek.addEventListener("change", function () {
    var d = (live.state && live.state.durationMs) || 0;
    var ms = Math.round((seek.value / 1000) * d);
    if (d > 0) { live.patch({ positionMs: ms }); cmd("seek", ms); }
    seeking = false;
  });

  var vol = $("#volume"), volSent = 0, volTimer;
  vol.addEventListener("input", function () {
    volDragging = true;
    setRange(vol, vol.value);
    // At most about eight updates a second while dragging, then the final value.
    var now = Date.now();
    clearTimeout(volTimer);
    if (now - volSent > 120) { volSent = now; cmd("volume", vol.value / 100); }
    else volTimer = setTimeout(function () { volSent = Date.now(); cmd("volume", vol.value / 100); }, 130);
  });
  vol.addEventListener("change", function () {
    clearTimeout(volTimer);
    cmd("volume", vol.value / 100);
    setTimeout(function () { volDragging = false; }, 600);
  });

  // --- Tabs ---
  document.querySelectorAll("nav button").forEach(function (b) {
    b.onclick = function () { show(b.dataset.v); };
  });
  $("#mini").onclick = function (e) { if (!e.target.closest("#mtoggle")) show("now"); };
  function show(v) {
    view = v;
    document.querySelectorAll("nav button").forEach(function (b) { b.classList.toggle("on", b.dataset.v === v); });
    document.querySelectorAll(".view").forEach(function (el) { el.classList.toggle("on", el.id === "v-" + v); });
    $("#mini").hidden = v === "now" || !(live.state && live.state.song);
    if (v === "lyrics") { curLine = -2; userScrolledAt = 0; }
    if (v === "queue") scrollQueueToCurrent();
    if (v === "search") setTimeout(function () { if (!$("#results").children.length) $("#sq").focus(); }, 220);
  }

  // --- Lyrics ---
  function onLyrics(l) {
    lyrics = l;
    if (l.songId === songId) renderLyrics(l);
  }

  function renderLyrics(l) {
    var box = $("#lyrics");
    box.innerHTML = "";
    lines = []; lineEls = []; curLine = -2;
    if (!l || l.loading) { box.innerHTML = '<div class="empty">' + (songId ? "Looking for lyrics" : "Nothing playing") + "</div>"; return; }
    if (l.synced && l.lines) {
      lines = l.lines.filter(function (x) { return x.text.trim().length; });
      lineEls = lines.map(function (line) {
        var el = document.createElement("div");
        el.className = "l";
        if (line.w && line.w.length) {
          line.w.forEach(function (w, i) {
            var sp = document.createElement("span");
            sp.className = "w";
            sp.textContent = w[2] + (i < line.w.length - 1 && !/\s$/.test(w[2]) ? " " : "");
            sp.dataset.s = w[0]; sp.dataset.e = w[1];
            el.appendChild(sp);
          });
        } else el.textContent = line.text;
        (line.bg || []).forEach(function (b) {
          var bg = document.createElement("span");
          bg.className = "bg";
          bg.textContent = b.text;
          el.appendChild(bg);
        });
        // Tap a line to jump there.
        el.onclick = function () {
          var ms = Math.max(0, line.t - offset);
          live.patch({ positionMs: ms });
          cmd("seek", ms);
        };
        box.appendChild(el);
        return el;
      });
    } else if (l.plain) {
      var p = document.createElement("div");
      p.className = "plain";
      p.textContent = l.plain;
      box.appendChild(p);
    } else {
      box.innerHTML = '<div class="empty">No lyrics for this song</div>';
      return;
    }
    if (l.source) {
      var src = document.createElement("div");
      src.className = "src";
      src.textContent = "Lyrics from " + l.source;
      box.appendChild(src);
    }
  }
  $("#v-lyrics").addEventListener("touchmove", function () { userScrolledAt = Date.now(); }, { passive: true });
  $("#v-lyrics").addEventListener("wheel", function () { userScrolledAt = Date.now(); }, { passive: true });

  function drawLyrics(p) {
    if (!lines.length) return;
    var i = Link.lineAt(lines, p);
    if (i !== curLine) {
      lineEls.forEach(function (el, k) { el.classList.toggle("past", k < i); });
      if (lineEls[curLine]) {
        lineEls[curLine].classList.remove("cur");
        lineEls[curLine].querySelectorAll(".w").forEach(function (w) { w.style.setProperty("--p", "1"); });
      }
      curLine = i;
      if (lineEls[i]) {
        lineEls[i].classList.add("cur");
        // Follow the song, unless the user scrolled away in the last few seconds.
        if (view === "lyrics" && Date.now() - userScrolledAt > 4000) {
          var v = $("#v-lyrics");
          v.scrollTo({ top: lineEls[i].offsetTop - v.clientHeight * 0.38, behavior: "smooth" });
        }
      }
    }
    if (i >= 0 && lines[i].w && view === "lyrics") {
      var spans = lineEls[i].querySelectorAll(".w");
      for (var k = 0; k < spans.length; k++) {
        spans[k].style.setProperty("--p", Link.progress(+spans[k].dataset.s, +spans[k].dataset.e, p).toFixed(3));
      }
    }
  }

  // --- Queue ---
  function row(song, opts) {
    var r = document.createElement("div");
    r.className = "row" + (opts.cur ? " cur" : "");
    var img = document.createElement("img");
    img.loading = "lazy";
    img.alt = "";
    if (song.thumb) img.src = song.thumb;
    var t = document.createElement("div");
    t.className = "rtext";
    var a = document.createElement("div"); a.className = "rt"; a.textContent = song.title;
    var b = document.createElement("div"); b.className = "ra"; b.textContent = song.artist + (song.durationMs ? "  ·  " + Link.fmt(song.durationMs) : "");
    t.appendChild(a); t.appendChild(b);
    r.appendChild(img); r.appendChild(t);
    (opts.actions || []).forEach(function (act) {
      var btn = document.createElement("button");
      btn.className = "icon";
      btn.setAttribute("aria-label", act.label);
      btn.innerHTML = svg(act.icon);
      btn.onclick = function (e) { e.stopPropagation(); act.run(); };
      r.appendChild(btn);
    });
    r.onclick = opts.onTap;
    return r;
  }

  function onQueue(q) {
    queue = q;
    var box = $("#queue");
    box.innerHTML = "";
    if (!q.items.length) { box.innerHTML = '<div class="empty">The queue is empty</div>'; return; }
    q.items.forEach(function (song, i) {
      box.appendChild(row(song, {
        cur: i === q.index,
        onTap: function () { if (i !== queue.index) { cmd("jump", i); toast("Playing " + song.title); } }
      }));
    });
    if (view === "queue") scrollQueueToCurrent();
  }
  function scrollQueueToCurrent() {
    var cur = $("#queue .row.cur");
    if (cur) $("#v-queue").scrollTo({ top: cur.offsetTop - 80 });
  }

  // --- Search ---
  var searchSeq = 0;
  $("#sform").onsubmit = function (e) {
    e.preventDefault();
    var text = $("#sq").value.trim();
    if (!text) return;
    $("#sq").blur();
    var box = $("#results");
    box.innerHTML = '<div class="empty">Searching</div>';
    var seq = ++searchSeq;
    Link.api("/api/search?q=" + encodeURIComponent(text), token).then(function (r) {
      if (seq !== searchSeq) return;
      box.innerHTML = "";
      if (!r.items.length) { box.innerHTML = '<div class="empty">Nothing found</div>'; return; }
      r.items.forEach(function (song) {
        box.appendChild(row(song, {
          onTap: function () {
            Link.api("/api/play", token, { song: song }).then(function () { toast("Playing " + song.title); }).catch(fail);
          },
          actions: [
            { icon: "playNext", label: "Play next", run: function () { cmd("playNext", song).then(function () { toast("Playing next"); }); } },
            { icon: "addQueue", label: "Add to queue", run: function () { cmd("enqueue", song).then(function () { toast("Added to queue"); }); } }
          ]
        }));
      });
    }).catch(function (e) {
      if (seq !== searchSeq) return;
      box.innerHTML = '<div class="empty">Search didn’t work. Check that Lyrenne is online.</div>';
      fail(e);
    });
  };

  // --- Frame loop: progress and lyrics, from the interpolated position ---
  function frame() {
    var s = live.state;
    if (s && s.song) {
      var p = live.position(), d = s.durationMs || 0;
      if (!seeking) {
        setRange(seek, d > 0 ? Math.round((p / d) * 1000) : 0);
        $("#cur").textContent = Link.fmt(p);
      }
      $("#dur").textContent = Link.fmt(d);
      $("#mini .mbar div").style.width = (d > 0 ? (p / d) * 100 : 0) + "%";
      drawLyrics(p + offset);
    }
    requestAnimationFrame(frame);
  }

  if (!token) onStatus("unpaired");
  else live.connect();
  renderLyrics(null);
  requestAnimationFrame(frame);
})();
