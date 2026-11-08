// interview.js — 면접 준비: 질문 생성 flow
// 모드 선택/약점 선택/질문-채팅 화면은 아직 더미 데이터
// 맞춤 면접 질문의 "자료 선택 -> 확정" 단계만

(function () {
  'use strict';

  var DF = window.DocumentFlow;

  function notify(type, message, duration) {
    if (typeof window.showToast === 'function') {
      return window.showToast(type, message, duration);
    }
    window.alert(message);
    return null;
  }

  var CAREER_LEVEL_LABEL = { NEW: '신입', EXPERIENCED: '경력', ANY: '경력무관' };
  var COMPANY_TYPES = ['JOB_POSTING', 'COMPANY_INFO'];
  var CANDIDATE_TYPES = ['RESUME', 'COVER_LETTER', 'PORTFOLIO', 'EXPERIENCE_NOTE'];

  var state = {
    step: 'mode', mode: null,
    selectedWeaknessTag: null,
    weaknessTags: [],

    // 자료 선택 단계
    docsLoaded: false,
    allDocs: [],
    jobCategories: [],
    selectedMainCategory: null,
    selectedSubCategory: null,
    selectedJobCategoryId: null,
    selectedDocIds: {}, // documentId -> true
    selectedExtractionIds: {}, // documentId -> CONFIRMED extractionId
    materialStage: 'company', // company(공고) -> candidate(내 자료)
    materialError: null,      // 다음 단계로 못 갈 때 버튼 옆에 보여줄 이유
    loadError: null,
    tickets: null,
    registerTargetType: null,
    materialRegisterMethod: 'file',
    analysisCaseId: null
  };

  function uniqueInOrder(values) {
    var seen = {};
    var result = [];
    values.forEach(function (v) { if (!seen[v]) { seen[v] = true; result.push(v); } });
    return result;
  }


  // 'mode' 단계 = 면접 준비 홈(interview-home.js). 다른 단계에서는 홈을 숨기고 step-root만 보인다.
  function render() {
    var root = document.getElementById('step-root');
    var home = window.InterviewHome;
    if (state.step === 'mode') {
      root.innerHTML = '';
      if (home) home.show();
    } else if (home) {
      home.hide();
    }
    if (state.step === 'weaknessPick') root.innerHTML = renderWeaknessPick();
    else if (state.step === 'materialSelect') root.innerHTML = renderMaterialSelect();
    bindStepEvents();
  }

  function showModeStep() {
    state.step = 'mode';
    render();
  }

  function openWeakness() {
    DF.api('/interview-weakness-tags/details').then(function (tags) {
      state.weaknessTags = tags || [];
      state.selectedWeaknessTag = null;
      state.step = 'weaknessPick';
      render();
      window.scrollTo(0, 0);
    }).catch(function (e) { notify('error', e.message); });
  }

  function openCustom() {
    state.mode = 'custom';
    enterMaterialSelect();
    window.scrollTo(0, 0);
  }

  // ---- step: weakness pick ----
  function renderWeaknessPick() {
    function historyDate(value) {
      if (!value) return '';
      var date = new Date(value);
      return String(date.getMonth() + 1).padStart(2, '0') + '.' + String(date.getDate()).padStart(2, '0');
    }
    function diagnosticChips(values) {
      if (!values || !values.length) return '';
      return '<p class="wk-diag"><span>세부 진단</span>' + values.map(function (value) {
        return '<i>' + esc(value) + '</i>';
      }).join('') + '</p>';
    }
    function historyLink(sessionId, label) {
      if (!sessionId) return '';
      return '<a class="fl-link" href="' + window.JobPuzzleRoutes.path('/interview-results?sessionId=' +
        encodeURIComponent(sessionId)) + '">' + esc(label || '결과 보기') + '</a>';
    }
    function summary(date, mode, score, stateClass, stateText) {
      return '<div class="wk-sum"><b>' + esc(date) + '</b><span>' + esc(mode) + '</span>' +
        (score == null ? '' : '<strong>' + esc(score) + '점</strong>') +
        '<em class="wk-state ' + stateClass + '">' + stateText + '</em></div>';
    }
    function renderOccurrence(occurrence) {
      var mode = { BASIC: '기본 면접', COMPANY_FIT: '맞춤 면접', WEAKNESS_REVIEW: '약점 보완' }[occurrence.mode] || occurrence.mode || '';
      var resolved = occurrence.status === 'RESOLVED';
      var attempts = (occurrence.attempts || []).map(function (attempt) {
        var ok = attempt.status === 'RESOLVED';
        return '<div class="wk-att">' + summary(historyDate(attempt.occurredAt), '약점 보완', attempt.score,
          ok ? 'is-ok' : 'is-open', ok ? '해결' : '미해결') +
          diagnosticChips(attempt.diagnostics) +
          historyLink(attempt.sessionId, attempt.resultLabel || '약점 보완 리포트 보기') + '</div>';
      }).join('');
      return '<article class="wk-occ">' + summary(historyDate(occurrence.occurredAt), mode, occurrence.score,
        resolved ? 'is-ok' : 'is-open', resolved ? '해결' : (occurrence.attempts || []).length ? '보완 중' : '보완 전') +
        diagnosticChips(occurrence.diagnostics) +
        historyLink(occurrence.sessionId, occurrence.resultLabel || '맞춤 면접 리포트 보기') + attempts + '</article>';
    }

    var rows = state.weaknessTags.map(function (item) {
      var tag = typeof item === 'string' ? item : item.tag;
      var displayName = typeof item === 'string' ? item : item.displayName;
      var description = typeof item === 'string'
        ? '이전 평가에서 확인된 관점을 집중해서 보완해요.'
        : item.description;
      var occurrences = typeof item === 'string' ? [] : (item.recentOccurrences || []);
      var count = item.unresolvedCount || item.occurrenceCount || occurrences.length || 1;
      var expanded = state.selectedWeaknessTag === tag;
      var id = 'wk-' + String(tag).replace(/[^a-zA-Z0-9_-]/g, '');
      var open = occurrences.filter(function (o) { return o.status !== 'RESOLVED'; });
      var done = occurrences.filter(function (o) { return o.status === 'RESOLVED'; });
      var body = '';
      if (expanded) {
        body = '<div class="wk-body" id="' + id + '">' +
          (occurrences.length
            ? (open.length ? '<section class="wk-group"><h3>보완이 필요한 면접 <b>' + open.length + '</b></h3>' + open.map(renderOccurrence).join('') + '</section>' : '') +
              (done.length ? '<section class="wk-group"><h3>최근 해결한 기록 <b>' + done.length + '</b></h3>' + done.map(renderOccurrence).join('') + '</section>' : '')
            : '<p class="wk-empty">아직 보완 기록이 없어요.</p>') +
          '<div class="wk-actions"><button type="button" class="fl-btn fl-btn--navy" data-generate-weakness="' + esc(tag) + '">' +
          '이 약점으로 질문 만들기</button></div></div>';
      }
      return '<li class="wk' + (expanded ? ' is-open' : '') + '">' +
        '<button type="button" class="wk-toggle" aria-expanded="' + expanded + '" aria-controls="' + id + '" data-weak-tag="' + esc(tag) + '">' +
        '<span class="wk-name">#' + esc(displayName) + '</span>' +
        '<span class="wk-desc">' + esc(description) + '</span>' +
        '<span class="wk-count">보완 필요 ' + esc(count) + '건</span></button>' + body + '</li>';
    }).join('');

    return '<section class="fl-panel" aria-labelledby="fl-title">' +
      backBtn('backToMode', '면접 준비로 돌아가기') +
      '<h1 class="fl-title" id="fl-title">보완할 약점을 골라주세요</h1>' +
      '<p class="fl-sub">약점을 누르면 보완 기록을 볼 수 있어요. 질문은 기록 아래 버튼으로 만들어요.</p>' +
      (rows ? '<ul class="wk-list">' + rows + '</ul>'
        : '<p class="wk-empty">보완할 약점이 아직 없어요. 면접을 한 번 마치면 답변에서 찾은 약점이 여기에 생겨요.</p>') +
      '</section>';
  }

  function backBtn(action, label) {
    return '<button type="button" class="fl-back" data-action="' + action + '"><span aria-hidden="true">←</span> ' + esc(label) + '</button>';
  }

  // ---- step: material select (실제 API 연동) ----
  function enterMaterialSelect() {
    state.step = 'materialSelect';
    state.materialStage = 'company';
    state.materialError = null;
    window.history.pushState({ materialStage: 'company' }, '');
    reloadMaterials();
  }

  function reloadMaterials() {
    state.loadError = null;
    state.docsLoaded = false;
    render();
    loadMaterialSelectData().then(render);
  }

  function setMaterialStage(stage) {
    state.materialStage = stage;
    state.materialError = null;
    render();
    window.scrollTo(0, 0);
    var title = document.getElementById('fl-title');
    if (title) { title.tabIndex = -1; title.focus(); }
  }

  // 단계 이동은 history.back으로 해서 화면 버튼과 브라우저 뒤로 가기가 같은 길을 쓴다
  window.addEventListener('popstate', function (event) {
    if (state.step !== 'materialSelect') return;
    if (event.state && event.state.materialStage === 'company') setMaterialStage('company');
    else showModeStep();
  });

  function loadMaterialSelectData() {
    return Promise.all([
      DF.api('/documents?size=100'),
      state.jobCategories.length ? Promise.resolve(state.jobCategories) : DF.api('/job-category'),
      DF.api('/user/me'),
      DF.api('/tickets/me').catch(function () { return null; })
    ]).then(function (results) {
      state.allDocs = (results[0] && results[0].content) || [];
      state.tickets = results[3];
      state.jobCategories = results[1] || [];
      state.docsLoaded = true;

      // 회원 기본 관심 직무를 초기값으로 제공 (아직 아무것도 선택 안 한 첫 진입 시에만)
      var defaultJobCategoryId = results[2] && results[2].defaultJobCategoryId;
      if (defaultJobCategoryId && !state.selectedJobCategoryId) {
        var match = state.jobCategories.filter(function (c) { return String(c.jobCategoryId) === String(defaultJobCategoryId); })[0];
        if (match) {
          state.selectedMainCategory = match.mainCategory;
          state.selectedSubCategory = match.subCategory;
          state.selectedJobCategoryId = match.jobCategoryId;
        }
      }
    }).catch(function (e) { state.loadError = e.message || '자료를 불러오지 못했어요.'; });
  }

  function docListHtml(type) {
    var docs = state.allDocs.filter(function (d) { return d.documentType === type; });
    var items = docs.map(function (d) {
      var confirmed = d.latestVersionStatus === 'CONFIRMED';
      if (!confirmed) {
        var status = DF.statusLabel(null, d.latestVersionStatus);
        return '<li><div class="ms-doc is-disabled"><span class="ms-check" aria-hidden="true"></span>' +
          '<span class="ms-doc__name">' + esc(d.displayName) + '</span>' +
          '<span class="ms-doc__meta">' + esc(status.text) + ' · 자료 관리에서 확정하면 고를 수 있어요</span></div></li>';
      }
      // 문서 유형별 상한을 넘는 자료는 분석에 쓸 수 없어 목록에서 선택을 막는다 (document-flow.js charLimitFor와 동일 기준)
      var docLimit = DF.charLimitFor(d.documentType);
      if (typeof d.contentLength === 'number' && d.contentLength > docLimit) {
        return '<li><div class="ms-doc is-disabled"><span class="ms-check" aria-hidden="true"></span>' +
          '<span class="ms-doc__name">' + esc(d.displayName) + '</span>' +
          '<span class="ms-doc__meta is-error">' + d.contentLength.toLocaleString() + ' / ' + docLimit.toLocaleString() +
          '자 · 글자 수가 넘어서 고를 수 없어요</span></div></li>';
      }
      var checked = !!state.selectedDocIds[d.documentId];
      return '<li><button type="button" class="ms-doc" aria-pressed="' + checked + '" data-toggle-doc="' + d.documentId + '">' +
        '<span class="ms-check" aria-hidden="true"></span>' +
        '<span class="ms-doc__name">' + esc(d.displayName) + '</span>' +
        '<span class="ms-doc__meta">' + DF.versionLabel(d.latestMajorVersion, d.latestMinorVersion) + ' · 확정됨</span></button></li>';
    }).join('');
    return '<fieldset class="ms-cat"><legend>' + DF.CATEGORY_LABEL[type] + '</legend>' +
      (items ? '<ul class="ms-docs">' + items + '</ul>' : '<p class="ms-empty">등록된 자료가 없어요</p>') +
      '<button type="button" class="ms-add" data-open-register="' + type + '">+ ' + DF.CATEGORY_LABEL[type] + ' 등록하기</button></fieldset>';
  }

  function selectedDocsOf(types) {
    return state.allDocs.filter(function (d) {
      return state.selectedDocIds[d.documentId] && types.indexOf(d.documentType) >= 0;
    });
  }

  // 시작 전에 비용과 잔액을 먼저 알려 마지막 확인 창에서 처음 알게 되는 일을 막는다
  function ticketLineHtml() {
    var t = state.tickets;
    if (!t) return '';
    var range = t.costs.companyFitMin + '~' + t.costs.companyFitMax + '장';
    var short = t.balance < t.costs.companyFitMin;
    return '<p class="ms-ticket">맞춤 분석에 티켓 <b>' + range + '</b>이 들어요 · 지금 <b>' + t.balance + '장</b>' +
      (short ? ' · 모자라요. <a class="fl-link" href="' + window.JobPuzzleRoutes.path('/tickets') + '">충전하기</a>' : '') + '</p>';
  }

  function footHtml(summary, nextLabel) {
    return '<div class="ms-foot"><span class="ms-summary" aria-live="polite">' + summary + '</span>' +
      (state.materialError ? '<p class="ms-error" role="alert">' + esc(state.materialError) + '</p>' : '') +
      '<button type="button" class="fl-btn fl-btn--navy" id="material-select-next">' + nextLabel + '</button></div>';
  }

  function renderMaterialSelect() {
    var back = state.materialStage === 'candidate'
      ? backBtn('historyBack', '공고 다시 고르기')
      : backBtn('historyBack', '면접 준비로 돌아가기');
    if (state.loadError) {
      return '<section class="fl-panel">' + back +
        '<div class="ms-load" role="alert"><p class="fl-sub">' + esc(state.loadError) + '</p>' +
        '<button type="button" class="fl-btn fl-btn--ghost" data-action="retryMaterials">다시 불러오기</button></div></section>';
    }
    if (!state.docsLoaded) {
      return '<section class="fl-panel">' + back +
        '<p class="fl-sub" role="status">자료를 불러오고 있어요.</p></section>';
    }

    if (state.materialStage === 'company') {
      var postings = selectedDocsOf(['JOB_POSTING']).length;
      var infos = selectedDocsOf(['COMPANY_INFO']).length;
      return '<section class="fl-panel" aria-labelledby="fl-title">' + back +
        '<p class="ms-stage">1 / 2 단계</p>' +
        '<h1 class="fl-title" id="fl-title">어느 공고로 준비하나요?</h1>' +
        '<p class="fl-sub">채용공고를 하나 이상 골라주세요. 회사 정보는 있으면 함께 써요.</p>' +
        ticketLineHtml() +
        '<div class="ms-cols"><div class="ms-col">' + COMPANY_TYPES.map(docListHtml).join('') + '</div></div>' +
        footHtml('채용공고 <b>' + postings + '개</b> · 회사 정보 <b>' + infos + '개</b>', '다음: 내 자료 고르기') +
      '</section>';
    }

    var mainCategories = uniqueInOrder(state.jobCategories.map(function (c) { return c.mainCategory; }));
    var mainOpts = '<option value="">대분류</option>' + mainCategories.map(function (m) {
      return '<option value="' + esc(m) + '"' + (m === state.selectedMainCategory ? ' selected' : '') + '>' + esc(m) + '</option>';
    }).join('');
    var subCategories = state.selectedMainCategory
      ? uniqueInOrder(state.jobCategories.filter(function (c) { return c.mainCategory === state.selectedMainCategory; }).map(function (c) { return c.subCategory; }))
      : [];
    var subOpts = '<option value="">' + (state.selectedMainCategory ? '중분류' : '대분류를 먼저 골라주세요') + '</option>' +
      subCategories.map(function (s) { return '<option value="' + esc(s) + '"' + (s === state.selectedSubCategory ? ' selected' : '') + '>' + esc(s) + '</option>'; }).join('');
    var levelMatches = (state.selectedMainCategory && state.selectedSubCategory)
      ? state.jobCategories.filter(function (c) { return c.mainCategory === state.selectedMainCategory && c.subCategory === state.selectedSubCategory; })
      : [];
    var levelOpts = '<option value="">' + (levelMatches.length ? '경력' : '중분류를 먼저 골라주세요') + '</option>' +
      levelMatches.map(function (c) {
        return '<option value="' + c.jobCategoryId + '"' + (String(c.jobCategoryId) === String(state.selectedJobCategoryId) ? ' selected' : '') + '>' + (CAREER_LEVEL_LABEL[c.careerLevel] || c.careerLevel) + '</option>';
      }).join('');
    var posting = selectedDocsOf(['JOB_POSTING'])[0];
    var mine = selectedDocsOf(CANDIDATE_TYPES).length;

    return '<section class="fl-panel" aria-labelledby="fl-title">' + back +
      '<p class="ms-stage">2 / 2 단계' + (posting ? ' · ' + esc(posting.displayName) : '') + '</p>' +
      '<h1 class="fl-title" id="fl-title">이 공고에 낼 내 자료를 골라주세요</h1>' +
      '<p class="fl-sub">확정된 자료만 고를 수 있어요. 없으면 바로 등록할 수 있어요.</p>' +
      '<div class="ms-cols">' +
        '<div class="ms-col"><div class="ms-job"><h2 class="ms-h">희망 직무</h2><div class="ms-selects">' +
          '<select id="material-main-select" class="fl-select" aria-label="직무 대분류">' + mainOpts + '</select>' +
          '<select id="material-sub-select" class="fl-select" aria-label="직무 중분류"' + (state.selectedMainCategory ? '' : ' disabled') + '>' + subOpts + '</select>' +
          '<select id="material-level-select" class="fl-select" aria-label="경력"' + (levelMatches.length ? '' : ' disabled') + '>' + levelOpts + '</select>' +
        '</div><p class="ms-note">고른 직무·경력을 면접 기준으로 써요. 공고의 요구 경력과 다르면 분석 결과에서 차이를 알려드려요.</p></div></div>' +
        '<div class="ms-col"><h2 class="ms-h">내 지원 자료</h2>' + CANDIDATE_TYPES.map(docListHtml).join('') + '</div>' +
      '</div>' +
      footHtml('내 자료 <b>' + mine + '개</b>', '확인하고 분석 준비') +
    '</section>';
  }

  function goCandidateStage() {
    if (!selectedDocsOf(['JOB_POSTING']).length) {
      state.materialError = '채용공고를 하나 이상 골라주세요.';
      render();
      return;
    }
    window.history.pushState({ materialStage: 'candidate' }, '');
    setMaterialStage('candidate');
  }

  function goMaterialReview() {
    var problem = !state.selectedJobCategoryId ? '희망 직무와 경력을 골라주세요.'
      : !selectedDocsOf(CANDIDATE_TYPES).length ? '내 자료를 하나 이상 골라주세요.' : null;
    if (problem) { state.materialError = problem; render(); return; }
    var selectedIds = Object.keys(state.selectedDocIds);

    Promise.all(selectedIds.map(function (id) {
      if (state.selectedExtractionIds[id]) return Promise.resolve();
      return DF.api('/documents/' + id).then(function (detail) {
        var confirmed = detail.confirmedVersions && detail.confirmedVersions[0];
        if (confirmed) state.selectedExtractionIds[id] = confirmed.extractionId;
      });
    })).then(function () {
      openMaterialReviewModal();
    }).catch(function (e) { notify('error', e.message); });
  }

  // ---- step: material review (선택 내용 읽기 전용 확인 + 확정) ----
  function materialReviewContent() {
    var byType = {};
    Object.keys(state.selectedDocIds).forEach(function (id) {
      var doc = state.allDocs.filter(function (d) { return String(d.documentId) === String(id); })[0];
      if (!doc) return;
      byType[doc.documentType] = byType[doc.documentType] || [];
      byType[doc.documentType].push(doc);
    });
    var jc = state.jobCategories.filter(function (c) { return String(c.jobCategoryId) === String(state.selectedJobCategoryId); })[0];
    var jcLabel = jc ? (jc.mainCategory + ' › ' + jc.subCategory + ' · ' + (CAREER_LEVEL_LABEL[jc.careerLevel] || jc.careerLevel)) : '';
    return '<dl class="mr-list"><div><dt>희망 직무</dt><dd>' + esc(jcLabel) + '</dd></div>' +
      Object.keys(byType).map(function (type) {
        return '<div><dt>' + DF.CATEGORY_LABEL[type] + '</dt><dd>' + byType[type].map(function (d) {
          return esc(d.displayName) + ' <small>' + DF.versionLabel(d.latestMajorVersion, d.latestMinorVersion) + '</small>';
        }).join('<br>') + '</dd></div>';
      }).join('') + '</dl>';
  }

  var reviewReturnFocus = null;

  function closeMaterialReviewModal() {
    var modal = document.getElementById('material-review-modal');
    if (modal) modal.remove();
    document.body.classList.remove('ih-modal-open');
    document.removeEventListener('keydown', onReviewKey, true);
    if (reviewReturnFocus && document.contains(reviewReturnFocus)) reviewReturnFocus.focus();
    reviewReturnFocus = null;
  }

  function onReviewKey(event) {
    var modal = document.getElementById('material-review-modal');
    if (!modal) return;
    if (event.key === 'Escape') { event.preventDefault(); closeMaterialReviewModal(); return; }
    if (event.key !== 'Tab') return;
    var items = Array.prototype.slice.call(modal.querySelectorAll('a[href], button:not([disabled])'));
    if (!items.length) return;
    var first = items[0];
    var last = items[items.length - 1];
    if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
    else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
  }

  function openMaterialReviewModal() {
    closeMaterialReviewModal();
    reviewReturnFocus = document.activeElement;
    document.body.classList.add('ih-modal-open');
    document.body.insertAdjacentHTML('beforeend',
      '<div class="qs-layer" id="material-review-modal">' +
        '<div class="qs-scrim" data-close-material-review></div>' +
        '<section class="qs-box qs-box--narrow" role="dialog" aria-modal="true" aria-labelledby="material-review-title" tabindex="-1">' +
          '<header class="qs-head"><div><div class="qs-chips"><span class="qs-mode">맞춤 면접</span></div>' +
          '<h2 id="material-review-title">분석 전 마지막 확인</h2>' +
          '<p>이 구성으로 분석을 시작하면 나중에 바꿀 수 없어요.</p></div>' +
          '<button type="button" class="qs-x" data-close-material-review aria-label="확인 창 닫기">×</button></header>' +
          '<div class="mr-body">' + materialReviewContent() +
          '<div class="tk-scope tk-inline" id="material-review-ticket" role="status">티켓 잔액을 확인하고 있어요.</div></div>' +
          '<footer class="qs-foot"><button type="button" class="fl-btn fl-btn--ghost" data-close-material-review>자료 다시 고르기</button>' +
          '<span id="material-review-action"><button type="button" class="fl-btn fl-btn--navy" id="material-review-confirm" disabled>이 구성으로 분석 시작</button></span></footer>' +
        '</section>' +
      '</div>');
    document.querySelectorAll('[data-close-material-review]').forEach(function (el) {
      el.addEventListener('click', closeMaterialReviewModal);
    });
    document.addEventListener('keydown', onReviewKey, true);
    document.querySelector('#material-review-modal .qs-box').focus();
    bindMaterialReviewEvents();
    renderReviewTicket();
  }

  // 맞춤 분석 비용: 최소 장수 + (고른 지원자 자료 종류 수 - 1), 최대 장수까지 (TicketService.companyFitCost와 같은 계산)
  function companyFitCost(costs) {
    var kinds = {};
    Object.keys(state.selectedDocIds).forEach(function (id) {
      var doc = state.allDocs.filter(function (d) { return String(d.documentId) === String(id); })[0];
      if (doc && CANDIDATE_TYPES.indexOf(doc.documentType) >= 0) kinds[doc.documentType] = true;
    });
    var count = Object.keys(kinds).length;
    return Math.min(costs.companyFitMin + Math.max(0, count - 1), costs.companyFitMax);
  }

  function renderReviewTicket() {
    var box = document.getElementById('material-review-ticket');
    var action = document.getElementById('material-review-action');
    if (!box || !action) return;
    DF.api('/tickets/me').then(function (tickets) {
      if (!document.getElementById('material-review-ticket')) return;
      var cost = companyFitCost(tickets.costs);
      var short = cost - tickets.balance;
      box.innerHTML = '<p class="tk-title">' + (short > 0 ? '티켓이 ' + short + '장 부족해요' : '분석에 티켓 ' + cost + '장을 사용해요') + '</p>' +
        '<p class="tk-what">고른 지원 자료 종류에 따라 ' + tickets.costs.companyFitMin + '~' + tickets.costs.companyFitMax + '장이 들어요.' +
        (short > 0 ? '' : ' 분석이 실패하면 티켓은 돌려드려요.') + '</p>' +
        '<dl class="tk-balance"><div><dt>지금</dt><dd>' + tickets.balance + '장</dd></div>' +
        '<div><dt>' + (short > 0 ? '필요' : '분석 후') + '</dt><dd>' + (short > 0 ? cost : tickets.balance - cost) + '장</dd></div></dl>';
      if (short > 0) {
        action.innerHTML = '<a class="fl-btn fl-btn--navy" href="' + window.JobPuzzleRoutes.path('/tickets') + '">티켓 충전하러 가기</a>';
      } else {
        var btn = document.getElementById('material-review-confirm');
        btn.disabled = false;
        btn.textContent = cost + '장 쓰고 분석 시작';
      }
    }).catch(function () {
      box.textContent = '티켓 잔액을 확인하지 못했어요. 분석 화면에서 잔액이 모자라면 다시 알려드려요.';
      var btn = document.getElementById('material-review-confirm');
      if (btn) btn.disabled = false;
    });
  }

  function submitAnalysisCase() {
    var confirmBtn = document.getElementById('material-review-confirm');
    if (confirmBtn) { confirmBtn.disabled = true; confirmBtn.textContent = '분석 준비 중...'; }

    var extractionIds = Object.keys(state.selectedDocIds).map(function (id) { return state.selectedExtractionIds[id]; });

    DF.api('/analysis-cases', { method: 'POST', json: { jobCategoryId: parseInt(state.selectedJobCategoryId, 10) } })
      .then(function (analysisCase) {
        state.analysisCaseId = analysisCase.analysisCaseId;
        return extractionIds.reduce(function (chain, extractionId) {
          return chain.then(function () {
            return DF.api('/analysis-cases/' + state.analysisCaseId + '/sources', { method: 'POST', json: { extractionId: extractionId } });
          });
        }, Promise.resolve());
      })
      .then(function () {
        return DF.api('/analysis-cases/' + state.analysisCaseId + '/confirm', { method: 'POST' });
      })
      .then(function () {
        var tracked;
        try {
          tracked = JSON.parse(localStorage.getItem('jobpuzzle_analysis_cases') || '[]');
        } catch (ignore) {
          tracked = [];
        }
        tracked = tracked.filter(function (item) {
          return String(item.analysisCaseId) !== String(state.analysisCaseId);
        });
        tracked.unshift({
          analysisCaseId: state.analysisCaseId,
          status: 'INPUT_CONFIRMED',
          mainCategory: state.selectedMainCategory,
          subCategory: state.selectedSubCategory,
          createdAt: new Date().toISOString()
        });
        localStorage.setItem('jobpuzzle_analysis_cases', JSON.stringify(tracked.slice(0, 10)));
        sessionStorage.setItem('jobpuzzle_analysis_notice', '분석 요청이 접수되었습니다.');
        window.location.href = window.JobPuzzleRoutes.path('/analysis/' + encodeURIComponent(state.analysisCaseId));
      })
      .catch(function (e) {
        notify('error', e.message);
        if (confirmBtn) { confirmBtn.disabled = false; confirmBtn.textContent = '다시 시도'; }
      });
  }

  var registerReturnFocus = null;

  // ---- material register modal (등록 -> 추출 확인/수정 -> 확정, document-flow.js 재사용) ----
  function openMaterialRegisterModal(documentType) {
    state.registerTargetType = documentType;
    state.materialRegisterMethod = 'file';
    document.getElementById('material-register-title').textContent = DF.CATEGORY_LABEL[documentType] + ' 등록';
    document.getElementById('material-register-name').value = '';
    document.getElementById('material-register-file').value = '';
    document.getElementById('material-register-file-list').textContent = '';
    document.getElementById('material-register-content').value = '';
    document.querySelectorAll('#material-register-modal [data-material-method]').forEach(function (b) {
      var on = b.dataset.materialMethod === 'file';
      b.classList.toggle('is-active', on);
      b.setAttribute('aria-pressed', String(on));
    });
    document.querySelector('[data-material-method-panel="file"]').hidden = false;
    document.querySelector('[data-material-method-panel="text"]').hidden = true;
    document.getElementById('material-register-form').hidden = false;
    document.getElementById('material-register-panel').hidden = true;
    document.getElementById('material-register-panel').innerHTML = '';
    registerReturnFocus = document.activeElement;
    document.getElementById('material-register-modal').hidden = false;
    document.body.classList.add('ih-modal-open');
    document.getElementById('material-register-name').focus();
  }

  function closeMaterialRegisterModal() {
    document.getElementById('material-register-modal').hidden = true;
    document.body.classList.remove('ih-modal-open');
    if (registerReturnFocus && document.contains(registerReturnFocus)) registerReturnFocus.focus();
    registerReturnFocus = null;
  }

  function bindMaterialRegisterModal() {
    document.getElementById('material-register-close').addEventListener('click', closeMaterialRegisterModal);
    document.querySelector('#material-register-modal [data-register-close]').addEventListener('click', closeMaterialRegisterModal);
    document.getElementById('material-register-modal').addEventListener('keydown', function (event) {
      if (event.key === 'Escape') { event.preventDefault(); closeMaterialRegisterModal(); }
    });
    document.getElementById('material-register-cancel').addEventListener('click', closeMaterialRegisterModal);
    document.getElementById('material-register-file').addEventListener('change', function () {
      document.getElementById('material-register-file-list').textContent = DF.describeSelectedFiles(this.files);
    });

    document.querySelectorAll('#material-register-modal [data-material-method]').forEach(function (b) {
      b.addEventListener('click', function () {
        state.materialRegisterMethod = b.dataset.materialMethod;
        document.querySelectorAll('#material-register-modal [data-material-method]').forEach(function (x) {
          x.classList.toggle('is-active', x === b);
          x.setAttribute('aria-pressed', String(x === b));
        });
        document.querySelector('[data-material-method-panel="file"]').hidden = state.materialRegisterMethod !== 'file';
        document.querySelector('[data-material-method-panel="text"]').hidden = state.materialRegisterMethod !== 'text';
      });
    });

    document.getElementById('material-register-submit').addEventListener('click', function () {
      var name = document.getElementById('material-register-name').value.trim();
      if (!name) { notify('warning', '자료명을 입력해주세요.'); return; }

      var opts = { category: state.registerTargetType, name: name, method: state.materialRegisterMethod };
      if (state.materialRegisterMethod === 'file') {
        var files = document.getElementById('material-register-file').files;
        if (!files.length) { notify('warning', '파일을 선택해주세요.'); return; }
        opts.files = files;
      } else {
        var content = document.getElementById('material-register-content').value.trim();
        if (!content) { notify('warning', '내용을 입력해주세요.'); return; }
        opts.content = content;
      }

      DF.registerDocument(opts).then(function (doc) {
        document.getElementById('material-register-form').hidden = true;
        var panelEl = document.getElementById('material-register-panel');
        panelEl.hidden = false;

        var panel = DF.createExtractPanel(panelEl, {
          onConfirmed: function (extraction) {
            state.selectedDocIds[doc.documentId] = true;
            state.selectedExtractionIds[doc.documentId] = extraction.extractionId;
            closeMaterialRegisterModal();
            loadMaterialSelectData().then(render);
          }
        });
        panel.load(doc.documentId);
        if (opts.method === 'file') {
          DF.pollExtraction(doc.documentId, function () { panel.reload(); });
        }
      }).catch(function (e) { notify('error', e.message); });
    });
  }

  // ---- step: connection result (더미 - JSON-01/02/05 연동 전까지 유지) ----
  // ---- events ----
  function bindStepEvents() {
    document.querySelectorAll('[data-weak-tag]').forEach(function (el) {
      el.addEventListener('click', function () {
        var tag = el.dataset.weakTag;
        state.selectedWeaknessTag = state.selectedWeaknessTag === tag ? null : tag;
        render();
        var again = document.querySelector('[data-weak-tag="' + (window.CSS && CSS.escape ? CSS.escape(tag) : tag) + '"]');
        if (again) again.focus();
      });
    });

    document.querySelectorAll('[data-generate-weakness]').forEach(function (button) {
      button.addEventListener('click', function (event) {
        event.stopPropagation();
        state.selectedWeaknessTag = button.dataset.generateWeakness;
        var tag = state.selectedWeaknessTag;
        window.TicketConfirm.open({
          usage: 'WEAKNESS_REVIEW',
          what: '고른 약점을 집중해서 확인하는 질문을 만들어요.',
          confirmLabel: '질문 만들기',
          onConfirm: function () { if (window.InterviewLive) window.InterviewLive.startWeakness(tag); }
        });
      });
    });

    bindAction('backToMode', function () { state.step = 'mode'; render(); });
    bindAction('historyBack', function () { window.history.back(); });
    bindAction('retryMaterials', reloadMaterials);
    if (state.step === 'materialSelect') bindMaterialSelectEvents();
  }

  function bindMaterialSelectEvents() {
    var mainSel = document.getElementById('material-main-select');
    if (mainSel) mainSel.addEventListener('change', function () {
      state.selectedMainCategory = mainSel.value || null;
      state.selectedSubCategory = null;
      state.selectedJobCategoryId = null;
      render();
    });
    var subSel = document.getElementById('material-sub-select');
    if (subSel) subSel.addEventListener('change', function () {
      state.selectedSubCategory = subSel.value || null;
      state.selectedJobCategoryId = null;
      render();
    });
    var levelSel = document.getElementById('material-level-select');
    if (levelSel) levelSel.addEventListener('change', function () {
      state.selectedJobCategoryId = levelSel.value || null;
      if (state.materialError) { state.materialError = null; render(); }
    });

    document.querySelectorAll('[data-toggle-doc]').forEach(function (el) {
      el.addEventListener('click', function () {
        var id = el.dataset.toggleDoc;
        if (state.selectedDocIds[id]) delete state.selectedDocIds[id]; else state.selectedDocIds[id] = true;
        state.materialError = null;
        render();
        var again = document.querySelector('[data-toggle-doc="' + id + '"]');
        if (again) again.focus();
      });
    });
    document.querySelectorAll('[data-open-register]').forEach(function (el) {
      el.addEventListener('click', function () { openMaterialRegisterModal(el.dataset.openRegister); });
    });
    var nextBtn = document.getElementById('material-select-next');
    if (nextBtn) nextBtn.addEventListener('click', state.materialStage === 'company' ? goCandidateStage : goMaterialReview);
  }

  function bindMaterialReviewEvents() {
    var confirmBtn = document.getElementById('material-review-confirm');
    if (confirmBtn) confirmBtn.addEventListener('click', submitAnalysisCase);
  }

  function bindAction(name, fn) {
    document.querySelectorAll('[data-action="' + name + '"]').forEach(function (el) { el.addEventListener('click', fn); });
  }

  document.addEventListener('DOMContentLoaded', function () {
    bindMaterialRegisterModal();
    // 분석 결과에서 바로 질문 고르기, 진행 중 면접 이어 하기로 들어온 경우에는 홈을 그리지 않는다
    var params = new URLSearchParams(window.location.search);
    if (params.get('analysisCaseId') || params.get('resumeSessionId')) return;
    render();
  });
  window.InterviewPage = { showModes: showModeStep, openWeakness: openWeakness, openCustom: openCustom };
})();
