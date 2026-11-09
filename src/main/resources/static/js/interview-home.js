// interview-home.js — 면접 준비 홈: 공고별 퍼즐판 + 공고 없이 연습 + 티켓 사용 확인 창
(function () {
  'use strict';

  var R = window.JobPuzzleRoutes;
  var CAREER = { NEW: '신입', EXPERIENCED: '경력', ANY: '경력무관' };
  var STEPS = ['분석', '질문', '면접', '리포트'];
  var COLLAPSED_LIMIT = 6;
  var COLLAPSE_FROM = 10;

  var home;
  var data = null;
  var showAllPostings = false;
  var openRecords = {};
  var scores = {}; // sessionId -> 종합 점수(약점 보완은 null)
  var activeTab = null; // 'ongoing' | 'all'

  function esc(value) {
    return String(value == null ? '' : value)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  function api(path) {
    return fetch(R.path(path), { credentials: 'same-origin' }).then(function (response) {
      return response.json().then(function (body) {
        if (!response.ok || !body.success) {
          var error = new Error(body.message || '요청에 실패했어요.');
          error.status = response.status;
          throw error;
        }
        return body.data;
      });
    });
  }

  function day(value) {
    if (!value) return '';
    var d = new Date(value);
    return (d.getMonth() + 1) + '월 ' + d.getDate() + '일';
  }

  function stripExt(name) {
    return String(name || '').replace(/\.(pdf|docx?|hwp|txt|png|jpe?g)$/i, '');
  }

  function time(value) {
    return value ? new Date(value).getTime() : 0;
  }

  function modeLabel(mode) {
    if (mode === 'BASIC') return '기본 질문';
    if (mode === 'COMPANY_FIT') return '맞춤 면접';
    return '약점 보완';
  }

  // ───────────────── 데이터 묶기 ─────────────────
  function groupPostings(d) {
    var groups = {};
    var order = [];
    var caseToGroup = {};
    d.cases.forEach(function (c) {
      var key = c.postingDocumentId ? 'doc-' + c.postingDocumentId : 'case-' + c.analysisCaseId;
      if (!groups[key]) {
        groups[key] = { key: key, cases: [], sessions: [] };
        order.push(key);
      }
      groups[key].cases.push(c);
      caseToGroup[c.analysisCaseId] = key;
    });
    d.sessions.forEach(function (s) {
      var key = s.analysisCaseId && caseToGroup[s.analysisCaseId];
      if (key) groups[key].sessions.push(s);
    });
    return order.map(function (key) { return describePosting(groups[key]); })
      .sort(function (a, b) {
        if (a.busy !== b.busy) return a.busy ? -1 : 1;
        return b.lastActivity - a.lastActivity;
      });
  }

  function describePosting(g) {
    var cases = g.cases.slice().sort(function (a, b) { return time(b.createdAt) - time(a.createdAt); });
    var latest = cases[0];
    var completedCase = cases.filter(function (c) { return c.status === 'COMPLETED'; })[0];
    var readyCase = cases.filter(function (c) { return c.status === 'COMPLETED' && c.questionSetAvailable; })[0];
    var running = latest.status === 'ANALYZING' || latest.status === 'INPUT_CONFIRMED';
    var failed = latest.status === 'FAILED';
    var sessions = g.sessions.slice().sort(function (a, b) {
      return time(b.lastActivityAt || b.completedAt || b.createdAt) - time(a.lastActivityAt || a.completedAt || a.createdAt);
    });
    var active = sessions.filter(function (s) { return s.bucket === 'active'; })[0];
    var review = sessions.filter(function (s) { return s.bucket === 'review'; })[0];
    var done = sessions.filter(function (s) { return s.bucket === 'done'; })[0];

    // 조각 상태: done | next | wait | running | failed
    var steps = [
      completedCase ? 'done' : running ? 'running' : failed ? 'failed' : 'next',
      sessions.length || (completedCase && !completedCase.questionSetAvailable) ? 'done' : readyCase ? 'next' : 'wait',
      // 진행 중인 면접이 있으면 지금 회차 기준으로 보여준다(이전 회차 완료는 기록에 남는다)
      active ? 'next' : done || review ? 'done' : 'wait',
      active ? 'wait' : review ? 'next' : done ? 'done' : 'wait'
    ];

    var action;
    if (active) {
      action = { label: '이어서 하기', href: '/interview?resumeSessionId=' + active.sessionId };
    } else if (review) {
      action = { label: '중간 결과 확인', href: '/interview-results?sessionId=' + review.sessionId };
    } else if (running) {
      action = { label: '분석 상태 확인', href: '/analysis/' + latest.analysisCaseId };
    } else if (readyCase) {
      action = { label: '면접 시작', href: '/interview?analysisCaseId=' + readyCase.analysisCaseId };
    } else if (failed && !completedCase) {
      action = { label: '멈춘 분석 확인', href: '/analysis/' + latest.analysisCaseId };
    } else if (done) {
      action = { label: '리포트 보기', href: '/interview-results?sessionId=' + done.sessionId };
    } else {
      action = { label: '분석 결과 보기', href: '/analysis-results/' + (completedCase || latest).analysisCaseId };
    }

    var status;
    if (active) status = '면접 중 ' + (active.currentQuestionOrder || 1) + '/' + active.questionCount;
    else if (review) status = '결과 확인 전';
    else if (running) status = '분석 중';
    else if (failed && !completedCase) status = '분석 멈춤';
    else if (readyCase) status = '질문 준비됨';
    else if (done) status = '면접 완료';
    else status = '분석 완료';

    var lastActivity = Math.max.apply(null, [time(latest.createdAt)].concat(sessions.map(function (s) {
      return time(s.lastActivityAt || s.completedAt || s.createdAt);
    })));

    return {
      key: g.key,
      title: stripExt(latest.postingName) || '공고 자료 없음',
      meta: [latest.subCategory, CAREER[latest.careerLevel] || latest.careerLevel].filter(Boolean).join(' · '),
      steps: steps,
      action: action,
      status: status,
      busy: !!(active || review || running),
      practiced: !!done,
      doneSessionId: done ? done.sessionId : null,
      lastActivity: lastActivity,
      cases: cases,
      sessions: sessions
    };
  }

  // ───────────────── 렌더 ─────────────────
  // 4단계(분석·질문·면접·리포트)를 작은 점으로: 채움=완료, 강조=지금 단계, 빈 점=아직
  function stepsHtml(steps) {
    var stateText = { done: '완료', next: '지금', wait: '아직', running: '진행 중', failed: '멈춤' };
    var label = steps.map(function (st, i) { return STEPS[i] + ' ' + stateText[st]; }).join(', ');
    return '<span class="ih-dots" role="img" aria-label="' + label + '" title="' + label + '">' +
      steps.map(function (st) { return '<i class="ih-dot is-' + st + '"></i>'; }).join('') + '</span>';
  }

  function recordRows(posting) {
    var rows = [];
    posting.sessions.forEach(function (s) {
      var label = { active: '진행 중', review: '결과 확인 전', done: '완료' }[s.bucket];
      var href = s.bucket === 'active'
        ? '/interview?resumeSessionId=' + s.sessionId
        : '/interview-results?sessionId=' + s.sessionId;
      rows.push({
        at: time(s.lastActivityAt || s.completedAt || s.createdAt),
        html: '<li><span class="ih-rec__what">' + esc(modeLabel(s.mode)) + ' · 질문 ' + esc(s.questionCount) + '개</span>' +
          '<span class="ih-rec__state">' + label + '</span>' +
          '<span class="ih-rec__when">' + esc(day(s.lastActivityAt || s.completedAt || s.createdAt)) + '</span>' +
          '<a class="ih-link" href="' + R.path(href) + '">' + (s.bucket === 'active' ? '이어서 하기' : '결과 보기') + '</a></li>'
      });
    });
    (posting.cases || []).forEach(function (c) {
      var label = { COMPLETED: '분석 완료', ANALYZING: '분석 중', INPUT_CONFIRMED: '분석 준비 중', FAILED: '분석 멈춤' }[c.status];
      var href = c.status === 'COMPLETED' ? '/analysis-results/' + c.analysisCaseId : '/analysis/' + c.analysisCaseId;
      rows.push({
        at: time(c.createdAt),
        html: '<li><span class="ih-rec__what">맞춤 분석</span><span class="ih-rec__state">' + label + '</span>' +
          '<span class="ih-rec__when">' + esc(day(c.createdAt)) + '</span>' +
          '<a class="ih-link" href="' + R.path(href) + '">' + (c.status === 'COMPLETED' ? '분석 결과' : '상태 확인') + '</a></li>'
      });
    });
    rows.sort(function (a, b) { return b.at - a.at; });
    return rows.map(function (r) { return r.html; }).join('');
  }

  function recordsToggle(id, label, cls) {
    return '<button type="button" class="' + cls + '" aria-expanded="' + !!openRecords[id] + '" aria-controls="ih-rec-' + id + '" data-records="' + id + '">' +
      label + '</button>';
  }

  function recordsList(id, posting) {
    return '<ul class="ih-rec" id="ih-rec-' + id + '"' + (openRecords[id] ? '' : ' hidden') + '>' + recordRows(posting) + '</ul>';
  }

  function postingRow(p) {
    var menu = recordsToggle(p.key, '기록 보기', 'ih-menu__item') +
      (p.practiced && data.weakness.length ? '<button type="button" class="ih-menu__item" data-open-weakness>약점 연습</button>' : '');
    return '<li class="ih-row' + (p.busy ? ' is-busy' : '') + '">' +
      '<div class="ih-row__main"><h3 class="ih-row__title">' + esc(p.title) + '</h3>' +
      (p.meta ? '<p class="ih-row__meta">' + esc(p.meta) + '</p>' : '') + '</div>' +
      '<div class="ih-row__state">' + stepsHtml(p.steps) +
      '<span class="ih-row__status">' + esc(p.status) +
      (p.doneSessionId && !p.busy ? '<span data-score-for="' + p.doneSessionId + '">' + scoreText(p.doneSessionId) + '</span>' : '') +
      '</span></div>' +
      '<div class="ih-row__act">' +
      '<a class="ih-btn ' + (p.busy ? 'ih-btn--navy' : 'ih-btn--line') + '" href="' + R.path(p.action.href) + '">' + esc(p.action.label) + '</a>' +
      '<details class="ih-menu"><summary class="ih-menu__btn" aria-label="' + esc(p.title) + ' 메뉴">' + dotsIcon() + '</summary>' +
      '<div class="ih-menu__pop">' + menu + '</div></details>' +
      '</div>' + recordsList(p.key, p) + '</li>';
  }

  function dotsIcon() {
    return '<svg viewBox="0 0 24 24" width="18" height="18" fill="currentColor" aria-hidden="true"><circle cx="5" cy="12" r="1.8"/><circle cx="12" cy="12" r="1.8"/><circle cx="19" cy="12" r="1.8"/></svg>';
  }

  function scoreText(sessionId) {
    var score = scores[sessionId];
    return typeof score === 'number' ? ' · <b>' + score + '점</b>' : '';
  }

  // 카드마다 최근 완료 면접 점수를 따로 불러와 채운다.
  // ponytail: 공고 수만큼 요청, 공고가 많아지면 history 응답에 점수를 실어 보내기
  function loadScores() {
    data.postings.forEach(function (p) {
      var id = p.doneSessionId;
      if (!id || id in scores) return;
      scores[id] = null;
      api('/interview-sessions/' + id + '/score').then(function (summary) {
        scores[id] = summary && typeof summary.overallScore === 'number' ? summary.overallScore : null;
        var slot = home.querySelector('[data-score-for="' + id + '"]');
        if (slot) slot.innerHTML = scoreText(id);
      }).catch(function () { /* 점수는 보조 정보라 못 불러와도 카드는 그대로 */ });
    });
  }

  // ───────────────── 모드 3개 ─────────────────
  function modeCard(opts) {
    return '<li class="ih-mode' + (opts.disabled ? ' is-disabled' : '') + '">' +
      '<div class="ih-mode__top"><h3 class="ih-mode__title">' + esc(opts.title) + '</h3>' +
      (opts.cost ? '<span class="ih-mode__cost">' + esc(opts.cost) + '</span>' : '') + '</div>' +
      '<p class="ih-mode__desc">' + esc(opts.desc) + '</p>' +
      opts.button + '</li>';
  }

  function modesHtml() {
    var t = data.tickets;
    var user = data.user || {};
    var category = (data.categories || []).filter(function (c) {
      return String(c.jobCategoryId) === String(user.defaultJobCategoryId);
    })[0];
    var tags = data.weakness || [];
    var fit = modeCard({
      title: '공고 맞춤 면접',
      cost: t ? '티켓 ' + t.costs.companyFit + '장' : '',
      desc: '채용공고와 내 자료로 그 공고에서 나올 질문을 만들어요.',
      button: '<button type="button" class="ih-btn ih-btn--navy" data-new-posting>공고 고르기</button>'
    });
    var basic = modeCard({
      title: '기본 질문',
      cost: t ? (t.freeBasicAvailable ? '첫 1회 무료' : '티켓 ' + t.costs.basic + '장') : '',
      desc: category
        ? [category.subCategory, CAREER[category.careerLevel]].filter(Boolean).join(' · ') + ' 공통 질문으로 빠르게 연습해요.'
        : '희망 직무를 정하면 그 직무의 공통 질문으로 연습해요.',
      disabled: !category,
      button: category
        ? '<button type="button" class="ih-btn ih-btn--line" data-start-basic>바로 시작</button>'
        : '<a class="ih-btn ih-btn--line" href="' + R.path('/settings') + '">희망 직무 정하기</a>'
    });
    var weak = modeCard({
      title: '약점 보완',
      cost: t ? '티켓 ' + t.costs.weakness + '장' : '',
      desc: tags.length
        ? '답변에서 찾은 약점 ' + tags.length + '개 중 골라 집중 연습해요.'
        : '면접을 한 번 마치면 답변에서 찾은 약점으로 연습할 수 있어요.',
      disabled: !tags.length,
      button: '<button type="button" class="ih-btn ih-btn--line" data-open-weakness' + (tags.length ? '' : ' disabled') + '>약점 고르기</button>'
    });
    return '<ul class="ih-modes">' + fit + basic + weak + '</ul>';
  }

  // ───────────────── 진행 중 / 전체 기록 ─────────────────
  function looseRow(s) {
    var active = s.bucket === 'active';
    var href = active ? '/interview?resumeSessionId=' + s.sessionId : '/interview-results?sessionId=' + s.sessionId;
    return '<li class="ih-row is-busy">' +
      '<div class="ih-row__main"><h3 class="ih-row__title">' + esc(modeLabel(s.mode)) + '</h3>' +
      '<p class="ih-row__meta">' + esc(day(s.lastActivityAt || s.createdAt)) + ' 시작</p></div>' +
      '<div class="ih-row__state"><span class="ih-row__status">' +
      esc(active ? '면접 중 ' + (s.currentQuestionOrder || 1) + '/' + s.questionCount : '결과 확인 전') + '</span></div>' +
      '<div class="ih-row__act"><a class="ih-btn ih-btn--navy" href="' + R.path(href) + '">' +
      (active ? '이어서 하기' : '중간 결과 확인') + '</a><span class="ih-menu-gap"></span></div></li>';
  }

  function preparedRow(set) {
    return '<li class="ih-row">' +
      '<div class="ih-row__main"><h3 class="ih-row__title">' + esc(set.mode === 'WEAKNESS_REVIEW'
        ? '약점 보완 · #' + (set.targetWeaknessDisplayName || set.targetWeaknessTag || '')
        : '기본 질문') + '</h3>' +
      '<p class="ih-row__meta">' + esc(day(set.createdAt)) + ' 만듦 · 질문 ' + esc(set.questionCount) + '개</p></div>' +
      '<div class="ih-row__state"><span class="ih-row__status">시작 전</span></div>' +
      '<div class="ih-row__act"><button type="button" class="ih-btn ih-btn--line" data-open-set="' + esc(set.questionSetId) + '">면접 시작</button><span class="ih-menu-gap"></span></div></li>';
  }

  function ongoingItems() {
    var postings = data.postings.filter(function (p) { return p.busy; }).map(postingRow);
    var loose = data.looseSessions.filter(function (s) { return s.bucket !== 'done'; }).map(looseRow);
    var prepared = (data.prepared || []).map(preparedRow);
    return postings.concat(loose, prepared);
  }

  function ongoingPanel(items) {
    return items.length
      ? '<ul class="ih-list">' + items.join('') + '</ul>'
      : '<p class="ih-empty">진행 중인 면접이 없어요. 위에서 모드를 골라 시작해 보세요.</p>';
  }

  function allPanel() {
    var postings = data.postings;
    var visible = postings;
    var more = '';
    if (!showAllPostings && postings.length >= COLLAPSE_FROM) {
      visible = postings.slice(0, COLLAPSED_LIMIT);
      more = '<button type="button" class="ih-more" data-show-all>공고 ' + (postings.length - COLLAPSED_LIMIT) + '개 더 보기</button>';
    }
    var loose = { key: 'loose', cases: [], sessions: data.looseSessions };
    return (postings.length
        ? '<h3 class="ih-h3">공고별</h3><ul class="ih-list">' + visible.map(postingRow).join('') + '</ul>' + more
        : '<p class="ih-empty">아직 공고로 준비한 면접이 없어요.</p>') +
      (data.looseSessions.length
        ? '<h3 class="ih-h3 ih-h3--gap">공고 없이 한 연습</h3>' +
          '<ul class="ih-rec ih-rec--open">' + recordRows(loose) + '</ul>'
        : '');
  }

  function tabsHtml(ongoing) {
    var tab = function (id, label) {
      var on = activeTab === id;
      return '<button type="button" class="ih-tab" role="tab" id="ih-tab-' + id + '" aria-controls="ih-panel" aria-selected="' + on + '"' +
        (on ? '' : ' tabindex="-1"') + ' data-tab="' + id + '">' + label + '</button>';
    };
    return '<div class="ih-tabs" role="tablist" aria-label="면접 기록">' +
      tab('ongoing', '진행 중 <span class="ih-count">' + ongoing.length + '</span>') +
      tab('all', '전체 기록') + '</div>' +
      '<div class="ih-panel" id="ih-panel" role="tabpanel" aria-labelledby="ih-tab-' + activeTab + '">' +
      (activeTab === 'ongoing' ? ongoingPanel(ongoing) : allPanel()) + '</div>';
  }

  function render() {
    if (!home) return;
    var ongoing = ongoingItems();
    if (!activeTab) activeTab = ongoing.length ? 'ongoing' : 'all';
    home.querySelector('[data-ih-modes]').innerHTML = modesHtml();
    home.querySelector('[data-ih-board]').innerHTML = tabsHtml(ongoing);
    var balance = home.querySelector('[data-ih-balance]');
    if (balance && data.tickets) {
      balance.innerHTML = '보유 티켓 <b>' + esc(data.tickets.balance) + '장</b> · <a href="' + R.path('/tickets') + '">충전</a>';
      balance.hidden = false;
    }
    var jump = home.querySelector('[data-ih-jump]');
    if (jump) {
      jump.hidden = !ongoing.length;
      jump.innerHTML = '진행 중인 면접 <b>' + ongoing.length + '</b>';
    }
  }

  function showTab(id, focus) {
    activeTab = id;
    render();
    if (focus) {
      var btn = document.getElementById('ih-tab-' + id);
      if (btn) btn.focus();
    }
  }

  function renderLoading() {
    var board = home.querySelector('[data-ih-board]');
    home.querySelector('[data-ih-modes]').innerHTML = '<div class="ih-modes" aria-busy="true" aria-label="불러오는 중">' +
      '<div class="ih-skel"></div><div class="ih-skel"></div><div class="ih-skel"></div></div>';
    board.innerHTML = '';
  }

  function renderError(message) {
    home.querySelector('[data-ih-board]').innerHTML =
      '<div class="ih-error" role="alert"><p>' + esc(message || '면접 준비 목록을 불러오지 못했어요.') + '</p>' +
      '<button type="button" class="ih-btn ih-btn--line" data-retry>다시 불러오기</button></div>';
    home.querySelector('[data-ih-modes]').innerHTML = '';
  }

  // ───────────────── 불러오기 ─────────────────
  function load() {
    if (!home) return Promise.resolve();
    renderLoading();
    var optional = function (p, fallback) { return p.catch(function () { return fallback; }); };
    // 약점 상세는 점수 집계 때문에 느려서(수 초) 화면을 먼저 그리고 도착하면 채운다.
    var weakness = optional(api('/interview-weakness-tags/details'), []);
    return Promise.all([
      api('/analysis-cases'),
      api('/interview-sessions/active-list'),
      api('/interview-sessions/review-list'),
      api('/interview-sessions/history'),
      optional(api('/question-sets/prepared'), []),
      optional(api('/tickets/me'), null),
      optional(api('/user/me'), null),
      optional(api('/job-category'), [])
    ]).then(function (r) {
      var sessions = []
        .concat((r[1] || []).map(function (s) { s.bucket = 'active'; return s; }))
        .concat((r[2] || []).map(function (s) { s.bucket = 'review'; return s; }))
        .concat((r[3] || []).map(function (s) { s.bucket = 'done'; return s; }));
      data = {
        cases: r[0] || [],
        sessions: sessions,
        prepared: r[4] || [],
        weakness: [],
        tickets: r[5],
        user: r[6],
        categories: r[7] || []
      };
      data.postings = groupPostings(data);
      data.looseSessions = sessions.filter(function (s) { return !s.analysisCaseId; });
      render();
      loadScores();
      weakness.then(function (tags) {
        if (!tags || !tags.length) return;
        data.weakness = tags;
        render();
      });
    }).catch(function (error) {
      renderError(error.message);
    });
  }

  function show() { if (home) home.hidden = false; }
  function hide() { if (home) home.hidden = true; }

  function bind() {
    home.addEventListener('keydown', function (event) {
      var t = event.target;
      if (!t.hasAttribute || !t.hasAttribute('data-tab')) return;
      if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return;
      event.preventDefault();
      showTab(t.getAttribute('data-tab') === 'ongoing' ? 'all' : 'ongoing', true);
    });
    home.addEventListener('click', function (event) {
      var t = event.target.closest('button, a');
      if (!t || !home.contains(t)) return;
      var menu = t.closest('.ih-menu');
      if (menu) menu.open = false;
      if (t.hasAttribute('data-records')) {
        var id = t.getAttribute('data-records');
        openRecords[id] = !openRecords[id];
        t.setAttribute('aria-expanded', String(openRecords[id]));
        document.getElementById('ih-rec-' + id).hidden = !openRecords[id];
      } else if (t.hasAttribute('data-tab')) {
        showTab(t.getAttribute('data-tab'));
      } else if (t.hasAttribute('data-ih-jump')) {
        showTab('ongoing');
        var section = document.getElementById('ih-sessions');
        if (section) section.scrollIntoView({ behavior: 'smooth', block: 'start' });
      } else if (t.hasAttribute('data-show-all')) {
        showAllPostings = true;
        render();
      } else if (t.hasAttribute('data-retry')) {
        load();
      } else if (t.hasAttribute('data-new-posting')) {
        if (window.InterviewPage) window.InterviewPage.openCustom();
      } else if (t.hasAttribute('data-open-weakness')) {
        if (window.InterviewPage) window.InterviewPage.openWeakness();
      } else if (t.hasAttribute('data-start-basic')) {
        TicketConfirm.open({
          usage: 'BASIC',
          what: '희망 직무의 공통 질문을 만들고 바로 면접을 시작해요. 원치 않는 질문은 면접 중에 건너뛸 수 있어요.',
          confirmLabel: '질문 만들기',
          onConfirm: function () { if (window.InterviewLive) window.InterviewLive.startBasic(); }
        });
      } else if (t.hasAttribute('data-open-set')) {
        if (window.InterviewLive) window.InterviewLive.openQuestionSet(t.getAttribute('data-open-set'));
      }
    });
  }

  // ───────────────── 공통 확인 창 틀 ─────────────────
  // 열면 포커스가 창 안으로 들어가고, Tab은 창 안에서만 돌고, Esc·바깥 누르기로 닫히며, 닫으면 원래 자리로 포커스가 돌아간다.
  var Layer = (function () {
    var layer = null;
    var returnFocus = null;
    var handlers = {};

    function close(fromCancel) {
      if (!layer) return;
      var onCancel = handlers.onCancel;
      layer.remove();
      layer = null;
      handlers = {};
      document.removeEventListener('keydown', onKey, true);
      document.body.classList.remove('ih-modal-open');
      if (returnFocus && returnFocus.focus && document.contains(returnFocus)) returnFocus.focus();
      if (fromCancel && onCancel) onCancel();
    }

    function onKey(event) {
      if (!layer) return;
      if (event.key === 'Escape') { event.preventDefault(); close(true); return; }
      if (event.key !== 'Tab') return;
      var items = Array.prototype.slice.call(layer.querySelectorAll('a[href], button:not([disabled])'));
      if (!items.length) return;
      var first = items[0];
      var last = items[items.length - 1];
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
    }

    function open(html, h) {
      close(false);
      handlers = h || {};
      returnFocus = document.activeElement;
      layer = document.createElement('div');
      layer.className = 'tk-layer';
      layer.innerHTML = '<div class="tk-scrim" data-tk-cancel></div>' +
        '<section class="tk-box" role="dialog" aria-modal="true" aria-labelledby="tk-title" tabindex="-1">' + html + '</section>';
      document.body.appendChild(layer);
      document.body.classList.add('ih-modal-open');
      document.addEventListener('keydown', onKey, true);
      layer.addEventListener('click', function (event) {
        if (event.target.closest('[data-tk-cancel]')) { close(true); return; }
        if (event.target.closest('[data-tk-confirm]')) {
          var fn = handlers.onConfirm;
          handlers.onCancel = null;
          close(false);
          if (fn) fn();
        }
      });
      var box = layer.querySelector('.tk-box');
      focusIn(box);
      return box;
    }

    function focusIn(box) {
      var target = box.querySelector('[data-tk-confirm], .tk-foot a, [data-tk-cancel]:not(.tk-scrim)');
      (target || box).focus();
    }

    function isOpen() { return !!layer; }

    return { open: open, close: close, focusIn: focusIn, isOpen: isOpen };
  })();

  // 일반 확인 창. opts: title, body, confirmLabel, cancelLabel, onConfirm, onCancel
  var Dialog = {
    confirm: function (opts) {
      Layer.open('<h2 class="tk-title" id="tk-title">' + esc(opts.title) + '</h2>' +
        (opts.body ? '<p class="tk-what">' + esc(opts.body) + '</p>' : '') +
        '<div class="tk-foot"><button type="button" class="ih-btn ih-btn--ghost" data-tk-cancel>' +
        esc(opts.cancelLabel || '취소') + '</button>' +
        '<button type="button" class="ih-btn ih-btn--navy" data-tk-confirm>' + esc(opts.confirmLabel || '확인') + '</button></div>',
        { onConfirm: opts.onConfirm, onCancel: opts.onCancel });
    }
  };

  // ───────────────── 티켓 사용 확인 창 ─────────────────
  var TicketConfirm = (function () {
    function costFor(usage, tickets, explicit) {
      if (typeof explicit === 'number') return explicit;
      if (usage === 'BASIC') return tickets.costs.basic;
      if (usage === 'WEAKNESS_REVIEW') return tickets.costs.weakness;
      return tickets.costs.companyFit;
    }

    function body(opts, tickets) {
      var cost = costFor(opts.usage, tickets, opts.cost);
      // shortage: 서버가 잔액 부족(402)을 돌려준 경우. 화면 잔액과 어긋나도 부족 상태로 보여준다.
      var free = !opts.shortage && opts.usage === 'BASIC' && tickets.freeBasicAvailable;
      var short = opts.shortage ? Math.max(cost - tickets.balance, 1) : cost - tickets.balance;
      var title = free ? '첫 기본 면접은 무료예요'
        : opts.shortage ? '티켓이 부족해요'
        : short > 0 ? '티켓이 ' + short + '장 부족해요'
          : '티켓 ' + cost + '장을 사용해요';
      var lines = '<p class="tk-what">' + esc(opts.what) + '</p>';
      if (!free) {
        lines += '<dl class="tk-balance"><div><dt>지금</dt><dd>' + tickets.balance + '장</dd></div>' +
          '<div><dt>' + (short > 0 ? '필요' : '사용 후') + '</dt><dd>' +
          (short > 0 ? cost + '장' : (tickets.balance - cost) + '장') + '</dd></div></dl>';
      }
      if (opts.note && short <= 0) lines += '<p class="tk-note">' + esc(opts.note) + '</p>';
      var primary = short > 0 && !free
        ? '<a class="ih-btn ih-btn--navy" href="' + R.path('/tickets') + '">티켓 충전하러 가기</a>'
        : '<button type="button" class="ih-btn ih-btn--navy" data-tk-confirm>' +
          esc(free ? opts.confirmLabel || '시작하기' : cost + '장 쓰고 ' + (opts.confirmLabel || '시작하기')) + '</button>';
      return '<h2 class="tk-title" id="tk-title">' + esc(title) + '</h2>' + lines +
        '<div class="tk-foot"><button type="button" class="ih-btn ih-btn--ghost" data-tk-cancel>취소</button>' + primary + '</div>';
    }

    // opts: usage, what, confirmLabel, onConfirm, cost(선택), note(선택), shortage(서버 402 때 true)
    function open(opts) {
      var box = Layer.open('<p class="tk-loading" role="status">티켓 잔액을 확인하고 있어요.</p>', { onConfirm: opts.onConfirm });
      api('/tickets/me').then(function (tickets) {
        if (!Layer.isOpen() || !document.contains(box)) return;
        box.innerHTML = body(opts, tickets);
        Layer.focusIn(box);
      }).catch(function (error) {
        if (!Layer.isOpen() || !document.contains(box)) return;
        box.innerHTML = '<h2 class="tk-title" id="tk-title">티켓 잔액을 확인하지 못했어요</h2>' +
          '<p class="tk-what">' + esc(error.message) + ' 잠시 후 다시 시도해 주세요.</p>' +
          '<div class="tk-foot"><button type="button" class="ih-btn ih-btn--ghost" data-tk-cancel>닫기</button></div>';
        Layer.focusIn(box);
      });
    }

    return { open: open, close: function () { Layer.close(false); } };
  })();

  window.JPDialog = Dialog;
  window.JPLayer = Layer;
  window.TicketConfirm = TicketConfirm;
  window.InterviewHome = { load: load, show: show, hide: hide };

  document.addEventListener('DOMContentLoaded', function () {
    home = document.getElementById('interview-home');
    if (!home) return;
    bind();
    var params = new URLSearchParams(window.location.search);
    if (params.get('analysisCaseId') || params.get('resumeSessionId')) {
      hide();
      return;
    }
    load();
  });
})();
