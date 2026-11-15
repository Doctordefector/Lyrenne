/* Lyrenne Link stream overlay. Query: layout=card|lyrics|ticker, pos=tl|tc|tr|bl|bc|br,
   scale=0.5..2, accent=auto|rrggbb, font=px (at 1080p), lyrics=0 (card without the lyric line),
   preview=1 (show a sample while nothing plays, for setting up). */
"use strict";
(function () {
  var q = new URLSearchParams(location.search);
  var layout = ["card", "lyrics", "ticker"].indexOf(q.get("layout")) >= 0 ? q.get("layout") : "card";
  var pos = ["tl", "tc", "tr", "bl", "bc", "br"].indexOf(q.get("pos")) >= 0 ? q.get("pos") : (layout === "lyrics" ? "bc" : "bl");
  if (layout === "ticker") pos = pos.charAt(0) === "t" ? "tl" : "bl";
  var scale = Math.min(2, Math.max(0.5, parseFloat(q.get("scale")) || 1));
  var accentParam = (q.get("accent") || "auto").replace(/^#/, "");
  var fixedAccent = /^[0-9a-f]{6}$/i.test(accentParam) ? "#" + accentParam : null;
  var font = Math.min(120, Math.max(20, parseInt(q.get("font"), 10) || 44));
  var preview = q.get("preview") === "1";

  var $ = function (s) { return document.querySelector(s); };
  var anchor = $("#anchor");
  anchor.className = "hidden pos-" + pos + " layout-" + layout + (q.get("lyrics") === "0" ? " nolyrics" : "");
  document.documentElement.style.setProperty("--font", font + "px");

  function fit() {
    var k = (window.innerHeight / 1080) * scale;
    document.documentElement.style.setProperty("--k", k);
    $("#ticker").style.width = Math.max(400, window.innerWidth / k - 96) + "px";
  }
  window.addEventListener("resize", fit);
  fit();

  var lyrics = null;      // last lyrics event
  var lines = [];         // synced lines
  var offset = 0;         // per-song timing offset, positive = lyrics earlier
  var current = -2;       // index of the highlighted line
  var songId = null;
  var lineEls = [];

  var SAMPLE = {
    song: { id: "sample", title: "Your song title here", artist: "Artist name", art: null },
    playing: true, positionMs: 42000, durationMs: 210000, accent: "#e3c089", lyricsOffsetMs: 0
  };

  // On this PC the overlay needs no code. A second streaming PC on the LAN (remote switched on)
  // can add #t=<pairing code> to the URL.
  var tok = (/[?&#]t=([A-Za-z0-9_-]{20,})/.exec(location.search + location.hash) || [])[1] || null;
  function artUrl(u) { return u && tok ? u + "&t=" + encodeURIComponent(tok) : u; }

  var live = new Link.Live({
    token: tok,
    onState: onState,
    onLyrics: onLyrics,
    onStatus: function () {}
  });

  function setArt(imgs, url) {
    // Two images cross-fade: the new cover loads behind the old one and fades in over it.
    var on = imgs[0].classList.contains("on") ? 0 : 1;
    var next = imgs[1 - on];
    if (!url) { imgs[0].classList.remove("on"); imgs[1].classList.remove("on"); return; }
    next.onload = function () { next.classList.add("on"); imgs[on].classList.remove("on"); };
    next.src = url;
  }

  function onState(s) {
    if (!s.song && preview) s = SAMPLE;
    var song = s.song;
    anchor.classList.toggle("hidden", !song);
    anchor.classList.toggle("paused", !s.playing);
    document.documentElement.style.setProperty("--accent", fixedAccent || s.accent || "#e3c089");
    offset = s.lyricsOffsetMs || 0;
    if (!song) { songId = null; return; }
    if (song.id !== songId) {
      var first = songId === null;
      songId = song.id;
      // Slide the old text out, swap it while invisible, and slide the new one in.
      anchor.classList.add("swap");
      setTimeout(function () {
        $("#card .title").textContent = song.title;
        $("#card .artist").textContent = song.artist;
        $("#lyrics .ctext").textContent = song.title + "  ·  " + song.artist;
        var tt = $("#ticker .ttext");
        tt.textContent = song.title + "  ·  " + song.artist;
        tt.classList.remove("marquee");
        requestAnimationFrame(function () {
          if (tt.scrollWidth > tt.parentNode.clientWidth) tt.classList.add("marquee");
          anchor.classList.remove("swap");
        });
      }, first ? 0 : 280);
      setArt(document.querySelectorAll("#card .art img"), artUrl(song.art));
      $("#lyrics .caption img").src = artUrl(song.art) || "";
      $("#ticker > img").src = artUrl(song.art) || "";
      if (song === SAMPLE.song) { lyrics = SAMPLE_LYRICS; setLines(SAMPLE_LYRICS); }
      else if (!lyrics || lyrics.songId !== song.id) setLines(null);
    }
  }

  function onLyrics(l) {
    lyrics = l;
    if (songId && l.songId === songId) setLines(l);
  }

  function wordSpans(el, words) {
    words.forEach(function (w, i) {
      var span = document.createElement("span");
      span.className = "w";
      span.textContent = w[2] + (i < words.length - 1 && !/\s$/.test(w[2]) ? " " : "");
      span.dataset.s = w[0];
      span.dataset.e = w[1];
      el.appendChild(span);
    });
  }

  function setLines(l) {
    lines = (l && l.synced && l.lines) ? l.lines.filter(function (x) { return x.text.trim().length; }) : [];
    current = -2;
    var box = $("#lyrics .lines");
    box.innerHTML = "";
    box.style.transform = "translateY(0)";
    lineEls = lines.map(function (line) {
      var el = document.createElement("div");
      el.className = "line";
      if (line.w && line.w.length) { el.classList.add("hasw"); wordSpans(el, line.w); }
      else el.textContent = line.text;
      (line.bg || []).forEach(function (b) {
        var bg = document.createElement("span");
        bg.className = "bg";
        bg.textContent = b.text;
        el.appendChild(bg);
      });
      box.appendChild(el);
      return el;
    });
    if (!lines.length && l && l.plain) {
      var p = document.createElement("div");
      p.className = "line plain";
      p.textContent = "Lyrics for this song aren't timed.";
      box.appendChild(p);
    }
    $("#card .cardline").classList.remove("on");
    $("#ticker .tline").textContent = "";
  }

  function frame() {
    var s = live.state && (live.state.song || !preview) ? live.state : (preview ? SAMPLE : null);
    if (s && s.song) {
      var p = s === SAMPLE ? SAMPLE.positionMs : live.position();
      var d = s.durationMs || 0;
      var pct = d > 0 ? Math.min(100, (p / d) * 100) : 0;
      $("#card .fill").style.width = pct + "%";
      $("#ticker .fill").style.width = pct + "%";
      $("#card .cur").textContent = Link.fmt(p);
      $("#card .dur").textContent = Link.fmt(d);
      drawLyrics(p + offset);
    }
    requestAnimationFrame(frame);
  }

  function drawLyrics(p) {
    if (!lines.length) return;
    var i = Link.lineAt(lines, p);
    if (i !== current) {
      if (lineEls[current]) {
        lineEls[current].classList.remove("cur", "words");
        lineEls[current].querySelectorAll(".w").forEach(function (w) { w.style.setProperty("--p", "0"); });
      }
      current = i;
      var el = lineEls[i];
      if (el) {
        el.classList.add("cur");
        if (el.classList.contains("hasw")) el.classList.add("words");
        // Keep the current line in the middle of the three-line window.
        var win = $("#lyrics .window");
        var y = el.offsetTop - (win.clientHeight - el.offsetHeight) / 2;
        $("#lyrics .lines").style.transform = "translateY(" + (-y) + "px)";
      }
      var text = i >= 0 ? lines[i].text : "";
      var cl = $("#card .cardline");
      if (text) {
        cl.innerHTML = "";
        if (lines[i].w && lines[i].w.length) wordSpans(cl, lines[i].w); else cl.textContent = text;
        cl.classList.add("on");
      } else cl.classList.remove("on");
      $("#ticker .tline").textContent = text;
    }
    // Word by word fill, on whichever copies of the current line are showing.
    if (i >= 0 && lines[i].w) {
      var spans = document.querySelectorAll(".line.cur .w, #card .cardline .w");
      for (var k = 0; k < spans.length; k++) {
        var sp = spans[k];
        sp.style.setProperty("--p", Link.progress(+sp.dataset.s, +sp.dataset.e, p).toFixed(3));
      }
    }
  }

  var SAMPLE_LYRICS = { songId: "sample", synced: true, lines: [
    { t: 20000, text: "This is how your lyrics look" },
    { t: 40000, text: "line by line, as the song plays", w: [[40000, 41000, "line"], [41000, 41500, "by"], [41500, 43000, "line,"], [43000, 43600, "as"], [43600, 44000, "the"], [44000, 44600, "song"], [44600, 45400, "plays"]] },
    { t: 46000, text: "with every word lighting up" }
  ] };

  live.connect();
  if (preview) { onState(SAMPLE); lyrics = SAMPLE_LYRICS; setLines(SAMPLE_LYRICS); }
  requestAnimationFrame(frame);
})();
