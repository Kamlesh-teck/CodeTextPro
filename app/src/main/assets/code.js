(function () {
  const $ = s => document.querySelector(s);
  const LANGS = [['html','HTML'],['css','CSS'],['js','JavaScript'],['json','JSON'],
    ['xml','XML'],['java','Java'],['kt','Kotlin'],['py','Python'],['cpp','C / C++'],
    ['sql','SQL'],['md','Markdown'],['php','PHP'],['txt','Text']];
  const sel = $('#clang');
  LANGS.forEach(l => {
    const o = document.createElement('option');
    o.value = l[0]; o.textContent = l[1]; sel.appendChild(o);
  });

  function load(k) { try { return JSON.parse(localStorage.getItem(k)); } catch (e) { return null; } }
  function store(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch (e) {} }
  const toast = m => window.ctpToast && window.ctpToast(m);

  let tabs = [], cur = null, uid = 0, saveTimer;
  let fs = load('ctp_cfs') || 14;
  const box = $('#cm');
  box.style.setProperty('--cfs', fs + 'px');

  /* ---------- Tabs ---------- */
  function mk(name, content, id, hasFile, saved) {
    const st = CM.mk(content, name);
    return {
      id: id || 'c' + Date.now() + '_' + (++uid),
      name: name, state: st, hasFile: !!hasFile, pend: null, dd: false,
      sd: saved != null ? CM.txt(saved) : (hasFile ? st.doc : CM.txt(''))
    };
  }
  function dirty(t) {
    const d = (t === cur ? CM.get() : t.state).doc;
    return !d.eq(t.sd);
  }

  function onUpd(u) {
    const s = CM.get();
    const p = s.selection.main.head;
    const l = s.doc.lineAt(p);
    $('#cstats').textContent = 'लाइन ' + l.number + ' · कॉलम ' + (p - l.from + 1) +
      ' · कुल ' + s.doc.lines + ' लाइनें · ' + s.doc.length + ' अक्षर';
    if (!u || u.docChanged) {
      if (cur) {
        const d = dirty(cur);
        if (d !== cur.dd) { cur.dd = d; renderTabs(); }
      }
      persist();
    }
  }

  CM.init(box, onUpd);
  CM.theme(document.documentElement.dataset.theme !== 'light');
  new MutationObserver(() => {
    CM.theme(document.documentElement.dataset.theme !== 'light');
  }).observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] });

  function add(t) { tabs.push(t); switchTab(t); }

  function switchTab(t) {
    if (cur && tabs.includes(cur)) cur.state = CM.get();
    cur = t;
    CM.set(t.state);
    sel.value = CM.key(t.name);
    renderTabs(); onUpd(); persist();
  }

  function renderTabs() {
    const bx = $('#ctabs'); bx.innerHTML = '';
    tabs.forEach(t => {
      const d = document.createElement('div');
      d.className = 'tab' + (t === cur ? ' on' : '');
      const s = document.createElement('span');
      s.textContent = (t.dd ? '● ' : '') + t.name;
      const x = document.createElement('i');
      x.textContent = '×';
      x.onclick = e => { e.stopPropagation(); closeTab(t); };
      d.onclick = () => { if (t !== cur) switchTab(t); };
      d.appendChild(s); d.appendChild(x);
      bx.appendChild(d);
      if (t === cur) setTimeout(() => d.scrollIntoView({ inline: 'center', block: 'nearest' }), 0);
    });
    const p = document.createElement('div');
    p.className = 'tab plus'; p.textContent = '+';
    p.onclick = newTab;
    bx.appendChild(p);
  }

  function newTab() { add(mk('untitled.' + sel.value, '', null, false)); }

  async function closeTab(t) {
    if (t === cur) t.state = CM.get();
    if (dirty(t)) {
      const i = await window.ctpAsk('"' + t.name + '" में बिना सेव किए बदलाव हैं।',
        ['रद्द', 'बिना सेव बंद करें']);
      if (i !== 1) return;
    }
    const k = tabs.indexOf(t);
    tabs.splice(k, 1);
    if (!tabs.length) newTab();
    else if (t === cur) switchTab(tabs[Math.max(0, k - 1)]);
    else renderTabs();
    persist();
  }

  /* ---------- Draft save ---------- */
  function persistNow() {
    if (cur) cur.state = CM.get();
    store('ctp_code', {
      act: Math.max(0, tabs.indexOf(cur)),
      tabs: tabs.map(t => {
        const s = t === cur ? CM.get() : t.state;
        const d = !s.doc.eq(t.sd);
        return { name: t.name, content: s.doc.toString(), saved: d ? t.sd.toString() : null };
      })
    });
  }
  function persist() { clearTimeout(saveTimer); saveTimer = setTimeout(persistNow, 800); }
  document.addEventListener('visibilitychange', () => { if (document.hidden) persistNow(); });
  window.addEventListener('pagehide', persistNow);

  /* ---------- Save / Open ---------- */
  function doSave(as) {
    cur.state = CM.get();
    cur.pend = cur.state.doc;
    const txt = cur.state.doc.toString();
    if (cur.hasFile && !as) Android.save(cur.id, txt);
    else Android.saveAs(cur.id, cur.name, txt);
  }

  const oldOpened = window.onFileOpened;
  window.onFileOpened = function (s) {
    if (!$('#code').classList.contains('on')) { oldOpened(s); return; }
    const o = JSON.parse(s);
    if (tabs.length === 1 && cur && !cur.hasFile && CM.get().doc.length === 0) tabs = [];
    add(mk(o.name, o.content, o.id, true));
  };

  const oldSaved = window.onSaved;
  window.onSaved = function (s) {
    const o = JSON.parse(s);
    const t = tabs.find(x => x.id === o.id);
    if (!t) { oldSaved(s); return; }
    t.name = o.name; t.hasFile = true;
    t.sd = t.pend || t.sd;
    t.dd = dirty(t);
    if (t === cur) { sel.value = CM.key(t.name); CM.lang(sel.value); }
    renderTabs(); persist(); toast('सेव हो गया ✓');
  };

  /* ---------- Actions ---------- */
  function setFs(n) {
    fs = Math.max(10, Math.min(32, n));
    box.style.setProperty('--cfs', fs + 'px');
    store('ctp_cfs', fs);
    CM.measure();
  }

  const C = {
    new: newTab,
    open() { Android.openFile(); },
    save() { doSave(false); },
    saveas() { doSave(true); },
    undo() { CM.undo(); },
    redo() { CM.redo(); },
    find() { CM.find(); },
    fminus() { setFs(fs - 2); },
    fplus() { setFs(fs + 2); },
    preview() {
      if (CM.key(cur.name) !== 'html') { toast('Preview सिर्फ HTML फाइल के लिए है'); return; }
      $('#pv').srcdoc = CM.text();
      $('#gopv').click();
    }
  };
  document.addEventListener('click', e => {
    const b = e.target.closest('[data-c]');
    if (b && C[b.dataset.c]) C[b.dataset.c]();
    if (e.target.closest('[data-go]')) setTimeout(CM.measure, 60);
  });

  sel.addEventListener('change', () => {
    if (!cur) return;
    CM.lang(sel.value);
    if (/^untitled\./.test(cur.name)) {
      cur.name = 'untitled.' + sel.value;
      renderTabs(); persist();
    }
  });

  /* ---------- Back button ---------- */
  const oldBack = window.handleBack;
  window.handleBack = function () {
    if ($('#preview').classList.contains('on')) { $('#gocode').click(); return true; }
    return oldBack();
  };

  /* ---------- Start ---------- */
  const d = load('ctp_code');
  if (d && d.tabs && d.tabs.length) {
    tabs = d.tabs.map(x => {
      const t = mk(x.name, x.content, null, false, x.saved != null ? x.saved : x.content);
      t.dd = x.saved != null;
      return t;
    });
    switchTab(tabs[Math.min(d.act || 0, tabs.length - 1)]);
  } else {
    add(mk('untitled.js', '', null, false));
  }
})();
