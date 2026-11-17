// dashboard.js — 준비 현황(6단계 + 퍼즐), 요약 카드, 다음 할 일, 면접·약점·과제 탭. 모두 기존 API 데이터로 판정한다.
(function () {
  'use strict';
  var U = window.JPUI, route = window.JobPuzzleRoutes.path;
  var ICON = {
    play: '<svg viewBox="0 0 24 24"><polygon points="6 4 19 12 6 20 6 4"/></svg>',
    tag: '<svg viewBox="0 0 24 24"><path d="M20.6 13.4 13.4 20.6a2 2 0 0 1-2.8 0L3 13V3h10l7.6 7.6a2 2 0 0 1 0 2.8z"/></svg>',
    list: '<svg viewBox="0 0 24 24"><path d="M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01"/></svg>',
    next: '<svg viewBox="0 0 24 24"><path d="M5 12h14M13 6l6 6-6 6"/></svg>'
  };
  // 준비 6단계: 순서와 상관없이 조건을 채우면 완료, '다음'은 첫 번째 미완료 단계
  var STEPS = [
    { name: '희망 직무 정하기', href: '/settings', test: function (d) { return !!(d.user && d.user.defaultJobCategoryId); } },
    { name: '내 자료 확정하기', href: '/my-data', test: function (d) { return d.docs.some(function (x) { return x.latestVersionStatus === 'CONFIRMED'; }); } },
    { name: '채용공고 분석하기', href: '/interview', test: function (d) { return d.analyses.length > 0; } },
    { name: '모의면접 시작하기', href: '/interview', test: function (d) { return d.active.length + d.history.length > 0; } },
    { name: '면접·답변 평가 받기', href: '/reports', test: function (d) { return d.history.length > 0; } },
    { name: '약점 보완하기', href: '/action-plans', test: function (d) { return d.solved.length > 0 || d.plans.some(function (p) { return p.status === 'DONE'; }); } }
  ];
  var ORDER = [0, 3, 1, 4, 2, 5];   // 퍼즐 조각을 맞추는 순서(J가 있는 가운데 열부터)
  var ROAM_SLOTS = [[.14, .1], [.26, .55], [.02, .45], [.3, -.12], [.12, .72], [-.04, .05]];
  var data = { user: null, jobs: [], docs: [], analyses: [], active: [], history: [], open: [], solved: [], plans: [], scores: {} };
  var pages = {};
  var today = new Date(); today.setHours(0, 0, 0, 0);
  var TODAY = today.getFullYear() + '-' + String(today.getMonth() + 1).padStart(2, '0') + '-' + String(today.getDate()).padStart(2, '0');

  function get(p, fallback) { return U.api(route(p)).catch(function () { return fallback; }); }
  function dateDot(v) { if (!v) return ''; var d = new Date(v); return d.getFullYear() + '.' + String(d.getMonth() + 1).padStart(2, '0') + '.' + String(d.getDate()).padStart(2, '0'); }
  function pending(p) { return p.status !== 'DONE'; }
  function overdue(p) { return pending(p) && p.deadline && p.deadline < TODAY; }
  function careerLabel(v) { return { NEW: '신입', EXPERIENCED: '경력', ANY: '경력 무관' }[v] || ''; }

  // ── 상단: 이름 · 희망 직무 · 준비 단계 · 퍼즐 ──
  var canvas = document.querySelector('.dhead__puzzle'), look = null, narrow = window.matchMedia('(max-width: 960px)');
  function renderHead() {
    var u = data.user;
    if (u) document.getElementById('dash-title').textContent = (u.name || u.loginId) + '님의 준비 현황';
    var job = u && data.jobs.filter(function (j) { return j.jobCategoryId === u.defaultJobCategoryId; })[0];
    document.getElementById('dash-job').textContent = job
      ? [job.mainCategory, job.subCategory].join(' › ') + (careerLabel(job.careerLevel) ? ' · ' + careerLabel(job.careerLevel) : '')
      : '아직 정하지 않았어요';
    var done = STEPS.map(function (s) { return s.test(data); });
    var count = done.filter(Boolean).length, next = done.indexOf(false);
    document.querySelector('[data-steps-count]').textContent = count + ' / ' + STEPS.length;
    document.querySelector('[data-steps-next]').textContent = next < 0 ? ' · 모든 단계를 마쳤어요' : ' · 다음: ' + STEPS[next].name;
    document.querySelector('[data-steps]').innerHTML = STEPS.map(function (s, i) {
      var st = done[i] ? 'done' : i === next ? 'next' : 'todo';
      return '<li class="dstep dstep--' + st + '"' + (st === 'next' ? ' aria-current="step"' : '') + '><a href="' + route(s.href) + '">' +
        '<span class="dstep__no">' + (done[i] ? '✓' : i + 1) + '</span>' + s.name + '</a></li>';
    }).join('');
    look = look || (canvas && canvas._puzzleLook);
    if (!look) return;
    var placed = ORDER.slice(0, count), slot = 0;
    look.setRoam(ORDER.map(function (_, p) { return placed.indexOf(p) >= 0 ? [0, 0] : narrow.matches ? [0, 4] : ROAM_SLOTS[slot++]; }), .75, true);
    look.setAssembled(placed);
  }

  function initPuzzle() {
    look = canvas && canvas._puzzleLook;
    if (!look) return;
    look.setAnchor(document.querySelector('.dhead__anchor'));
    look.setGhost('grid');
    look.setDragMode('tilt');
    look.setClickable(false);
    look.setFloatSpeed(.4);
    if (window.JP_LOGO_MASK) {
      var logo = new Image();
      logo.onload = function () { look.setEmblem(logo, .8); };
      logo.src = window.JP_LOGO_MASK;
    }
    narrow.addEventListener('change', renderHead);
  }

  // ── 요약 카드 ──
  function renderStats() {
    var set = function (k, v) { document.querySelector('[data-stat="' + k + '"]').textContent = v; };
    set('active', data.active.length);
    set('open', data.open.length);
    set('overdue', data.plans.filter(overdue).length);
    set('nodue', data.plans.filter(function (p) { return pending(p) && !p.deadline; }).length);
  }

  // ── 다음 해야 할 일 ──
  function renderTodos() {
    var todos = [], a = data.active[0];
    if (a) todos.push({ icon: 'play', title: '진행 중인 면접 마무리하기', desc: U.mode(a.mode)[0] + ' · 질문 ' + (a.questionCount || 0) + '개 중 ' + (a.completedQuestionCount || 0) + '개 답변 완료',
      cta: '<a href="' + route('/interview?resumeSessionId=' + a.sessionId) + '" class="dbtn dbtn--sky">이어서 진행하기</a>' });
    if (data.open.length) todos.push({ icon: 'tag', title: '약점 태그 해결하기',
      desc: data.open.slice(0, 2).map(function (t) { return '#' + (t.displayName || U.weakLabel(t.tag)); }).join(' · ') + ' 연습이 필요해요',
      cta: '<a href="' + route('/interview?mode=weakness') + '" class="dbtn dbtn--line">약점 보완 연습하기</a>' });
    var left = data.plans.filter(pending).length;
    if (left) todos.push({ icon: 'list', title: '미완료 과제 처리하기', desc: left + '건의 과제가 남아있어요',
      cta: '<button type="button" class="dbtn dbtn--line" data-goto="tasks">과제 목록 보기</button>' });
    if (!todos.length) {
      var next = STEPS.filter(function (s) { return !s.test(data); })[0];
      todos.push(next
        ? { icon: 'next', title: next.name, desc: '다음 준비 단계예요.', cta: '<a href="' + route(next.href) + '" class="dbtn dbtn--sky">시작하기</a>' }
        : { icon: 'next', title: '새 면접으로 연습 이어가기', desc: '모든 준비 단계를 마쳤어요.', cta: '<a href="' + route('/interview') + '" class="dbtn dbtn--sky">면접 준비</a>' });
    }
    document.getElementById('todo-list').innerHTML = todos.map(function (t) {
      return '<div class="nx"><span class="nx__icon">' + ICON[t.icon] + '</span><div class="nx__body"><p class="nx__title">' + U.esc(t.title) +
        '</p><p class="nx__desc">' + U.esc(t.desc) + '</p></div>' + t.cta + '</div>';
    }).join('');
  }

  // ── 탭 목록(페이지 나눔) ──
  var LISTS = {
    doing: { size: 2, empty: '진행 중인 면접이 없어요.', rows: function () { return data.active; }, row: function (s) {
      var p = s.questionCount ? Math.round((s.completedQuestionCount || 0) / s.questionCount * 100) : 0;
      return '<a href="' + route('/interview?resumeSessionId=' + s.sessionId) + '" class="srow srow--progress"><div style="flex:1"><p class="srow__title">' + U.mode(s.mode)[0] + ' 모의면접 #' + s.sessionId + '</p>' +
        '<p class="srow__meta">' + dateDot(s.lastActivityAt || s.createdAt) + ' · 질문 ' + (s.questionCount || 0) + '개 중 ' + (s.completedQuestionCount || 0) + '개 답변 완료</p>' +
        '<div class="progress"><i style="width:' + p + '%"></i></div></div><span class="dbtn dbtn--sky">이어하기</span></a>';
    } },
    done: { size: 4, empty: '완료된 면접이 없어요.', rows: function () { return data.history; }, row: function (s) {
      var sc = data.scores[s.sessionId];
      return '<a href="' + route('/interview-results?sessionId=' + s.sessionId) + '" class="srow"><div><p class="srow__title">' + U.mode(s.mode)[0] + ' 모의면접 #' + s.sessionId + '</p>' +
        '<p class="srow__meta">' + dateDot(s.completedAt) + ' · 질문 ' + (s.questionCount || 0) + '개</p></div>' +
        '<span class="srow__score">' + (sc && sc.overallScore != null ? sc.overallScore + '점' : '-') + '</span></a>';
    } },
    open: { size: 3, empty: '미해결 약점 태그가 없어요.', rows: function () { return data.open; }, row: function (t) {
      var last = (t.recentOccurrences || [])[0];
      return '<div class="tag tag--warn"><span class="tag__name">#' + U.esc(t.displayName || U.weakLabel(t.tag)) + '</span><p class="tag__desc">' + U.esc(t.description || '') + '</p>' +
        '<p class="tag__meta">' + (last && last.score != null ? '최근 시도 ' + last.score + '점 · ' : '') + '기준 70점</p></div>';
    } },
    solved: { size: 3, empty: '아직 해결된 약점 태그가 없어요.', rows: function () { return data.solved; }, row: function (t) {
      return '<div class="tag tag--ok"><span class="tag__name">#' + U.esc(t.displayName || U.weakLabel(t.tag)) + ' ✓</span><p class="tag__desc">' + U.esc(t.description || '') + '</p><p class="tag__meta">해결 완료</p></div>';
    } }
  };
  function pagerHtml(total, cur) {
    if (total <= 1) return '';
    var h = '<button type="button" data-page="' + (cur - 1) + '" aria-label="이전"' + (cur === 0 ? ' disabled' : '') + '>‹</button>';
    for (var i = 0; i < total; i++) h += '<button type="button" data-page="' + i + '"' + (i === cur ? ' aria-current="page"' : '') + '>' + (i + 1) + '</button>';
    return h + '<button type="button" data-page="' + (cur + 1) + '" aria-label="다음"' + (cur === total - 1 ? ' disabled' : '') + '>›</button>';
  }
  function renderList(k) {
    var cfg = LISTS[k], rows = cfg.rows(), body = document.querySelector('[data-list="' + k + '"]');
    var total = Math.ceil(rows.length / cfg.size), cur = Math.min(pages[k] || 0, Math.max(0, total - 1));
    pages[k] = cur;
    body.innerHTML = rows.length ? rows.slice(cur * cfg.size, (cur + 1) * cfg.size).map(cfg.row).join('') : '<p class="dlist__empty">' + cfg.empty + '</p>';
    var pager = body.nextElementSibling;
    pager.innerHTML = pagerHtml(total, cur);
    pager.dataset.pagerFor = k;
  }

  // ── 과제 탭 ──
  function taskRow(p) {
    var status, due;
    if (!pending(p)) { status = '<span class="pill pill--done">완료</span>'; due = (p.completedAt ? dateDot(p.completedAt) + ' 완료' : '완료'); }
    else if (!p.deadline) { status = '<span class="pill pill--none">마감일 미지정</span>'; due = '마감일 없음'; }
    else {
      var days = Math.round((new Date(p.deadline + 'T00:00:00') - today) / 864e5);
      status = days < 0 ? '<span class="pill pill--overdue">마감 초과</span>' : '<span class="pill pill--active">진행 중</span>';
      due = '마감 ' + p.deadline.replace(/-/g, '.') + ' · ' + (days < 0 ? -days + '일 지남' : days === 0 ? '오늘' : 'D-' + days);
    }
    var id = p.actionPlanId;
    return '<div class="task"><div><p class="task__title' + (pending(p) ? '' : ' task__title--done') + '">' + U.esc(p.suggestion || '보완 과제') + '</p><p class="task__due">' + due + '</p></div>' +
      '<div class="task__actions">' + status +
      (pending(p) ? '<button class="dbtn dbtn--line" type="button" data-due="' + id + '">' + (p.deadline ? '마감일 재지정' : '마감일 지정') + '</button>' +
        '<input type="date" class="ap-due__input" data-due-input="' + id + '" value="' + (p.deadline || '') + '" aria-label="마감일" tabindex="-1">' : '') +
      '<a class="dbtn dbtn--line" href="' + route('/action-plans?analysisCaseId=' + encodeURIComponent(p.analysisCaseId)) + '">상세보기</a>' +
      (pending(p) ? '<button class="dbtn dbtn--sky" type="button" data-complete="' + id + '">완료</button>' : '') + '</div></div>';
  }
  function renderTasks() {
    var el = document.getElementById('task-list');
    var list = data.plans.slice().sort(function (a, b) {
      return (pending(b) - pending(a)) || (overdue(b) - overdue(a)) || String(a.deadline || '9').localeCompare(String(b.deadline || '9'));
    });
    el.innerHTML = list.length
      ? list.slice(0, 8).map(taskRow).join('') + (list.length > 8 ? '<p style="margin:14px 0 0; text-align:center"><a class="dbtn dbtn--line" href="' + route('/action-plans') + '">전체 ' + list.length + '개 과제 보기</a></p>' : '')
      : '<p class="dlist__empty">아직 과제가 없어요. 지원 분석을 마치면 보완 과제가 생겨요.</p>';
  }

  function renderAll() {
    renderHead(); renderStats(); renderTodos(); renderTasks();
    Object.keys(LISTS).forEach(renderList);
  }

  function showTab(name) {
    document.querySelectorAll('.dtab').forEach(function (t) {
      var on = t.dataset.tab === name;
      t.setAttribute('aria-selected', String(on));
      document.getElementById('p-' + t.dataset.tab).hidden = !on;
    });
  }

  function findPlan(id) { return data.plans.filter(function (p) { return String(p.actionPlanId) === String(id); })[0]; }
  function patchPlan(id, url, body, apply) {
    U.api(route('/action-plan/' + encodeURIComponent(id) + url), { method: 'PATCH', json: body })
      .then(function (u) { apply(findPlan(id), u || {}); renderAll(); })
      .catch(function (e) { if (window.showToast) window.showToast('error', e.message); });
  }

  document.addEventListener('click', function (e) {
    var b = e.target.closest('button');
    if (!b) return;
    if (b.dataset.tab) showTab(b.dataset.tab);
    else if (b.dataset.goto) { showTab(b.dataset.goto); document.querySelector('.dtabs').scrollIntoView({ behavior: 'smooth' }); }
    else if (b.dataset.page && b.parentNode.dataset.pagerFor && !b.disabled) { pages[b.parentNode.dataset.pagerFor] = +b.dataset.page; renderList(b.parentNode.dataset.pagerFor); }
    else if (b.dataset.complete) patchPlan(b.dataset.complete, '/completion', { completed: true }, function (p, u) { p.status = u.status || 'DONE'; p.completedAt = u.completedAt; });
    else if (b.dataset.due) {
      var input = document.querySelector('[data-due-input="' + b.dataset.due + '"]');
      try { input.showPicker(); } catch (err) { input.focus(); }
    }
  });
  document.addEventListener('change', function (e) {
    var id = e.target.dataset && e.target.dataset.dueInput;
    if (id) patchPlan(id, '/deadline', { deadline: e.target.value || null }, function (p, u) { p.deadline = u.deadline !== undefined ? u.deadline : (e.target.value || null); });
  });

  document.addEventListener('app-user-loaded', function (e) { data.user = e.detail; renderAll(); });

  document.addEventListener('DOMContentLoaded', function () {
    initPuzzle();
    renderAll();
    Promise.all([
      get('/job-category', []), get('/documents?size=100', { content: [] }), get('/analysis-cases/completed', []),
      get('/interview-sessions/active-list', []), get('/interview-sessions/history', []),
      get('/interview-weakness-tags/details', []), get('/interview-weakness-tags/resolved', []), get('/action-plan', [])
    ]).then(function (r) {
      data.jobs = r[0] || []; data.docs = (r[1] && r[1].content) || []; data.analyses = r[2] || [];
      data.active = r[3] || []; data.history = r[4] || []; data.open = r[5] || []; data.solved = r[6] || []; data.plans = r[7] || [];
      renderAll();
      data.history.forEach(function (s) {
        get('/interview-sessions/' + s.sessionId + '/score', null).then(function (sc) { data.scores[s.sessionId] = sc; renderList('done'); });
      });
    });
  });
})();
