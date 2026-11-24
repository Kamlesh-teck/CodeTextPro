(function () {
  const $ = s => document.querySelector(s);
  const $$ = s => [...document.querySelectorAll(s)];
  const ta = $('#ed');

  let tabs = [], cur = null, uid = 0, scr = 'home';
  let histTimer, saveTimer, toastTimer, modalRes = null;

  function load(k) { try { return JSON.parse(localStorage.getItem(k)); } catch (e) { return null; } }
  function store(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch (e) {} }

  const S = Object.assign({ fs: 16, ff: 'monospace', al: 'left', theme: 'dark' }, load('ctp_set') || {});

  function applySet() {
    document.documentElement.dataset.theme = S.theme;
    ta.style.fontSize = S.fs + 'px';
    ta.style.fontFamily = S.ff;
    ta.style.textAlign = S.al;
    $('#ff').value = S.ff;
  }
  function saveSet() { applySet(); store('ctp_set', S); }

  function toast(m) {
    const t = $('#toast');
    t.textContent = m; t.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { t.hidden = true; }, 2200);
  }

  /* ---------- Modal ---------- */
  function ask(msg, btns) {
    return new Promise(res => {
      $('#mmsg').textContent = msg;
      const b = $('#mbtns'); b.innerHTML = '';
      btns.forEach((l, i) => {
        const x = document.createElement('button');
        x.textContent = l;
        if (i === btns.length - 1) x.className = 'danger';
        x.onclick = () => closeModal(i);
        b.appendChild(x);
      });
      $('#modal').hidden = false;
      modalRes = res;
    });
  }
  function closeModal(i) {
    $('#modal').hidden = true;
    if (modalRes) { const r = modalRes; modalRes = null; r(i); }
  }

  /* ---------- Tabs ---------- */
  const dirty = t => t.content !== t.saved;

  function mk(name, content, id, hasFile) {
    return {
      id: id || 't' + Date.now() + '_' + (++uid),
      name: name, content: content,
      saved: hasFile ? content : '',
      hasFile: !!hasFile,
      hist: [content], hi: 0, sel: 0, pend: null
    };
  }

  function addTab(t) { tabs.push(t); switchTab(t.id); }

  function switchTab(id) {
    if (cur && tabs.includes(cur)) { cur.content = ta.value; cur.sel = ta.selectionStart; }
    cur = tabs.find(t => t.id === id);
    ta.value = cur.content;
    try { ta.setSelectionRange(cur.sel, cur.sel); } catch (e) {}
    ta.scrollTop = 0;
    renderTabs(); stats(); persist();
  }

  function renderTabs() {
    const box = $('#tabs'); box.innerHTML = '';
    tabs.forEach(t => {
      const d = document.createElement('div');
      d.className = 'tab' + (t === cur ? ' on' : '');
      const s = document.createElement('span');
      s.textContent = (dirty(t) ? '● ' : '') + t.name;
      const x = document.createElement('i');
      x.textContent = '×';
      x.onclick = e => { e.stopPropagation(); closeTab(t.id); };
      d.onclick = () => { if (t !== cur) switchTab(t.id); };
      d.appendChild(s); d.appendChild(x);
      box.appendChild(d);
      if (t === cur) setTimeout(() => d.scrollIntoView({ inline: 'center', block: 'nearest' }), 0);
    });
    const p = document.createElement('div');
    p.className = 'tab plus'; p.textContent = '+';
    p.onclick = () => addTab(mk('untitled.txt', ''));
    box.appendChild(p);
  }

  async function closeTab(id) {
    const t = tabs.find(x => x.id === id);
    if (!t) return;
    if (t === cur) t.content = ta.value;
    if (dirty(t)) {
      const i = await ask('"' + t.name + '" में बिना सेव किए बदलाव हैं।', ['रद्द', 'बिना सेव बंद करें']);
      if (i !== 1) return;
    }
    const k = tabs.indexOf(t);
    tabs.splice(k, 1);
    if (!tabs.length) addTab(mk('untitled.txt', ''));
    else if (t === cur) switchTab(tabs[Math.max(0, k - 1)].id);
    else renderTabs();
    persist();
  }

  /* ---------- History (Undo / Redo) ---------- */
  function pushHist(t) {
    if (t.hist[t.hi] === t.content) return;
    t.hist = t.hist.slice(0, t.hi + 1);
    t.hist.push(t.content);
    const cap = t.content.length > 200000 ? 10 : 100;
    while (t.hist.length > cap) t.hist.shift();
    t.hi = t.hist.length - 1;
  }
  function setVal(v) { ta.value = v; ta.dispatchEvent(new Event('input')); }
  function undo() {
    clearTimeout(histTimer); cur.content = ta.value; pushHist(cur);
    if (cur.hi > 0) { cur.hi--; setVal(cur.hist[cur.hi]); } else toast('और Undo नहीं');
  }
  function redo() {
    clearTimeout(histTimer);
    if (cur.hi < cur.hist.length - 1) { cur.hi++; setVal(cur.hist[cur.hi]); } else toast('और Redo नहीं');
  }

  /* ---------- Stats / Persist ---------- */
  function stats() {
    const v = ta.value, tr = v.trim();
    const w = tr ? tr.split(/\s+/).length : 0;
    const p = v.split(/\n\s*\n/).filter(x => x.trim()).length;
    $('#stats').textContent = 'शब्द ' + w + ' · अक्षर ' + v.length + ' · लाइन ' + v.split('\n').length + ' · पैरा ' + p;
  }

  function persistNow() {
    if (cur) cur.content = ta.value;
    store('ctp_tabs', {
      act: Math.max(0, tabs.indexOf(cur)),
      tabs: tabs.map(t => ({ name: t.name, content: t.content, saved: t.saved }))
    });
  }
  function persist() { clearTimeout(saveTimer); saveTimer = setTimeout(persistNow, 800); }

  ta.addEventListener('input', () => {
    const was = dirty(cur);
    cur.content = ta.value;
    if (was !== dirty(cur)) renderTabs();
    stats();
    clearTimeout(histTimer);
    histTimer = setTimeout(() => pushHist(cur), 500);
    persist();
  });
  document.addEventListener('visibilitychange', () => { if (document.hidden) persistNow(); });
  window.addEventListener('pagehide', persistNow);

  /* ---------- Save / Open ---------- */
  function doSave(as) {
    cur.content = ta.value;
    cur.pend = cur.content;
    if (cur.hasFile && !as) Android.save(cur.id, cur.content);
    else Android.saveAs(cur.id, cur.name, cur.content);
  }

  window.onSaved = function (s) {
    const o = JSON.parse(s);
    const t = tabs.find(x => x.id === o.id);
    if (!t) return;
    t.name = o.name; t.hasFile = true;
    t.saved = t.pend != null ? t.pend : t.content;
    renderTabs(); persist(); toast('सेव हो गया ✓');
  };
  window.onFileOpened = function (s) {
    const o = JSON.parse(s);
    if (tabs.length === 1 && cur && !cur.hasFile && ta.value === '' && cur.saved === '') tabs = [];
    addTab(mk(o.name, o.content, o.id, true));
    show('text');
  };
  window.onError = function (m) { toast(m); };

  /* ---------- Find / Replace ---------- */
  function hay() {
    const cs = $('#cs').checked, q = $('#fq').value;
    return [cs ? ta.value : ta.value.toLowerCase(), cs ? q : q.toLowerCase()];
  }
  function count() {
    const [h, q] = hay();
    if (!q) { $('#fcount').textContent = ''; return; }
    let n = 0, i = 0;
    while ((i = h.indexOf(q, i)) >= 0) { n++; i += q.length; }
    $('#fcount').textContent = n + ' मिले';
  }
  function find(dir) {
    const [h, q] = hay();
    if (!q) return false;
    let i;
    if (dir > 0) {
      i = h.indexOf(q, ta.selectionEnd);
      if (i < 0) i = h.indexOf(q);
    } else {
      const s = ta.selectionStart;
      i = s > 0 ? h.lastIndexOf(q, s - 1) : -1;
      if (i < 0) i = h.lastIndexOf(q);
    }
    if (i < 0) { toast('नहीं मिला'); return false; }
    ta.focus();
    ta.setSelectionRange(i, i + q.length);
    return true;
  }
  function replaceOne() {
    const [, q] = hay();
    if (!q) return;
    const s = ta.selectionStart, e = ta.selectionEnd;
    const sel = ta.value.slice(s, e);
    const match = $('#cs').checked ? sel === $('#fq').value : sel.toLowerCase() === q;
    if (match) {
      ta.setRangeText($('#rq').value, s, e, 'end');
      ta.dispatchEvent(new Event('input'));
    }
    find(1); count();
  }
  function replaceAll() {
    const f = $('#fq').value;
    if (!f) return;
    const re = new RegExp(f.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), $('#cs').checked ? 'g' : 'gi');
    let n = 0;
    const rep = $('#rq').value;
    const out = ta.value.replace(re, () => { n++; return rep; });
    if (!n) { toast('नहीं मिला'); return; }
    clearTimeout(histTimer); cur.content = ta.value; pushHist(cur);
    setVal(out);
    clearTimeout(histTimer); pushHist(cur);
    count(); toast(n + ' जगह बदला');
  }
  function toggleFind() {
    const fb = $('#findbar');
    fb.hidden = !fb.hidden;
    if (!fb.hidden) { $('#fq').focus(); count(); }
  }
  $('#fq').addEventListener('input', count);
  $('#cs').addEventListener('change', count);

  /* ---------- Screens ---------- */
  function show(n) {
    scr = n;
    $$('.screen').forEach(s => s.classList.toggle('on', s.id === n));
    $$('nav button').forEach(b => b.classList.toggle('on', b.dataset.go === n));
  }

  /* ---------- Actions ---------- */
  const A = {
    new() { addTab(mk('untitled.txt', '')); show('text'); },
    open() { Android.openFile(); },
    save() { doSave(false); },
    saveas() { doSave(true); },
    undo: undo, redo: redo,
    find: toggleFind,
    fnext() { find(1); },
    fprev() { find(-1); },
    rep: replaceOne,
    repall: replaceAll,
    all() { ta.focus(); ta.select(); },
    copy() { ta.focus(); document.execCommand('copy'); toast('कॉपी हुआ'); },
    cut() { ta.focus(); document.execCommand('cut'); },
    paste() {
      navigator.clipboard.readText()
        .then(x => { ta.focus(); document.execCommand('insertText', false, x); })
        .catch(() => toast('पेस्ट के लिए टेक्स्ट को देर तक दबाएँ'));
    },
    fminus() { S.fs = Math.max(10, S.fs - 2); saveSet(); },
    fplus() { S.fs = Math.min(40, S.fs + 2); saveSet(); },
    align() {
      const o = ['left', 'center', 'right'];
      S.al = o[(o.indexOf(S.al) + 1) % 3]; saveSet();
    },
    dark() { S.theme = 'dark'; saveSet(); },
    light() { S.theme = 'light'; saveSet(); },
    soon() { toast('यह फीचर आगे के भाग में आएगा'); }
  };

  document.addEventListener('click', e => {
    const b = e.target.closest('[data-act]');
    if (b && A[b.dataset.act]) { A[b.dataset.act](); return; }
    const g = e.target.closest('[data-go]');
    if (g) show(g.dataset.go);
  });
  $('#ff').addEventListener('change', e => { S.ff = e.target.value; saveSet(); });

  /* ---------- Android Back ---------- */
  window.handleBack = function () {
    if (!$('#modal').hidden) { closeModal(-1); return true; }
    if (!$('#findbar').hidden) { $('#findbar').hidden = true; return true; }
    if (scr !== 'home') { show('home'); return true; }
    if (tabs.some(dirty)) {
      ask('कुछ फाइलें डिस्क पर सेव नहीं हैं। बाहर निकलें? (ड्राफ्ट ऐप में सुरक्षित रहेंगे)', ['रुकें', 'बाहर निकलें'])
        .then(i => { if (i === 1) Android.exitApp(); });
      return true;
    }
    return false;
  };

  /* ---------- Start ---------- */
  applySet();
  $('#ver').textContent = 'CodeText Pro v' + Android.appVersion();
  const d = load('ctp_tabs');
  if (d && d.tabs && d.tabs.length) {
    tabs = d.tabs.map(x => { const t = mk(x.name, x.content, null, false); t.saved = x.saved; return t; });
    switchTab(tabs[Math.min(d.act || 0, tabs.length - 1)].id);
  } else {
    addTab(mk('untitled.txt', ''));
  }
})();
