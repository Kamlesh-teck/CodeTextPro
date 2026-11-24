(function () {
  const $ = s => document.querySelector(s);
  const toast = m => window.ctpToast && window.ctpToast(m);

  /* ---------- फाइल प्रकार ---------- */
  const T = {
    txt: ['📝', 'Text'], md: ['📘', 'Markdown'], html: ['🌐', 'HTML'], htm: ['🌐', 'HTML'],
    css: ['🎨', 'CSS'], js: ['🟨', 'JavaScript'], json: ['🧾', 'JSON'], xml: ['📰', 'XML'],
    java: ['☕', 'Java'], kt: ['🟣', 'Kotlin'], kts: ['🟣', 'Kotlin'], py: ['🐍', 'Python'],
    c: ['🔧', 'C'], cpp: ['🔧', 'C++'], h: ['🔧', 'C Header'], sql: ['🗄️', 'SQL'],
    php: ['🐘', 'PHP'], pdf: ['📕', 'PDF'],
    png: ['🖼️', 'Image'], jpg: ['🖼️', 'Image'], jpeg: ['🖼️', 'Image'], gif: ['🖼️', 'Image'],
    webp: ['🖼️', 'Image'], mp3: ['🎵', 'Audio'], mp4: ['🎬', 'Video'],
    zip: ['🗜️', 'Archive'], apk: ['📦', 'APK']
  };
  const BIN = new Set(['png', 'jpg', 'jpeg', 'gif', 'webp', 'mp3', 'mp4', 'zip', 'apk', 'mkv', 'avi', 'rar', '7z']);

  function ext(n) { const i = n.lastIndexOf('.'); return i < 0 ? '' : n.slice(i + 1).toLowerCase(); }
  function info(n, dir) {
    if (dir) return ['📁', 'Folder'];
    const e = ext(n);
    return T[e] || ['📄', e ? e.toUpperCase() : 'File'];
  }
  function size(n) {
    if (n < 1024) return n + ' B';
    if (n < 1048576) return (n / 1024).toFixed(1) + ' KB';
    return (n / 1048576).toFixed(1) + ' MB';
  }
  function when(t) { return t ? new Date(t).toLocaleDateString() : ''; }

  /* ---------- Dialog ---------- */
  let closeDlg = null;
  function dlg(msg, o) {
    return new Promise(res => {
      const ov = document.createElement('div'); ov.className = 'ov';
      const bx = document.createElement('div'); bx.className = 'bx';
      const p = document.createElement('p'); p.textContent = msg; bx.appendChild(p);
      let inp = null;
      if (o.input !== undefined) {
        inp = document.createElement('input'); inp.value = o.input; bx.appendChild(inp);
      }
      const bb = document.createElement('div');
      bb.className = 'bb' + (o.col ? ' col' : '');
      const done = i => { ov.remove(); closeDlg = null; res({ i: i, v: inp ? inp.value : '' }); };
      o.btns.forEach((l, i) => {
        const b = document.createElement('button');
        b.textContent = l;
        if (i === o.danger) b.className = 'danger';
        b.onclick = () => done(i);
        bb.appendChild(b);
      });
      closeDlg = () => done(-1);
      bx.appendChild(bb); ov.appendChild(bx); document.body.appendChild(ov);
      if (inp) { inp.focus(); inp.select(); }
    });
  }

  /* ---------- पंक्ति बनाना ---------- */
  function row(ic, name, sub, tap, menu) {
    const d = document.createElement('div'); d.className = 'frow';
    const i = document.createElement('span'); i.className = 'fi'; i.textContent = ic;
    const m = document.createElement('div'); m.className = 'fm';
    const b = document.createElement('b'); b.textContent = name;
    const s = document.createElement('small'); s.textContent = sub;
    m.appendChild(b); m.appendChild(s);
    d.appendChild(i); d.appendChild(m);
    d.onclick = tap;
    if (menu) {
      const x = document.createElement('button');
      x.className = 'fx'; x.textContent = '⋮';
      x.onclick = e => { e.stopPropagation(); menu(); };
      d.appendChild(x);
    }
    return d;
  }

  function empty(box, msg, withPick) {
    const d = document.createElement('div'); d.className = 'fempty';
    d.textContent = msg;
    if (withPick) {
      const b = document.createElement('button');
      b.className = 'pbtn'; b.textContent = '📂 फोल्डर चुनें';
      b.onclick = () => Android.fmPick();
      d.appendChild(b);
    }
    box.appendChild(d);
  }

  /* ---------- फोल्डर ---------- */
  let depth = 0, tab = 'folder';

  function openItem(it) {
    if (it.dir) { Android.fmEnter(it.id); return; }
    if (BIN.has(ext(it.name))) { toast('यह फाइल ऐप में नहीं खुल सकती'); return; }
    Android.fmOpen(it.id);
  }

  async function menu(it) {
    const btns = it.dir ? ['खोलें', 'Rename', 'Delete', 'रद्द'] : ['खोलें', 'Rename', 'Share', 'Delete', 'रद्द'];
    const r = await dlg(it.name, { btns: btns, col: true });
    const k = btns[r.i];
    if (k === 'खोलें') openItem(it);
    else if (k === 'Rename') {
      const n = await dlg('नया नाम', { input: it.name, btns: ['रद्द', 'बदलें'] });
      if (n.i === 1 && n.v.trim()) Android.fmRename(it.id, n.v.trim());
    } else if (k === 'Share') Android.fmShare(it.id);
    else if (k === 'Delete') {
      const msg = '"' + it.name + '" हमेशा के लिए हटाएँ?' + (it.dir ? ' अंदर की सब फाइलें भी हट जाएँगी।' : '');
      const c = await dlg(msg, { btns: ['रद्द', 'हटाएँ'], danger: 1 });
      if (c.i === 1) Android.fmDelete(it.id);
    }
  }

  window.onFolder = function (s) {
    const o = JSON.parse(s);
    const L = $('#flist'); L.innerHTML = '';
    depth = o.depth || 0;
    $('#fpath').textContent = o.has ? '📍 ' + o.path : '';
    if (!o.has) {
      empty(L, 'कोई फोल्डर चुना नहीं है। Documents में अपना एक फोल्डर बनाकर उसे चुनें (Android 11+ में पूरा Storage या Download चुना नहीं जा सकता)।', true);
      return;
    }
    if (!o.items.length) { empty(L, 'यह फोल्डर खाली है।', false); return; }
    o.items.forEach(it => {
      const t = info(it.name, it.dir);
      const sub = it.dir ? 'Folder' : t[1] + ' · ' + size(it.size) + ' · ' + when(it.time);
      L.appendChild(row(t[0], it.name, sub, () => openItem(it), () => menu(it)));
    });
    if (o.more) empty(L, 'सिर्फ पहली 300 एंट्री दिखाई गई हैं।', false);
  };

  /* ---------- Recent ---------- */
  function renderRecent() {
    let a = [];
    try { a = JSON.parse(Android.recentList()); } catch (e) {}
    const R = $('#frecent'); R.innerHTML = '';
    if (!a.length) { empty(R, 'अभी कोई Recent फाइल नहीं है।', false); return; }
    a.forEach(r => {
      const t = info(r.name, false);
      R.appendChild(row(t[0], r.name, t[1] + ' · ' + when(r.time), () => Android.openRecent(r.id), null));
    });
  }

  function setTab(t) {
    tab = t;
    $('#tfold').classList.toggle('on', t === 'folder');
    $('#trec').classList.toggle('on', t === 'recent');
    $('#ffolder').hidden = t !== 'folder';
    $('#frecent').hidden = t !== 'recent';
    if (t === 'recent') renderRecent(); else Android.fmStart();
  }

  /* ---------- बटन ---------- */
  const F = {
    tabfolder() { setTab('folder'); },
    tabrecent() { setTab('recent'); },
    up() { if (depth > 0) Android.fmUp(); else toast('यह सबसे ऊपरी फोल्डर है'); },
    async nfile() {
      const r = await dlg('नई फाइल का नाम', { input: 'untitled.txt', btns: ['रद्द', 'बनाएँ'] });
      if (r.i === 1 && r.v.trim()) Android.fmNew(r.v.trim(), false);
    },
    async nfolder() {
      const r = await dlg('नए फोल्डर का नाम', { input: '', btns: ['रद्द', 'बनाएँ'] });
      if (r.i === 1 && r.v.trim()) Android.fmNew(r.v.trim(), true);
    },
    import() { Android.fmImport(); },
    pick() { Android.fmPick(); },
    refresh() { Android.fmStart(); },
    clrrecent() { Android.clearRecent(); toast('Recent सूची साफ़ हो गई'); if (tab === 'recent') renderRecent(); }
  };

  document.addEventListener('click', e => {
    const b = e.target.closest('[data-f]');
    if (b && F[b.dataset.f]) { F[b.dataset.f](); return; }
    if (e.target.closest('[data-go="files"]')) setTimeout(() => setTab(tab), 0);
  });

  /* ---------- फाइल खुलने पर सही Editor चुनना ---------- */
  const prevOpened = window.onFileOpened;
  window.onFileOpened = function (s) {
    if (!$('#files').classList.contains('on')) { prevOpened(s); return; }
    const o = JSON.parse(s);
    if (CM.key(o.name) !== 'txt') $('#gocode').click();
    prevOpened(s);
  };

  /* ---------- Back ---------- */
  const prevBack = window.handleBack;
  window.handleBack = function () {
    if (closeDlg) { closeDlg(); return true; }
    if ($('#files').classList.contains('on') && tab === 'folder' && depth > 0) {
      Android.fmUp();
      return true;
    }
    return prevBack();
  };

  /* ---------- थीम (स्टेटस बार + बटन हाइलाइट) ---------- */
  function theme() {
    const dark = document.documentElement.dataset.theme !== 'light';
    try { Android.setTheme(dark); } catch (e) {}
    $('#bdark').classList.toggle('sel', dark);
    $('#blight').classList.toggle('sel', !dark);
  }
  new MutationObserver(theme).observe(document.documentElement, {
    attributes: true, attributeFilter: ['data-theme']
  });
  theme();
})();
