(function () {
  const $ = s => document.querySelector(s);
  const toast = m => window.ctpToast && window.ctpToast(m);
  let zoom = 100;

  function opts() {
    return {
      size: $('#psize').value,
      orient: $('#porient').value,
      font: $('#pfont').value,
      fs: parseFloat($('#pfs').value) || 12,
      margin: parseFloat($('#pmargin').value) || 20,
      header: $('#phead').value,
      footer: $('#pfoot').value,
      pn: $('#pnum').checked,
      name: $('#pname').value || 'document.pdf'
    };
  }

  function applyZoom() {
    [...$('#pages').children].forEach(i => { i.style.width = zoom + '%'; });
  }

  $('#psrc').addEventListener('change', e => {
    $('#pfont').value = e.target.value === 'code' ? 'mono' : 'sans';
    $('#pnote').textContent = e.target.value === 'html'
      ? 'HTML मोड में Print dialog खुलेगा। वहाँ "Save as PDF" चुनें। इसमें Header/Footer/Page number नहीं लगते।'
      : '';
  });

  const P = {
    make() {
      const src = $('#psrc').value;
      const text = src === 'text' ? $('#ed').value : CM.text();
      if (!text.trim()) { toast('स्रोत खाली है'); return; }
      const o = JSON.stringify(opts());
      toast('PDF बन रही है…');
      if (src === 'html') Android.printHtml(text, o);
      else Android.makePdf(text, o);
    },
    open() { Android.openPdf(); },
    save() { Android.savePdf(); },
    share() { Android.sharePdf(); },
    print() { Android.printPdf(); },
    zin() { zoom = Math.min(250, zoom + 25); applyZoom(); },
    zout() { zoom = Math.max(100, zoom - 25); applyZoom(); }
  };

  document.addEventListener('click', e => {
    const b = e.target.closest('[data-p]');
    if (b && P[b.dataset.p]) P[b.dataset.p]();
  });

  window.onPdfReady = function (s) {
    const o = JSON.parse(s);
    const bx = $('#pages');
    bx.innerHTML = '';
    for (let i = 1; i <= o.shown; i++) {
      const img = new Image();
      img.src = '/pdfout/pv_' + o.token + '_' + i + '.jpg';
      img.alt = 'Page ' + i;
      bx.appendChild(img);
    }
    zoom = 100; applyZoom();
    bx.scrollTop = 0;
    $('#pinfo').textContent = o.total + ' पेज' + (o.shown < o.total ? ' (पहले ' + o.shown + ' दिख रहे)' : '');
    $('#gopdfv').click();
  };

  window.onToast = function (m) { toast(m); };

  const oldBack = window.handleBack;
  window.handleBack = function () {
    if ($('#pdfview').classList.contains('on')) {
      $('#pdfview [data-go="pdf"]').click();
      return true;
    }
    return oldBack();
  };
})();
