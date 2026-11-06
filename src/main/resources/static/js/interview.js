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

  // ---- (JSON-01/02/05 연동 전까지 임시로 남겨둔 연결 분석 결과 더미) ----
  var CONNECTION_SCENARIOS = {
    full: {
      level: 'HIGH', pct: 96,
      explanation: '공고 요구사항과 등록하신 자료가 대부분 일치해요. 과제 없이 바로 맞춤 질문으로 진행돼요.',
      weakSpots: [],
      breakdown: [
        { label: '대규모 트래픽 처리 경험', level: 'HIGH', basis: '자격 요건에 "RDBMS 설계 및 SQL 작성 경험", 우대사항에 "대규모 트래픽 처리 경험"이 명시되어 있어요.', fit: '포트폴리오에 캐싱·큐 도입으로 응답속도와 처리량을 개선한 수치가 구체적으로 정리되어 있어요.', gap: '' },
        { label: 'RESTful API 설계 경험', level: 'HIGH', basis: '주요 업무에 "서버 API 설계 및 개발 (RESTful 기반)"이 명시되어 있어요.', fit: '포트폴리오에 API 엔드포인트 구조와 설계 원칙이 프로젝트 사례로 남아있어요.', gap: '' },
        { label: '협업 커뮤니케이션 능력', level: 'HIGH', basis: '인재상에 "협업 커뮤니케이션 역량"이 명시되어 있어요.', fit: '자기소개서와 경험정리 자료에 조율 과정이 구체적으로 서술되어 있어요.', gap: '' }
      ],
      assignments: [], questions: true
    },
    partial: {
      level: 'MEDIUM', pct: 74,
      explanation: '핵심 요구사항은 충족했지만 일부 경험 자료가 부족해요. 부족한 부분은 과제로 보완한 뒤, 나머지는 질문으로 진행돼요.',
      weakSpots: ['장애 대응·모니터링 경험을 뒷받침할 자료가 부족해요', '클라우드 인프라 운영 경험 근거가 일부 누락돼 있어요'],
      breakdown: [
        { label: '대규모 트래픽 처리 경험', level: 'MEDIUM', basis: '우대사항에 "대규모 트래픽 처리 경험"이 명시되어 있어요.', fit: '포트폴리오에 관련 프로젝트 사례가 있어요.', gap: '경험정리 자료에 트래픽 급증 대응 경험이 정리되어 있지 않아요.' },
        { label: '장애 대응·운영 경험', level: 'LOW', basis: '주요 업무에 "서비스 성능 개선 및 모니터링"이 명시되어 있어요.', fit: '', gap: '경험정리 자료에 장애 감지·대응·재발방지 관련 경험이 담겨있지 않아요.' },
        { label: 'RESTful API 설계 경험', level: 'HIGH', basis: '주요 업무에 "서버 API 설계 및 개발"이 명시되어 있어요.', fit: '포트폴리오에 구체적으로 남아있어요.', gap: '' }
      ],
      assignments: [{ title: '장애 대응 경험 정리 과제', desc: '서비스 운영 중 발생했던 장애 상황을 감지 → 대응 → 재발 방지 순서로 정리해보세요.', reason: '장애 대응·운영 경험 근거 자료 부족' }],
      questions: true
    },
    insufficient: {
      level: 'INSUFFICIENT', pct: 30,
      explanation: '', weakSpots: [], breakdown: [
        { label: '대규모 트래픽 처리 경험', level: 'NONE', basis: '우대사항에 "대규모 트래픽 처리 경험"이 명시되어 있어요.', fit: '', gap: '포트폴리오와 경험정리 모두에 관련 내용이 없어요.' },
        { label: '협업 커뮤니케이션 능력', level: 'INSUFFICIENT', basis: '인재상에 "협업 커뮤니케이션 역량"이 명시되어 있어요.', fit: '', gap: '자기소개서·경험정리 모두에 협업 경험 서술이 없어요.' }
      ],
      assignments: [
        { title: '장애 대응 경험 정리 과제', desc: '서비스 운영 중 장애 대응 경험을 정리해보세요.', reason: '장애 대응 관련 근거 자료 없음' },
        { title: '협업 커뮤니케이션 사례 작성', desc: '팀 프로젝트에서 의견 차이를 조율했던 경험을 정리해보세요.', reason: '협업 경험 근거 자료 없음' }
      ],
      questions: false
    }
  };

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
    registerTargetType: null,
    materialRegisterMethod: 'file',
    analysisCaseId: null,

    connectionScenario: 'full',
    questions: [],
    selectedQIdx: null,
    hintOpen: false,
    draftAnswer: ''
  };

  function uniqueInOrder(values) {
    var seen = {};
    var result = [];
    values.forEach(function (v) { if (!seen[v]) { seen[v] = true; result.push(v); } });
    return result;
  }

  function icon(name) {
    var icons = {
      basic: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><path d="M14 2v6h6"/></svg>',
      tag: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M20.59 13.41l-7.17 7.17a2 2 0 0 1-2.83 0L2 12V2h10l8.59 8.59a2 2 0 0 1 0 2.82z"/><circle cx="7" cy="7" r="1"/></svg>',
      sparkle: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 2l1.9 5.8L20 10l-6.1 2.2L12 18l-1.9-5.8L4 10l6.1-2.2z"/></svg>',
      back: '<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M19 12H5M12 19l-7-7 7-7"/></svg>'
    };
    return icons[name] || '';
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
    if (state.step === 'mode') { /* 홈이 직접 그린다 */ }
    else if (state.step === 'weaknessPick') root.innerHTML = renderWeaknessPick();
    else if (state.step === 'materialSelect') root.innerHTML = renderMaterialSelect();
    else if (state.step === 'materialReview') root.innerHTML = renderMaterialReview();
    else if (state.step === 'connectionResult') root.innerHTML = renderConnectionResult();
    else if (state.step === 'list') root.innerHTML = renderQuestionList();
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
    return '<div class="card card--pad-lg">' +
      backBtn('backToMode', '면접 준비로 돌아가기') +
      '<h2 style="font-size:18px; font-weight:700; margin:0 0 4px;">보완할 약점을 골라주세요</h2>' +
      '<p style="font-size:14px; color:#4B5563; margin:0 0 20px;">약점을 누르면 보완 기록을 볼 수 있어요. 질문은 기록 아래 버튼으로 만들어요.</p>' +
      '<div style="display:flex; flex-direction:column; gap:10px;">' +
      state.weaknessTags.map(function (item) {
        var tag = typeof item === 'string' ? item : item.tag;
        var displayName = typeof item === 'string' ? item : item.displayName;
        var description = typeof item === 'string'
          ? '이전 평가에서 확인된 관점을 집중적으로 보완합니다.'
          : item.description;
        var occurrences = typeof item === 'string' ? [] : (item.recentOccurrences || []);
        var historyTitle = (displayName || '약점').replace(/\s*부족\s*$/, '') + ' 보완 이력';
        function historyDate(value) {
          if (!value) return '';
          var date = new Date(value);
          return String(date.getMonth() + 1).padStart(2, '0') + '.' +
            String(date.getDate()).padStart(2, '0');
        }
        function diagnosticChips(values) {
          if (!values || !values.length) return '';
          return '<div class="weak-history-diagnostics"><em>세부 진단</em><div>' +
            values.map(function (value) {
              return '<span>' + esc(value) + '</span>';
            }).join('') + '</div></div>';
        }
        function historyLink(sessionId, label) {
          if (!sessionId) return '';
          return '<a class="weak-history-report" href="' +
            window.JobPuzzleRoutes.path('/interview-results?sessionId=' +
              encodeURIComponent(sessionId)) + '">' + esc(label || '결과 보기') + '</a>';
        }
        function renderOccurrence(occurrence) {
          var occurrenceMode = {
            BASIC: '기본 면접',
            COMPANY_FIT: '맞춤 면접',
            WEAKNESS_REVIEW: '약점 보완'
          }[occurrence.mode] || occurrence.mode || '';
          var attempts = (occurrence.attempts || []).map(function (attempt) {
            var attemptStatus = attempt.status === 'RESOLVED' ? '해결' : '미해결';
            return '<div class="weak-history-attempt"><div class="weak-history-branch">└</div>' +
              '<div class="weak-history-content"><div class="weak-history-summary"><b>' +
              esc(historyDate(attempt.occurredAt)) + '</b><span>약점 보완</span>' +
              (attempt.score == null ? '' : '<strong>' + esc(attempt.score) + '점</strong>') +
              '<i class="' + (attempt.status === 'RESOLVED' ? 'is-resolved' : 'is-unresolved') +
              '">' + attemptStatus + '</i></div>' +
              diagnosticChips(attempt.diagnostics) +
              historyLink(attempt.sessionId, attempt.resultLabel || '약점 보완 리포트 보기') +
              '</div></div>';
          }).join('');
          return '<article class="weak-history-origin"><div class="weak-history-content">' +
            '<div class="weak-history-summary"><b>' + esc(historyDate(occurrence.occurredAt)) +
            '</b><span>' + esc(occurrenceMode) + '</span>' +
            (occurrence.score == null ? '' : '<strong>' + esc(occurrence.score) + '점</strong>') +
            '<i class="' + (occurrence.status === 'RESOLVED' ? 'is-resolved' : 'is-pending') +
            '">' + (occurrence.status === 'RESOLVED' ? '해결' :
              (occurrence.attempts || []).length ? '보완 중' : '보완 전') + '</i></div>' +
            diagnosticChips(occurrence.diagnostics) +
            historyLink(occurrence.sessionId, occurrence.resultLabel || '맞춤 면접 리포트 보기') +
            '</div>' + attempts + '</article>';
        }
        var activeOccurrences = occurrences.filter(function (occurrence) {
          return occurrence.status !== 'RESOLVED';
        });
        var resolvedOccurrences = occurrences.filter(function (occurrence) {
          return occurrence.status === 'RESOLVED';
        });
        var history = occurrences.length
          ? '<div class="weak-pick-history"><header><span>보완 기록</span><strong>' +
            esc(historyTitle) + '</strong><p>면접별 진단과 보완 결과를 한 흐름으로 확인하세요.</p></header>' +
            (activeOccurrences.length
              ? '<section class="weak-history-group"><h4>현재 보완이 필요한 면접 <b>' +
                activeOccurrences.length + '</b></h4>' +
                activeOccurrences.map(renderOccurrence).join('') + '</section>' : '') +
            (resolvedOccurrences.length
              ? '<section class="weak-history-group weak-history-group--resolved"><h4>최근 해결 이력 <b>' +
                resolvedOccurrences.length + '</b></h4>' +
                resolvedOccurrences.map(renderOccurrence).join('') + '</section>' : '') +
            '</div>'
          : '';
        var expanded = state.selectedWeaknessTag === tag;
        return '<div class="weak-pick-row' + (expanded ? ' is-expanded' : '') +
          '" tabindex="0" role="button" aria-expanded="' + expanded + '" data-weak-tag="' + esc(tag) + '">' +
          '<div><span class="weak-pick-name">#' + esc(displayName) + '</span><p>' +
          esc(description) + '</p></div>' +
          '<span class="badge-pill">보완 필요 ' +
          esc((item.unresolvedCount || item.occurrenceCount || occurrences.length || 1)) +
          '건</span>' + history +
          (expanded ? '<div class="weak-pick-actions"><button type="button" class="btn btn--primary" ' +
            'data-generate-weakness="' + esc(tag) + '">이 약점으로 질문 만들기</button></div>' : '') +
          '</div>';
      }).join('') + '</div></div>';
  }

  function backBtn(action, label) {
    return '<div style="margin-bottom:16px;"><button class="btn-sm" data-action="' + action + '">' + icon('back') + ' ' + label + '</button></div>';
  }

  // ---- step: material select (실제 API 연동) ----
  function enterMaterialSelect() {
    state.step = 'materialSelect';
    render();
    loadMaterialSelectData().then(render);
  }

  function loadMaterialSelectData() {
    return Promise.all([
      DF.api('/documents?size=100'),
      state.jobCategories.length ? Promise.resolve(state.jobCategories) : DF.api('/job-category'),
      DF.api('/user/me')
    ]).then(function (results) {
      state.allDocs = (results[0] && results[0].content) || [];
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
    }).catch(function (e) { notify('error', e.message); });
  }

  function docListHtml(type) {
    var docs = state.allDocs.filter(function (d) { return d.documentType === type; });
    var html = '';
    if (docs.length) {
      html += docs.map(function (d) {
        var confirmed = d.latestVersionStatus === 'CONFIRMED';
        if (!confirmed) {
          var status = DF.statusLabel(null, d.latestVersionStatus);
          return '<div class="material-doc-row" style="opacity:.55; cursor:default;"><div class="material-checkbox"></div>' +
            '<div><p class="material-doc-title">' + esc(d.displayName) + '</p>' +
            '<p class="material-doc-meta" style="color:' + status.color + ';">' + status.text + ' · 내 자료 관리에서 확정해주세요</p></div></div>';
        }
        // 문서 유형별 상한을 넘는 자료는 분석에 쓸 수 없어 목록에서 선택을 막는다 (document-flow.js charLimitFor와 동일 기준)
        var docLimit = DF.charLimitFor(d.documentType);
        var overLimit = typeof d.contentLength === 'number' && d.contentLength > docLimit;
        if (overLimit) {
          return '<div class="material-doc-row" style="opacity:.55; cursor:default;"><div class="material-checkbox"></div>' +
            '<div><p class="material-doc-title">' + esc(d.displayName) + '</p>' +
            '<p class="material-doc-meta" style="color:#B5433D;">' + d.contentLength.toLocaleString() + '/' + docLimit.toLocaleString() + '자 · ' + docLimit.toLocaleString() + '자가 넘어가는 자료는 선택할 수 없습니다</p></div></div>';
        }
        var checked = !!state.selectedDocIds[d.documentId];
        return '<div class="material-doc-row' + (checked ? ' is-checked' : '') + '" data-toggle-doc="' + d.documentId + '"><div class="material-checkbox"></div>' +
          '<div><p class="material-doc-title">' + esc(d.displayName) + '</p>' +
          '<p class="material-doc-meta">' + DF.versionLabel(d.latestMajorVersion, d.latestMinorVersion) + ' · 확정됨</p></div></div>';
      }).join('');
    } else {
      html += '<p class="material-empty">등록된 자료가 없어요</p>';
    }
    html += '<button class="material-register-btn" data-open-register="' + type + '">+ 자료 등록하기</button>';
    return html;
  }

  function renderMaterialSelect() {
    if (!state.docsLoaded) {
      return '<div class="card card--pad-lg">' + backBtn('backToMode', '면접 준비로 돌아가기') + '<p class="text-faint" role="status">자료를 불러오고 있어요.</p></div>';
    }

    var mainCategories = uniqueInOrder(state.jobCategories.map(function (c) { return c.mainCategory; }));
    var mainOpts = '<option value="">선택해주세요</option>' + mainCategories.map(function (m) {
      return '<option value="' + esc(m) + '"' + (m === state.selectedMainCategory ? ' selected' : '') + '>' + esc(m) + '</option>';
    }).join('');

    var subCategories = state.selectedMainCategory
      ? uniqueInOrder(state.jobCategories.filter(function (c) { return c.mainCategory === state.selectedMainCategory; }).map(function (c) { return c.subCategory; }))
      : [];
    var subOpts = '<option value="">' + (state.selectedMainCategory ? '선택해주세요' : '대분류를 먼저 선택해주세요') + '</option>' +
      subCategories.map(function (s) { return '<option value="' + esc(s) + '"' + (s === state.selectedSubCategory ? ' selected' : '') + '>' + esc(s) + '</option>'; }).join('');

    var levelMatches = (state.selectedMainCategory && state.selectedSubCategory)
      ? state.jobCategories.filter(function (c) { return c.mainCategory === state.selectedMainCategory && c.subCategory === state.selectedSubCategory; })
      : [];
    var levelOpts = '<option value="">' + (levelMatches.length ? '선택해주세요' : '중분류를 먼저 선택해주세요') + '</option>' +
      levelMatches.map(function (c) {
        return '<option value="' + c.jobCategoryId + '"' + (String(c.jobCategoryId) === String(state.selectedJobCategoryId) ? ' selected' : '') + '>' + (CAREER_LEVEL_LABEL[c.careerLevel] || c.careerLevel) + '</option>';
      }).join('');

    return '<div class="card card--pad-lg material-select-card">' +
      backBtn('backToMode', '면접 준비로 돌아가기') +
      '<h2 style="font-size:18px; font-weight:700; margin:0 0 4px;">새 공고로 준비할 자료를 골라주세요</h2>' +
      '<p class="material-select-intro">확정된 자료만 선택할 수 있어요. 없으면 새로 등록해주세요</p>' +
      '<div class="material-columns">' +
        '<div><p class="material-column-title">회사 공고 / 회사 정보</p>' +
          COMPANY_TYPES.map(function (type) { return '<div class="material-category"><p style="font-size:12.5px; font-weight:700; margin:0 0 8px;">' + DF.CATEGORY_LABEL[type] + '</p>' + docListHtml(type) + '</div>'; }).join('') +
        '</div>' +
        '<div>' +
          '<div class="material-job-card">' +
            '<p class="material-job-label">희망 직무</p>' +
            '<div class="flex-row gap-8 material-job-selects">' +
              '<select id="material-main-select" class="select-input" style="width:auto; padding:7px 8px; font-size:12px;">' + mainOpts + '</select>' +
              '<select id="material-sub-select" class="select-input" style="width:auto; padding:7px 8px; font-size:12px;"' + (state.selectedMainCategory ? '' : ' disabled') + '>' + subOpts + '</select>' +
              '<select id="material-level-select" class="select-input" style="width:auto; padding:7px 8px; font-size:12px;"' + (levelMatches.length ? '' : ' disabled') + '>' + levelOpts + '</select>' +
            '</div><p class="material-job-note">선택한 직무·경력을 면접 기준으로 사용합니다. 공고의 요구 경력과 다르면 분석 결과에서 차이를 안내해드려요.</p></div>' +
          '<p class="material-column-title">이력서 / 자기소개서 / 포트폴리오 / 경험정리</p>' +
          CANDIDATE_TYPES.map(function (type) { return '<div class="material-category"><p style="font-size:12.5px; font-weight:700; margin:0 0 8px;">' + DF.CATEGORY_LABEL[type] + '</p>' + docListHtml(type) + '</div>'; }).join('') +
        '</div>' +
      '</div>' +
      '<div class="flex-row" style="justify-content:flex-end; margin-top:22px;"><button class="btn btn--primary" id="material-select-next">선택 내용 확인하고 분석 준비</button></div>' +
    '</div>';
  }

  function goMaterialReview() {
    if (!state.selectedJobCategoryId) { notify('warning', '희망 직무를 선택해주세요.'); return; }
    var selectedIds = Object.keys(state.selectedDocIds);
    if (!selectedIds.length) { notify('warning', '자료를 1개 이상 선택해주세요.'); return; }

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
    var jcLabel = jc ? (jc.mainCategory + ' > ' + jc.subCategory + ' · ' + (CAREER_LEVEL_LABEL[jc.careerLevel] || jc.careerLevel)) : '';

    return '<div class="material-review-content">' +
      '<p class="material-review-lead">선택한 자료와 기준을 확인하세요</p>' +
      '<p class="material-review-description">확정하면 아래 자료와 직무 기준으로 분석을 시작합니다. 확정 후에는 구성을 바꿀 수 없어요.</p>' +
      '<div class="material-review-job">' +
        '<p class="material-review-job-label">희망 직무</p>' +
        '<p class="material-review-job-value">' + esc(jcLabel) + '</p>' +
      '</div>' +
      Object.keys(byType).map(function (type) {
        return '<div class="material-review-group"><p class="material-review-group-title">' + DF.CATEGORY_LABEL[type] + '</p>' +
          byType[type].map(function (d) {
            return '<div class="review-card material-review-item"><p class="material-review-item-name">' + esc(d.displayName) + '</p>' +
              '<p class="material-review-item-version">' + DF.versionLabel(d.latestMajorVersion, d.latestMinorVersion) + '</p></div>';
          }).join('') +
        '</div>';
      }).join('') +
    '</div>';
  }

  function renderMaterialReview() {
    return '<div class="card card--pad-lg">' +
      backBtn('backToMaterialSelect', '자료 다시 선택') +
      materialReviewContent() +
      '<div class="flex-row" style="justify-content:flex-end; margin-top:22px;"><button class="btn btn--primary" id="material-review-confirm">이 구성으로 분석 시작</button></div>' +
    '</div>';
  }

  function closeMaterialReviewModal() {
    var modal = document.getElementById('material-review-modal');
    if (modal) modal.remove();
    document.body.classList.remove('has-selection-modal');
  }

  function openMaterialReviewModal() {
    closeMaterialReviewModal();
    document.body.classList.add('has-selection-modal');
    document.body.insertAdjacentHTML('beforeend',
      '<div class="question-selection-modal material-review-modal" id="material-review-modal" role="dialog" aria-modal="true" aria-labelledby="material-review-title">' +
        '<div class="question-selection-backdrop" data-close-material-review></div>' +
        '<section class="question-selection-dialog material-review-dialog">' +
          '<header><div><span class="question-selection-mode">회사 맞춤</span><h2 id="material-review-title">분석 전 마지막 확인</h2>' +
          '<p>선택한 자료와 면접 기준이 맞는지 확인해주세요.</p></div>' +
          '<button type="button" class="question-selection-close" data-close-material-review aria-label="닫기">×</button></header>' +
          '<div class="material-review-scroll">' + materialReviewContent() +
          '<div class="tk-scope tk-inline" id="material-review-ticket" role="status">티켓 잔액을 확인하고 있어요.</div></div>' +
          '<footer><button type="button" class="btn-sm" data-close-material-review>자료 다시 선택</button>' +
          '<span id="material-review-action"><button type="button" class="btn btn--primary" id="material-review-confirm" disabled>이 구성으로 분석 시작</button></span></footer>' +
        '</section>' +
      '</div>');
    document.querySelectorAll('[data-close-material-review]').forEach(function (el) {
      el.addEventListener('click', closeMaterialReviewModal);
    });
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
        action.innerHTML = '<a class="btn btn--primary" href="' + window.JobPuzzleRoutes.path('/tickets') + '" style="text-decoration:none;">티켓 충전하러 가기</a>';
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

  // ---- material register modal (등록 -> 추출 확인/수정 -> 확정, document-flow.js 재사용) ----
  function openMaterialRegisterModal(documentType) {
    state.registerTargetType = documentType;
    state.materialRegisterMethod = 'file';
    document.getElementById('material-register-title').textContent = DF.CATEGORY_LABEL[documentType] + ' 등록';
    document.getElementById('material-register-name').value = '';
    document.getElementById('material-register-file').value = '';
    document.getElementById('material-register-file-list').textContent = '';
    document.getElementById('material-register-content').value = '';
    document.querySelectorAll('#material-register-modal [data-material-method]').forEach(function (b) { b.classList.toggle('is-active', b.dataset.materialMethod === 'file'); });
    document.querySelector('[data-material-method-panel="file"]').hidden = false;
    document.querySelector('[data-material-method-panel="text"]').hidden = true;
    document.getElementById('material-register-form').hidden = false;
    document.getElementById('material-register-panel').hidden = true;
    document.getElementById('material-register-panel').innerHTML = '';
    document.getElementById('material-register-modal').hidden = false;
  }

  function closeMaterialRegisterModal() {
    document.getElementById('material-register-modal').hidden = true;
  }

  function bindMaterialRegisterModal() {
    document.getElementById('material-register-close').addEventListener('click', closeMaterialRegisterModal);
    document.getElementById('material-register-cancel').addEventListener('click', closeMaterialRegisterModal);
    document.getElementById('material-register-file').addEventListener('change', function () {
      document.getElementById('material-register-file-list').textContent = DF.describeSelectedFiles(this.files);
    });

    document.querySelectorAll('#material-register-modal [data-material-method]').forEach(function (b) {
      b.addEventListener('click', function () {
        state.materialRegisterMethod = b.dataset.materialMethod;
        document.querySelectorAll('#material-register-modal [data-material-method]').forEach(function (x) { x.classList.toggle('is-active', x === b); });
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
  var LEVEL_COLOR = { HIGH: '#1E7A4C', MEDIUM: '#185FA5', LOW: '#B5622E', INSUFFICIENT: '#B5433D', NONE: '#8A93A3' };

  function renderConnectionResult() {
    var sc = CONNECTION_SCENARIOS[state.connectionScenario];
    var deg = Math.round(sc.pct / 100 * 360);
    var html = '<div class="card card--pad-lg">' +
      '<p style="font-size:14px; font-weight:700; margin:0 0 4px;">공고 요구사항 · 자료 연결 분석 결과</p>' +
      '<p style="font-size:12.5px; color:#8A93A3; margin:0 0 22px;">자료가 요구사항을 완벽히 충족하면 HIGH, 조금 부족하면 MEDIUM, 많이 부족하면 LOW·INSUFFICIENT·NONE 순으로 낮아져요.</p>' +
      '<div class="flex-row gap-12" style="flex-wrap:wrap; margin-bottom:20px;">' +
        '<div class="donut-lg" style="width:150px; height:150px; background:conic-gradient(' + LEVEL_COLOR[sc.level] + ' ' + deg + 'deg, #E3E7ED 0);"><div class="donut-lg__inner" style="width:104px; height:104px;"><p style="font-size:22px; font-weight:700; margin:0;">' + sc.pct + '%</p><p style="font-size:12px; font-weight:700; margin:2px 0 0; color:' + LEVEL_COLOR[sc.level] + ';">' + sc.level + '</p></div></div>' +
        '<div style="flex:1; min-width:240px;"><p style="font-size:13.5px; font-weight:700; margin:0 0 8px;">' + (sc.explanation || (sc.level === 'INSUFFICIENT' ? '자료가 요구사항을 충족하기엔 많이 부족해요. 과제를 먼저 완료해주세요.' : '')) + '</p></div>' +
      '</div>';

    if (sc.weakSpots.length) {
      html += '<div class="weakspot-box"><p style="font-size:12.5px; font-weight:700; color:#8A5A22; margin:0 0 10px;">어느 부분이 부족한가요</p><div style="display:flex; flex-direction:column; gap:8px;">' +
        sc.weakSpots.map(function (w) { return '<p style="font-size:12px; color:#5B6370; margin:0; line-height:1.6;">• ' + w + '</p>'; }).join('') + '</div></div>';
    }

    html += '<div style="margin-bottom:20px;"><p style="font-size:13px; font-weight:700; margin:0 0 10px;">요구사항별 자료 적합도</p><div style="display:flex; flex-direction:column; gap:8px;">' +
      sc.breakdown.map(function (r) {
        return '<div class="req-row"><div class="flex-row" style="justify-content:space-between; gap:8px; flex-wrap:wrap; margin-bottom:6px;"><p style="font-size:12.5px; font-weight:700; margin:0;">' + r.label + '</p><span style="font-size:11px; font-weight:700; color:' + LEVEL_COLOR[r.level] + ';">' + r.level + '</span></div>' +
          '<p style="font-size:11.5px; color:#8A93A3; margin:0 0 6px; line-height:1.6;">이 요구사항이 나온 근거: ' + r.basis + '</p>' +
          (r.fit ? '<p style="font-size:11.5px; color:#1E7A4C; margin:0 0 3px; line-height:1.6;">✓ ' + r.fit + '</p>' : '') +
          (r.gap ? '<p style="font-size:11.5px; color:#B5433D; margin:0; line-height:1.6;">✕ ' + r.gap + '</p>' : '') +
        '</div>';
      }).join('') + '</div></div>';

    if (sc.assignments.length) {
      html += '<div style="margin-bottom:20px;"><p style="font-size:13px; font-weight:700; margin:0 0 10px;">생성된 과제</p><div style="display:flex; flex-direction:column; gap:8px;">' +
        sc.assignments.map(function (a) { return '<div class="req-row"><p style="font-size:12.5px; font-weight:700; margin:0 0 4px;">' + a.title + '</p><p style="font-size:12px; color:#5B6370; margin:0 0 6px; line-height:1.6;">' + a.desc + '</p><p style="font-size:11px; color:#8A93A3; margin:0;">근거: ' + a.reason + '</p></div>'; }).join('') + '</div></div>';
    }

    if (sc.questions) {
      html += '<div class="flex-row" style="justify-content:space-between; gap:12px; background:#F3F8FD; border:1px solid #DCE7F3; border-radius:10px; padding:14px 16px; flex-wrap:wrap;">' +
        '<span style="font-size:12.5px; color:#185FA5; font-weight:600;">' + (sc.assignments.length ? '부족한 항목은 과제로, 나머지는 질문으로 만들었어요.' : '모든 요구사항이 충분히 확인되어 질문으로 바로 진행해요.') + '</span>' +
        '<button class="btn btn--primary" data-action="proceedToQuestions">질문 생성하기</button></div>';
    } else {
      html += '<div style="text-align:center; padding:16px 0 4px;">' +
        '<p style="font-size:13px; color:#5B6370; margin:0 0 18px;">자료가 너무 부족해 질문을 만들 수 없어요. 과제를 먼저 완료하거나 기본 질문 모드로 진행해주세요.</p>' +
        '<div class="flex-row gap-10" style="justify-content:center;"><button class="btn" style="background:#fff; border:1px solid #E3E7ED;" data-action="goBasicMode">기본 질문 모드로 전환</button><a href="' + window.JobPuzzleRoutes.path('/dashboard') + '" class="btn btn--primary" style="text-decoration:none;">과제 목록 확인하기</a></div></div>';
    }

    html += '</div>';
    return html;
  }

  // ---- step: question list + chat (더미 - JSON-11/09/06 연동 전까지 유지) ----
  var MODE_HINTS = {
    basic: '질문의 의도를 파악하고 상황-행동-결과 순서로 답해보세요.',
    weakness: '이전에 부족했던 부분을 의식하며 구체적인 수치와 본인의 역할을 강조해보세요.',
    custom: '공고 요구사항과 본인 경험을 연결지어 답해보세요.'
  };

  function ensureQuestions() {
    if (state.questions.length) return;
    var base = [
      '자기소개와 함께 이 직무에 지원하게 된 계기를 말씀해주세요.',
      '대규모 트래픽을 처리하기 위해 설계한 경험을 설명해주세요.',
      'RESTful API 설계 시 어떤 원칙을 가장 중요하게 생각하나요?',
      '가장 어려웠던 기술적 문제와 해결 과정을 설명해주세요.',
      '협업 중 의견 충돌이 있었던 경험과 해결 방법을 말씀해주세요.',
      '서비스 운영·장애 대응 경험을 구체적으로 설명해주세요.',
      '본인의 강점과 약점을 각각 말씀해주세요.',
      '팀 프로젝트에서 갈등을 해결한 경험이 있나요?',
      '데이터 시각화 도구를 사용해본 경험이 있나요?',
      '5년 후 본인의 커리어 목표는 무엇인가요?'
    ];
    state.questions = base.map(function (text, idx) {
      return { n: idx + 1, text: text, answered: false, followUp: '방금 답변에서 본인이 직접 판단하거나 결정한 부분은 구체적으로 무엇이었나요?', messages: [{ from: 'bot', text: text }] };
    });
  }

  function renderQuestionList() {
    ensureQuestions();
    var answeredCount = state.questions.filter(function (q) { return q.answered; }).length;
    var total = state.questions.length;
    var finishDisabled = answeredCount < total;
    var modeLabelMap = { basic: '기본 질문 모드', weakness: '약점 보완 · #' + (state.selectedWeaknessTag || ''), custom: '맞춤 면접 질문' };
    var backLabel = state.mode === 'custom' ? '연결 분석 결과로' : '모드 다시 선택';

    var html = '<div class="card card--pad-lg">' +
      '<div class="flex-row" style="justify-content:space-between; margin-bottom:16px; flex-wrap:wrap; gap:12px;">' +
        '<div class="flex-row gap-10">' +
          '<button class="btn-sm" data-action="backFromList">' + icon('back') + ' ' + backLabel + '</button>' +
          '<span class="badge-pill" style="color:#185FA5; background:#EAF2FB;">' + modeLabelMap[state.mode] + '</span>' +
        '</div>' +
        '<div class="flex-row gap-12">' +
          '<span style="font-size:12.5px; color:#5B6370; font-weight:600; white-space:nowrap;">' + answeredCount + '/' + total + ' 답변 완료</span>' +
          '<button class="btn" style="background:' + (finishDisabled ? '#B9C3D6' : '#185FA5') + '; color:#fff;" ' + (finishDisabled ? 'disabled' : '') + ' data-action="finishAll">최종 결과 확인하기</button>' +
        '</div>' +
      '</div>' +
      '<div class="q-gen-layout">' +
        '<div class="q-gen-list">' + state.questions.map(function (q, idx) {
          var active = state.selectedQIdx === idx;
          return '<div class="q-gen-item' + (active ? ' is-active' : '') + '" data-select-q="' + idx + '"><span class="q-gen-item__num">' + q.n + '</span><span class="q-gen-item__text">' + q.text + '</span>' +
            (q.answered ? '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#1E8E5A" stroke-width="3"><path d="M20 6L9 17l-5-5"/></svg>' : '') + '</div>';
        }).join('') + '</div>' +
        '<div class="chat-panel" id="chat-panel"></div>' +
      '</div>' +
    '</div>';
    return html;
  }

  function renderChatPanel() {
    var panel = document.getElementById('chat-panel');
    if (!panel) return;
    if (state.selectedQIdx === null) {
      panel.innerHTML = '<div style="display:flex; flex-direction:column; align-items:center; justify-content:center; height:100%; text-align:center; color:#8A93A3;">' +
        '<p style="font-size:14px; font-weight:600; color:#5B6370; margin:0 0 4px;">질문을 선택해주세요</p><p style="font-size:12.5px; margin:0;">왼쪽 목록에서 질문을 클릭하면 답변을 시작할 수 있어요</p></div>';
      return;
    }
    var q = state.questions[state.selectedQIdx];
    var html = '<div class="flex-row gap-10" style="margin-bottom:14px;"><button class="btn-sm" id="toggle-hint-btn">💡 힌트 보기</button></div>';
    if (state.hintOpen) html += '<div class="hint-box">' + (MODE_HINTS[state.mode] || '') + '</div>';
    html += '<div class="chat-thread" id="chat-thread">' + q.messages.map(function (m) {
      return '<div class="chat-bubble-row chat-bubble-row--' + (m.from === 'user' ? 'user' : 'bot') + '"><div class="chat-bubble chat-bubble--' + (m.from === 'user' ? 'user' : 'bot') + '">' + esc(m.text) + '</div></div>';
    }).join('') + '</div>';
    if (q.answered) {
      html += '<div class="chat-done-banner">✓ 이 질문에 답변을 완료했어요</div>';
    } else {
      html += '<div class="chat-input-area"><textarea id="draft-answer" placeholder="답변을 입력하세요">' + esc(state.draftAnswer) + '</textarea><div class="flex-row" style="justify-content:flex-end;"><button class="btn btn--primary" id="send-answer-btn">전송</button></div></div>';
    }
    panel.innerHTML = html;
    var hintBtn = document.getElementById('toggle-hint-btn');
    if (hintBtn) hintBtn.addEventListener('click', function () { state.hintOpen = !state.hintOpen; renderChatPanel(); });
    var draft = document.getElementById('draft-answer');
    if (draft) draft.addEventListener('input', function () { state.draftAnswer = draft.value; });
    var sendBtn = document.getElementById('send-answer-btn');
    if (sendBtn) sendBtn.addEventListener('click', sendAnswer);
  }

  function sendAnswer() {
    var q = state.questions[state.selectedQIdx];
    if (!state.draftAnswer.trim()) return;
    if (q.messages.length === 1) {
      q.messages.push({ from: 'user', text: state.draftAnswer });
      q.messages.push({ from: 'bot', text: q.followUp });
      state.draftAnswer = '';
      renderChatPanel();
    } else {
      q.messages.push({ from: 'user', text: state.draftAnswer });
      q.answered = true;
      state.draftAnswer = '';
      renderQuestionListInPlace();
      renderChatPanel();
    }
  }

  function renderQuestionListInPlace() {
    var root = document.getElementById('step-root');
    root.innerHTML = renderQuestionList();
    bindStepEvents();
    renderChatPanel();
  }

  // ---- events ----
  function bindStepEvents() {
    document.querySelectorAll('[data-weak-tag]').forEach(function (el) {
      el.addEventListener('click', function (event) {
        if (event.target.closest('a, button')) return;
        state.selectedWeaknessTag = state.selectedWeaknessTag === el.dataset.weakTag
          ? null : el.dataset.weakTag;
        render();
      });
      el.addEventListener('keydown', function (event) {
        if (event.key !== 'Enter' && event.key !== ' ') return;
        if (event.target.closest('a, button')) return;
        event.preventDefault();
        state.selectedWeaknessTag = state.selectedWeaknessTag === el.dataset.weakTag
          ? null : el.dataset.weakTag;
        render();
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
    bindAction('backToMaterialSelect', function () { state.step = 'materialSelect'; render(); });
    bindAction('proceedToQuestions', function () { state.step = 'list'; state.questions = []; state.selectedQIdx = null; render(); renderChatPanel(); });
    bindAction('goBasicMode', function () { state.mode = 'basic'; state.step = 'list'; state.questions = []; state.selectedQIdx = null; render(); renderChatPanel(); });
    bindAction('backFromList', function () {
      if (state.mode === 'custom') { state.step = 'connectionResult'; }
      else { state.step = 'mode'; }
      render();
    });
    bindAction('finishAll', function () {
      try { localStorage.setItem('jobpuzzle_latest_session', JSON.stringify({ title: '모의면접', date: new Date().toISOString().slice(0, 10).replace(/-/g, '.'), score: 78 })); } catch (e) {}
      window.location.href = window.JobPuzzleRoutes.path('/interview-results');
    });

    document.querySelectorAll('[data-select-q]').forEach(function (el) {
      el.addEventListener('click', function () { state.selectedQIdx = parseInt(el.dataset.selectQ, 10); state.hintOpen = false; renderQuestionListInPlace(); });
    });

    if (state.step === 'materialSelect') bindMaterialSelectEvents();
    if (state.step === 'materialReview') bindMaterialReviewEvents();

    if (document.getElementById('chat-panel')) renderChatPanel();
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
    if (levelSel) levelSel.addEventListener('change', function () { state.selectedJobCategoryId = levelSel.value || null; });

    document.querySelectorAll('[data-toggle-doc]').forEach(function (el) {
      el.addEventListener('click', function () {
        var id = el.dataset.toggleDoc;
        if (state.selectedDocIds[id]) delete state.selectedDocIds[id]; else state.selectedDocIds[id] = true;
        render();
      });
    });
    document.querySelectorAll('[data-open-register]').forEach(function (el) {
      el.addEventListener('click', function () { openMaterialRegisterModal(el.dataset.openRegister); });
    });
    var nextBtn = document.getElementById('material-select-next');
    if (nextBtn) nextBtn.addEventListener('click', goMaterialReview);
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
