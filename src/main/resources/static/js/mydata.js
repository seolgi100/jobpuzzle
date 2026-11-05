// mydata.js — 지원 자료: 카드 목록(카테고리 필터) · 추출 결과 확인/수정/확정 · 등록 · 복사해서 새로 만들기 · 삭제.
// API 호출과 페이지 나누기는 document-flow.js(DocumentFlow) 공용 함수를 쓴다.
(function () {
  'use strict';
  var DF = window.DocumentFlow, U = window.JPUI, api = DF.api, esc = U.esc;
  var CATS = [
    { id: 'JOB_POSTING', ico: '공고', cls: 'job' }, { id: 'COMPANY_INFO', ico: '회사', cls: 'company' },
    { id: 'RESUME', ico: '이력', cls: 'resume' }, { id: 'COVER_LETTER', ico: '자소', cls: 'cover' },
    { id: 'PORTFOLIO', ico: '포트', cls: 'portfolio' }, { id: 'EXPERIENCE_NOTE', ico: '경험', cls: 'note' }
  ];
  var SOURCE = { PDF: 'PDF', IMAGE: '이미지', TEXT: '직접 입력' };
  var CARD_SIZE = 8;
  var expanded = false;
  try { expanded = localStorage.getItem('jp-extract-expanded') === '1'; } catch (e) { /* 저장소 없음 */ }

  var state = {
    docs: [], loaded: false, filter: 'all', cardPage: 0, navPage: 0, navSize: 6,
    selectedId: null, detail: null, versions: [], docPage: 0, editPages: null, nameEdit: false,
    regCat: 'RESUME', regMethod: 'file', regFiles: [], deleteId: null, copySource: null
  };

  function notify(type, msg) { if (window.showToast) window.showToast(type, msg); }
  function cat(id) { return CATS.filter(function (c) { return c.id === id; })[0] || CATS[0]; }
  function label(id) { return DF.CATEGORY_LABEL[id] || ''; }
  function ico(id) { var c = cat(id); return '<span class="doc-ico doc-ico--' + c.cls + '">' + c.ico + '</span>'; }
  function dot(v) { return v ? String(v).slice(0, 10).replace(/-/g, '.') : ''; }
  // 목록 응답에는 확정 상태만 있다: 확정됨 / 검토 필요(초안) / 확인 필요(추출 전·실패)
  function docStatus(d) {
    if (d.latestVersionStatus === 'CONFIRMED') return ['확정됨', 'done'];
    if (d.latestVersionStatus === 'DRAFT') return ['검토 필요', 'warn'];
    return ['확인 필요', 'warn'];
  }
  // 상세에서는 추출 상태까지 안다
  function extractionStatus(x) {
    if (!x) return ['추출 중', 'none'];
    if (x.extractionStatus === 'FAILED') return ['등록 실패', 'danger'];
    if (x.versionStatus === 'CONFIRMED') return ['확정됨', 'done'];
    if (x.extractionStatus === 'PARTIAL') return ['일부 성공', 'warn'];
    return ['검토 필요', 'warn'];
  }
  function pill(s) { return '<span class="pill pill--' + s[1] + '">' + s[0] + '</span>'; }
  function filtered() { return state.docs.filter(function (d) { return state.filter === 'all' || d.documentType === state.filter; }); }
  function findDoc(id) { return state.docs.filter(function (d) { return d.documentId === id; })[0]; }
  function pager(total, cur, kind) {
    if (total <= 1) return '';
    var h = '<button type="button" data-' + kind + '-page="' + Math.max(0, cur - 1) + '" aria-label="이전"' + (cur === 0 ? ' disabled' : '') + '>‹</button>';
    for (var i = 0; i < total; i++) h += '<button type="button" data-' + kind + '-page="' + i + '"' + (i === cur ? ' aria-current="page"' : '') + '>' + (i + 1) + '</button>';
    return h + '<button type="button" data-' + kind + '-page="' + Math.min(total - 1, cur + 1) + '" aria-label="다음"' + (cur === total - 1 ? ' disabled' : '') + '>›</button>';
  }

  // ── 목록 ──
  function renderChips() {
    var html = [{ id: 'all' }].concat(CATS).map(function (c) {
      var n = c.id === 'all' ? state.docs.length : state.docs.filter(function (d) { return d.documentType === c.id; }).length;
      return '<button type="button" class="chip" aria-pressed="' + (state.filter === c.id) + '" data-cat="' + c.id + '">' + (c.id === 'all' ? '전체' : label(c.id)) + '<small>' + n + '</small></button>';
    }).join('');
    document.getElementById('chips').innerHTML = html;
    document.getElementById('chips-x').innerHTML = html;
    var review = state.docs.filter(function (d) { return d.latestVersionStatus !== 'CONFIRMED'; }).length;
    var note = document.getElementById('review-note');
    note.hidden = !review;
    document.getElementById('review-count').textContent = '검토가 필요한 자료 ' + review + '건';
  }

  function renderCards() {
    var el = document.getElementById('cards');
    if (!state.loaded) { el.innerHTML = '<p class="nav-empty">자료를 불러오고 있어요.</p>'; return; }
    var list = filtered(), total = Math.max(1, Math.ceil(list.length / CARD_SIZE));
    state.cardPage = Math.min(state.cardPage, total - 1);
    el.innerHTML = list.slice(state.cardPage * CARD_SIZE, (state.cardPage + 1) * CARD_SIZE).map(function (d) {
      return '<article class="dcard doc-card"><div class="doc-card__top">' + ico(d.documentType) + pill(docStatus(d)) + '</div>' +
        '<div><p class="doc-card__name">' + esc(d.displayName) + '</p><p class="doc-card__meta">' + label(d.documentType) + ' · ' + (SOURCE[d.sourceType] || '') + '</p></div>' +
        '<div class="doc-card__foot"><span class="doc-date">' + dot(d.updatedAt) + '</span><div class="doc-actions">' +
        '<button type="button" class="dbtn dbtn--line" data-view="' + d.documentId + '">보기</button>' +
        '<button type="button" class="dbtn dbtn--line" data-copy="' + d.documentId + '">복사</button>' +
        '<button type="button" class="dbtn dbtn--danger-line" data-del="' + d.documentId + '">삭제</button></div></div></article>';
    }).join('') + (state.cardPage === total - 1 ? '<button type="button" class="dcard doc-card doc-card--add" data-open-register><span>+</span>새 자료 등록</button>' : '');
    document.getElementById('cards-pager').innerHTML = pager(total, state.cardPage, 'card');
  }

  function renderNav() {
    var list = filtered(), total = Math.max(1, Math.ceil(list.length / state.navSize));
    state.navPage = Math.min(state.navPage, total - 1);
    document.getElementById('nav-list').innerHTML = list.slice(state.navPage * state.navSize, (state.navPage + 1) * state.navSize).map(function (d) {
      return '<button type="button" data-select="' + d.documentId + '" aria-current="' + (d.documentId === state.selectedId) + '">' + ico(d.documentType) +
        '<div><p>' + esc(d.displayName) + '</p><small>' + docStatus(d)[0] + '</small></div></button>';
    }).join('') || '<p class="nav-empty">이 카테고리에 자료가 없어요</p>';
    document.getElementById('nav-pager').innerHTML = pager(total, state.navPage, 'nav');
  }

  // ── 추출 결과 상세 ──
  function latest() { return state.versions[0] || null; }
  function renderDetail() {
    var el = document.getElementById('detail');
    var meta = state.detail && state.detail.document;
    if (!meta) { el.innerHTML = '<p class="nav-empty">왼쪽 목록에서 확인할 자료를 골라주세요.</p>'; return; }
    var x = latest(), st = extractionStatus(x);
    var name = state.nameEdit
      ? '<input class="input" id="name-input" value="' + esc(meta.displayName) + '" maxlength="255" style="width:240px; height:36px"> <button type="button" class="dbtn dbtn--sky" data-name-save>저장</button> <button type="button" class="dbtn dbtn--line" data-name-cancel>취소</button>'
      : esc(meta.displayName) + ' <button type="button" class="dbtn dbtn--line" data-name-edit>이름 수정</button>';
    var head = '<div class="extract-head"><div><h3>' + name + '</h3><p>' + label(meta.documentType) + ' · ' + (SOURCE[meta.sourceType] || '') + '</p></div>' +
      '<div class="extract-head__right">' + pill(st) + '</div></div>';
    if (!x) {
      el.innerHTML = head + '<div class="extract-fail"><p><b>내용을 읽고 있어요</b></p><p>잠시 후 다시 확인해 주세요.</p></div>';
      return;
    }
    if (x.extractionStatus === 'FAILED') {
      el.innerHTML = head + '<div class="extract-fail"><p><b>글자를 읽지 못했어요</b></p><p>' + esc(x.failureReason || '사진이 흐리거나 글자가 너무 작으면 실패할 수 있어요. 더 선명한 파일로 다시 시도해 주세요.') + '</p>' +
        '<button type="button" class="dbtn dbtn--navy" data-retry>다시 시도</button></div>';
      return;
    }
    var editing = !!state.editPages;
    var pages = editing ? state.editPages : DF.splitPages(x.content);
    var p = Math.min(state.docPage, pages.length - 1); state.docPage = p;
    var limit = DF.charLimitFor(meta.documentType);
    var chars = pages.reduce(function (s, t) { return s + (t || '').length; }, 0);
    var hasVersion = state.versions.some(function (v) { return v.majorVersion != null; });
    var canConfirm = !editing && x.versionStatus === 'DRAFT';
    el.innerHTML = head +
      (x.extractionStatus === 'PARTIAL' ? '<p class="extract-partial">' + esc(x.failureReason || '일부 페이지를 읽지 못했어요.') + ' · 실패한 페이지는 직접 입력해서 채울 수 있어요.</p>' : '') +
      (editing
        ? '<textarea class="input page-view" id="page-editor" style="height:360px; font:inherit; resize:vertical">' + esc(pages[p]) + '</textarea>'
        : '<div class="page-view' + (expanded ? '' : ' is-collapsed') + '" id="page-view">' + esc(pages[p]) + '</div>' +
          '<div class="view-toggle" id="view-toggle" hidden><button type="button" data-toggle-view>' + (expanded ? '접기 ∧' : '전체 보기 ∨') + '</button></div>') +
      '<div class="extract-foot">' +
        (pages.length > 1 ? '<div class="page-nav"><button type="button" data-doc-page="' + Math.max(0, p - 1) + '" aria-label="이전 페이지">‹</button>' + (p + 1) + ' / ' + pages.length + ' 페이지<button type="button" data-doc-page="' + Math.min(pages.length - 1, p + 1) + '" aria-label="다음 페이지">›</button></div>' : '<span></span>') +
        '<span class="char-count"' + (chars > limit ? ' style="color:var(--danger)"' : '') + '><b id="char-now">' + chars.toLocaleString() + '</b> / ' + limit.toLocaleString() + '자<span class="char-bar"><i style="width:' + Math.min(100, Math.max(2, Math.round(chars / limit * 100))) + '%"></i></span></span>' +
        '<div class="extract-actions">' + (editing
          ? '<button type="button" class="dbtn dbtn--line" style="height:40px" data-add-page>페이지 추가</button>' +
            (pages.length > 1 ? '<button type="button" class="dbtn dbtn--danger-line" style="height:40px" data-del-page>이 페이지 삭제</button>' : '') +
            '<button type="button" class="dbtn dbtn--ghost" data-edit-cancel>취소</button><button type="button" class="dbtn dbtn--navy" data-edit-save>저장</button>'
          : '<button type="button" class="dbtn dbtn--line" style="height:40px" data-edit>수정</button>' +
            '<button type="button" class="dbtn dbtn--line" style="height:40px" data-copy="' + meta.documentId + '">복사해서 새로 만들기</button>' +
            (canConfirm ? '<button type="button" class="dbtn dbtn--navy" data-confirm>확정하기</button>' : '')) + '</div>' +
      '</div>' +
      (chars > limit ? '<p class="extract-confirm-note" style="color:var(--danger)">' + limit.toLocaleString() + '자가 넘으면 분석에 쓸 수 없어요.</p>' : '') +
      '<p class="extract-confirm-note">' + (x.versionStatus === 'CONFIRMED' ? '확정됨 · 분석과 면접 질문에 쓰이고 있어요. 고치면 다시 확정해야 해요.'
        : hasVersion ? '고친 내용은 다시 확정해야 분석에 쓰여요.' : '확정한 내용이 분석과 면접 질문에 쓰여요. 확정한 뒤에 고치면 다시 확정해야 해요.') + '</p>';
    if (editing) return;
    var view = document.getElementById('page-view'), note = view.previousElementSibling;
    if (note && note.classList.contains('extract-partial')) view.style.setProperty('--note', (note.getBoundingClientRect().height + 14) + 'px');
    view.classList.add('is-collapsed');
    var overflows = view.scrollHeight > view.clientHeight + 2;
    view.classList.toggle('is-collapsed', overflows && !expanded);
    view.classList.toggle('has-more', overflows && !expanded);
    document.getElementById('view-toggle').hidden = !overflows;
  }

  // 왼쪽 목록 높이 = 오른쪽을 접었을 때 높이, 그 안에 들어가는 만큼만 한 페이지에
  var wide = window.matchMedia('(min-width: 961px)');
  function syncNav() {
    var nav = document.getElementById('extract-nav'), detail = document.getElementById('detail');
    if (!wide.matches || !detail.offsetHeight) { nav.style.height = ''; state.navSize = 6; renderNav(); return; }
    nav.style.height = '0px';
    var h = Math.max(detail.offsetHeight, 420);
    nav.style.height = h + 'px';
    renderNav();
    var listEl = document.getElementById('nav-list'), first = listEl.querySelector('button');
    if (first) state.navSize = Math.max(3, Math.floor(listEl.clientHeight / first.offsetHeight));
    renderNav();
  }

  function renderAll() { renderChips(); renderCards(); if (!document.getElementById('p-extract').hidden) { renderDetail(); syncNav(); } }

  function showTab(name) {
    document.querySelectorAll('.dtab').forEach(function (t) {
      var on = t.dataset.tab === name;
      t.setAttribute('aria-selected', String(on));
      document.getElementById('p-' + t.dataset.tab).hidden = !on;
    });
    if (name === 'extract') {
      if (!state.selectedId) { var first = filtered()[0]; if (first) return select(first.documentId); }
      renderDetail(); syncNav();
    }
  }

  // ── 데이터 ──
  function loadDocs() {
    // ponytail: 전체를 한 번에(최대 200개) 받아 화면에서 나눔. 자료가 더 많아지면 서버 페이지로 바꿀 것
    return api('/documents?size=200').then(function (page) {
      state.docs = (page && page.content) || []; state.loaded = true; renderAll();
    }).catch(function (e) { state.loaded = true; renderAll(); notify('error', e.message); });
  }

  function loadDetail(id) {
    return Promise.all([api('/documents/' + id), api('/documents/' + id + '/extractions')]).then(function (r) {
      if (state.selectedId !== id) return;
      state.detail = r[0]; state.versions = r[1] || [];
      renderDetail(); syncNav();
    }).catch(function (e) { notify('error', e.message); });
  }

  function select(id) {
    state.selectedId = id; state.docPage = 0; state.editPages = null; state.nameEdit = false; state.detail = null; state.versions = [];
    var pos = filtered().map(function (d) { return d.documentId; }).indexOf(id);
    if (pos >= 0) state.navPage = Math.floor(pos / state.navSize);
    document.querySelectorAll('.dtab').forEach(function (t) {
      var on = t.dataset.tab === 'extract';
      t.setAttribute('aria-selected', String(on));
      document.getElementById('p-' + t.dataset.tab).hidden = !on;
    });
    renderDetail(); syncNav();
    return loadDetail(id);
  }

  function captureEdit() {
    var ed = document.getElementById('page-editor');
    if (ed && state.editPages) state.editPages[state.docPage] = ed.value;
  }

  function saveEdit() {
    captureEdit();
    var x = latest();
    var withMarkers = DF.hasPageMarkers(x.content) || state.editPages.length > 1;
    var content = DF.joinPages(state.editPages, withMarkers).trim();
    if (!content) { notify('warning', '내용을 입력해주세요.'); return; }
    var hasVersion = state.versions.some(function (v) { return v.majorVersion != null; });
    var payload = { content: content };
    if (hasVersion) payload.changeType = 'MINOR';   // 버전 화면은 없앴고, 수정은 늘 작은 수정으로 저장
    api('/document-extractions/' + x.extractionId + '/versions', { method: 'POST', json: payload }).then(function () {
      state.editPages = null; notify('success', '고친 내용을 저장했어요. 확정해야 분석에 쓰여요.');
      return Promise.all([loadDetail(state.selectedId), loadDocs()]);
    }).catch(function (e) { notify('error', e.message); });
  }

  // ── 등록 모달 ──
  function openRegister() {
    state.regCat = state.filter !== 'all' ? state.filter : 'RESUME'; state.regMethod = 'file'; state.regFiles = [];
    document.getElementById('register-name').value = '';
    document.getElementById('register-content').value = '';
    document.getElementById('register-keep').checked = false;
    renderRegister();
    document.getElementById('register').hidden = false;
  }
  function renderRegister() {
    document.getElementById('reg-cats').innerHTML = CATS.map(function (c) {
      return '<button type="button" class="reg-cat" data-reg-cat="' + c.id + '" aria-pressed="' + (c.id === state.regCat) + '">' + ico(c.id) + label(c.id) + '</button>';
    }).join('');
    document.querySelectorAll('[data-method]').forEach(function (b) { b.setAttribute('aria-pressed', String(b.dataset.method === state.regMethod)); });
    document.querySelectorAll('[data-method-panel]').forEach(function (p) { p.hidden = p.dataset.methodPanel !== state.regMethod; });
    document.getElementById('register-files').innerHTML = state.regFiles.map(function (f, i) {
      return '<span class="file">' + esc(f.name) + ' <button type="button" data-remove-file="' + i + '" aria-label="빼기">×</button></span>';
    }).join('');
  }
  function addFiles(list) {
    Array.prototype.forEach.call(list, function (f) { state.regFiles.push(f); });
    renderRegister();
  }
  function submitRegister(btn) {
    var name = document.getElementById('register-name').value.trim();
    if (!name) { notify('warning', '자료명을 입력해주세요.'); return; }
    var opts = { method: state.regMethod, category: state.regCat, name: name };
    if (state.regMethod === 'file') {
      if (!state.regFiles.length) { notify('warning', '올릴 파일을 골라주세요.'); return; }
      opts.files = state.regFiles; opts.keepOriginal = document.getElementById('register-keep').checked;
    } else {
      opts.content = document.getElementById('register-content').value.trim();
      if (!opts.content) { notify('warning', '내용을 입력해주세요.'); return; }
    }
    btn.disabled = true;
    DF.registerDocument(opts).then(function (doc) {
      document.getElementById('register').hidden = true;
      notify('success', state.regMethod === 'file' ? '자료를 올렸어요. 내용을 읽는 중이에요.' : '자료를 등록했어요.');
      return loadDocs().then(function () {
        select(doc.documentId);
        if (state.regMethod === 'file') DF.pollExtraction(doc.documentId, function () { loadDetail(doc.documentId); loadDocs(); });
      });
    }).catch(function (e) { notify('error', e.message); }).then(function () { btn.disabled = false; });
  }

  // ── 복사해서 새로 만들기: 최신 내용으로 같은 종류의 직접 입력 자료를 새로 만든다(원본은 그대로) ──
  function openCopy(id) {
    var d = findDoc(id);
    if (!d) return;
    state.copySource = d;
    document.getElementById('copy-from').innerHTML = ico(d.documentType) + '<span><b>' + esc(d.displayName) + '</b>를 복사해요</span>';
    document.getElementById('copy-name').value = d.displayName + '_복사본';
    document.getElementById('copy').hidden = false;
  }
  function submitCopy(btn) {
    var d = state.copySource, name = document.getElementById('copy-name').value.trim();
    if (!name) { notify('warning', '새 자료명을 입력해주세요.'); return; }
    btn.disabled = true;
    api('/documents/' + d.documentId + '/extractions').then(function (versions) {
      var src = (versions || []).filter(function (v) { return v.extractionStatus !== 'FAILED' && v.content; })[0];
      if (!src) throw new Error('복사할 내용이 없어요. 원본의 추출 결과를 먼저 확인해 주세요.');
      return DF.registerDocument({ method: 'text', category: d.documentType, name: name, content: src.content });
    }).then(function (doc) {
      document.getElementById('copy').hidden = true;
      notify('success', '복사본을 만들었어요. 고친 뒤 확정해 주세요.');
      return loadDocs().then(function () { select(doc.documentId); });
    }).catch(function (e) { notify('error', e.message); }).then(function () { btn.disabled = false; });
  }

  // ── 클릭 ──
  document.addEventListener('click', function (e) {
    var t = e.target.closest('button, [data-open-register]');
    if (!t || t.disabled) return;
    var ds = t.dataset;
    if (ds.cat) {
      state.filter = ds.cat; state.cardPage = 0; state.navPage = 0; renderAll();
      var list = filtered();
      if (!document.getElementById('p-extract').hidden && list.length && !list.some(function (d) { return d.documentId === state.selectedId; })) select(list[0].documentId);
    }
    else if (ds.cardPage) { state.cardPage = +ds.cardPage; renderCards(); }
    else if (ds.navPage) { state.navPage = +ds.navPage; renderNav(); }
    else if (ds.docPage) { captureEdit(); state.docPage = +ds.docPage; renderDetail(); syncNav(); }
    else if (ds.select) select(+ds.select);
    else if (ds.view) select(+ds.view);
    else if (ds.tab) showTab(ds.tab);
    else if (ds.goto) showTab(ds.goto);
    else if (t.hasAttribute('data-toggle-view')) {
      expanded = !expanded;
      try { localStorage.setItem('jp-extract-expanded', expanded ? '1' : '0'); } catch (err) { /* 무시 */ }
      renderDetail();
    }
    else if (t.hasAttribute('data-open-register')) openRegister();
    else if (t.hasAttribute('data-close')) t.closest('.modal').hidden = true;
    else if (ds.regCat) { state.regCat = ds.regCat; renderRegister(); }
    else if (ds.method) { state.regMethod = ds.method; renderRegister(); }
    else if (ds.removeFile) { state.regFiles.splice(+ds.removeFile, 1); renderRegister(); }
    else if (t.hasAttribute('data-register-submit')) submitRegister(t);
    else if (ds.copy) openCopy(+ds.copy);
    else if (t.hasAttribute('data-copy-submit')) submitCopy(t);
    else if (ds.del) {
      var d = findDoc(+ds.del); state.deleteId = +ds.del;
      document.getElementById('delete-name').textContent = d ? d.displayName : '';
      document.getElementById('delete').hidden = false;
    }
    else if (t.hasAttribute('data-delete-submit')) {
      t.disabled = true;
      api('/documents/' + state.deleteId, { method: 'DELETE' }).then(function () {
        document.getElementById('delete').hidden = true;
        if (state.selectedId === state.deleteId) { state.selectedId = null; state.detail = null; }
        notify('success', '자료를 삭제했어요.');
        return loadDocs();
      }).catch(function (err) { notify('error', err.message); }).then(function () { t.disabled = false; });
    }
    else if (t.hasAttribute('data-name-edit')) { state.nameEdit = true; renderDetail(); }
    else if (t.hasAttribute('data-name-cancel')) { state.nameEdit = false; renderDetail(); }
    else if (t.hasAttribute('data-name-save')) {
      var value = document.getElementById('name-input').value.trim();
      if (!value) { notify('warning', '자료명을 입력해주세요.'); return; }
      api('/documents/' + state.selectedId + '/name', { method: 'PATCH', json: { displayName: value } }).then(function () {
        state.detail.document.displayName = value; state.nameEdit = false;
        var doc = findDoc(state.selectedId); if (doc) doc.displayName = value;
        renderAll(); renderDetail(); syncNav();
      }).catch(function (err) { notify('error', err.message); });
    }
    else if (t.hasAttribute('data-edit')) { state.editPages = DF.splitPages(latest().content); renderDetail(); }
    else if (t.hasAttribute('data-edit-cancel')) { state.editPages = null; renderDetail(); syncNav(); }
    else if (t.hasAttribute('data-edit-save')) saveEdit();
    else if (t.hasAttribute('data-add-page')) { captureEdit(); state.editPages.splice(state.docPage + 1, 0, ''); state.docPage++; renderDetail(); }
    else if (t.hasAttribute('data-del-page')) { state.editPages.splice(state.docPage, 1); state.docPage = Math.max(0, state.docPage - 1); renderDetail(); }
    else if (t.hasAttribute('data-confirm')) {
      t.disabled = true;
      api('/document-extractions/' + latest().extractionId + '/confirm', { method: 'POST' }).then(function () {
        notify('success', '확정했어요. 이제 분석과 면접 질문에 쓰여요.');
        return Promise.all([loadDetail(state.selectedId), loadDocs()]);
      }).catch(function (err) { notify('error', err.message); t.disabled = false; });
    }
    else if (t.hasAttribute('data-retry')) {
      var id = state.selectedId;
      api('/documents/' + id + '/extractions', { method: 'POST' }).then(function () {
        notify('success', '다시 읽는 중이에요.');
        state.versions = []; renderDetail();
        DF.pollExtraction(id, function () { loadDetail(id); loadDocs(); });
      }).catch(function (err) { notify('error', err.message); });
    }
  });

  document.addEventListener('input', function (e) {
    if (e.target.id !== 'page-editor') return;
    captureEdit();
    var n = state.editPages.reduce(function (s, x) { return s + (x || '').length; }, 0);
    document.getElementById('char-now').textContent = n.toLocaleString();
  });

  document.addEventListener('keydown', function (e) {
    if (e.key === 'Escape') document.querySelectorAll('.modal').forEach(function (m) { m.hidden = true; });
    if (e.key === 'Enter' && e.target.id === 'name-input') document.querySelector('[data-name-save]').click();
  });

  document.addEventListener('DOMContentLoaded', function () {
    var input = document.getElementById('register-file'), drop = document.getElementById('register-drop');
    drop.addEventListener('click', function () { input.click(); });
    drop.addEventListener('keydown', function (e) { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); input.click(); } });
    input.addEventListener('change', function () { addFiles(input.files); input.value = ''; });
    drop.addEventListener('dragover', function (e) { e.preventDefault(); drop.style.borderColor = 'var(--sky)'; });
    drop.addEventListener('dragleave', function () { drop.style.borderColor = ''; });
    drop.addEventListener('drop', function (e) { e.preventDefault(); drop.style.borderColor = ''; addFiles(e.dataTransfer.files); });
    wide.addEventListener('change', syncNav);
    renderAll();
    var q = new URLSearchParams(window.location.search);
    loadDocs().then(function () {
      if (q.get('documentId')) select(Number(q.get('documentId')));
      else if (q.get('tab') === 'extract') showTab('extract');
    });
  });
})();
