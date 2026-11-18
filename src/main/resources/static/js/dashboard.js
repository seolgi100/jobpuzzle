// dashboard.js — 준비 현황(다음 할 일 + 6단계 퍼즐판), 요약 표, 면접·약점·과제 탭. 모두 기존 API 데이터로 판정한다.
// 화면 기준: design-test/dashboard.html (도록 컨셉)
(function () {
  'use strict';
  var U = window.JPUI, route = window.JobPuzzleRoutes.path, esc = U.esc;
  // 준비 6단계: 순서와 상관없이 조건을 채우면 완료, '지금'은 첫 번째 미완료 단계
  var STEPS = [
    { name: '희망 직무 정하기', href: '/settings', test: function (d) { return !!(d.user && d.user.defaultJobCategoryId); } },
    { name: '내 자료 확정하기', href: '/my-data', test: function (d) { return d.docs.some(function (x) { return x.latestVersionStatus === 'CONFIRMED'; }); } },
    { name: '채용공고 분석하기', href: '/interview', test: function (d) { return d.analyses.length > 0; } },
    { name: '모의면접 시작하기', href: '/interview', test: function (d) { return d.active.length + d.history.length > 0; } },
    { name: '면접·답변 평가 받기', href: '/reports', test: function (d) { return d.history.length > 0; } },
    { name: '약점 보완하기', href: '/action-plans', test: function (d) { return d.solved.length > 0 || d.plans.some(function (p) { return p.status === 'DONE'; }); } }
  ];
  var DONE_ROWS = 5, TASK_ROWS = 8, MINI_MAX = 10;
  var data = { user: null, jobs: [], docs: [], analyses: [], active: [], history: [], open: [], solved: [], plans: [], scores: {} };
  var today = new Date(); today.setHours(0, 0, 0, 0);
  var TODAY = today.getFullYear() + '-' + String(today.getMonth() + 1).padStart(2, '0') + '-' + String(today.getDate()).padStart(2, '0');

  function get(p, fallback) { return U.api(route(p)).catch(function () { return fallback; }); }
  function md(v) { if (!v) return ''; var d = new Date(v); return (d.getMonth() + 1) + '월 ' + d.getDate() + '일'; }
  function pending(p) { return p.status !== 'DONE'; }
  function overdue(p) { return pending(p) && p.deadline && p.deadline < TODAY; }
  function careerLabel(v) { return { NEW: '신입', EXPERIENCED: '경력', ANY: '경력 무관' }[v] || ''; }
  function arrow() { return '<svg class="arrow" aria-hidden="true"><use href="#arrow"/></svg>'; }
  function caseOf(id) { return data.analyses.filter(function (c) { return c.analysisCaseId === id; })[0]; }
  function caseTitle(id) { var c = id && caseOf(id); return c && c.postingName ? c.postingName.replace(/\.[a-z0-9]{2,5}$/i, '') : ''; }
  function sessionName(s) { return caseTitle(s.analysisCaseId) || U.mode(s.mode)[0] + ' 면접'; }
  function sessionSub(s, when) {
    return [U.mode(s.mode)[0] + ' 면접', s.round > 1 ? s.round + '회차' : '', when].filter(Boolean).join(' · ');
  }
  function empty(text) { return '<li class="empty-note">' + text + '</li>'; }

  // ── 머리말 · 다음 할 일 · 준비 단계 ──
  function renderHead() {
    var u = data.user;
    var job = u && data.jobs.filter(function (j) { return j.jobCategoryId === u.defaultJobCategoryId; })[0];
    document.getElementById('dash-job').textContent = job
      ? job.subCategory + (careerLabel(job.careerLevel) ? ' · ' + careerLabel(job.careerLevel) : '')
      : '아직 정하지 않았습니다';

    var done = STEPS.map(function (s) { return s.test(data); });
    var now = done.indexOf(false);
    document.querySelector('[data-steps-count]').textContent = done.filter(Boolean).length;
    var chain = document.querySelector('[data-chain]');
    chain.setAttribute('aria-label', '준비 6단계 중 ' + done.filter(Boolean).length + '단계 완료' + (now < 0 ? '' : ', 지금 ' + (now + 1) + '단계'));
    chain.innerHTML = STEPS.map(function (s, i) {
      var cls = done[i] ? 'done' : i === now ? 'now-piece' : 'todo';
      return '<a href="' + route(s.href) + '" aria-label="' + (i + 1) + '. ' + s.name + '"><use href="#link" class="' + cls + '" x="' + i * 100 + '"/>' +
        '<text x="' + (i * 100 + 50) + '" y="50">' + (i + 1) + '</text></a>';
    }).join('');
    document.querySelector('[data-steps]').innerHTML = STEPS.map(function (s, i) {
      var cls = done[i] ? 'is-done' : i === now ? 'is-now' : '';
      return '<li' + (cls ? ' class="' + cls + '"' : '') + (i === now ? ' aria-current="step"' : '') + '><a href="' + route(s.href) + '">' + s.name + '</a></li>';
    }).join('');
    document.querySelector('[data-step-mobile]').innerHTML = now < 0 ? '모든 단계를 마쳤습니다.' : '지금 단계: <b>' + (now + 1) + '. ' + STEPS[now].name + '</b>';

    var a = data.active[0], left = data.plans.filter(pending).length, n;
    if (a) n = { title: '진행 중인 면접 마무리하기', ctx: '<b>' + esc(sessionName(a)) + '</b> · ' + esc(sessionSub(a, '')) + ' · <span class="nowrap num">질문 ' + (a.currentQuestionOrder || a.completedQuestionCount + 1) + ' / ' + a.questionCount + '</span>',
      cta: '<a class="btn btn-primary" href="' + route('/interview?resumeSessionId=' + a.sessionId) + '">이어서 하기' + arrow() + '</a>' };
    else if (left) n = { title: '남은 과제 처리하기', ctx: '보완 과제 <b class="num">' + left + '</b>건이 남아 있습니다.',
      cta: '<button type="button" class="btn btn-primary" data-goto="tasks">과제 목록 보기' + arrow() + '</button>' };
    else if (now >= 0) n = { title: STEPS[now].name, ctx: '다음 준비 단계입니다.', cta: '<a class="btn btn-primary" href="' + route(STEPS[now].href) + '">시작하기' + arrow() + '</a>' };
    else n = { title: '새 면접으로 연습 이어 가기', ctx: '모든 준비 단계를 마쳤습니다.', cta: '<a class="btn btn-primary" href="' + route('/interview') + '">면접 준비' + arrow() + '</a>' };
    document.getElementById('dash-next').innerHTML = '<p class="label">다음 할 일</p><h2>' + n.title + '</h2><p class="ctx">' + n.ctx + '</p>' + n.cta;
  }

  // ── 요약 표 · 탭 숫자 ──
  function renderStats() {
    var set = function (k, v) { document.querySelector('[data-stat="' + k + '"]').textContent = v; };
    var late = data.plans.filter(overdue).length;
    set('active', data.active.length);
    set('open', data.open.length);
    set('overdue', late);
    set('nodue', data.plans.filter(function (p) { return pending(p) && !p.deadline; }).length);
    document.querySelector('[data-late]').classList.toggle('is-late', late > 0);
    var count = function (k, v) { document.querySelector('[data-count="' + k + '"]').textContent = v; };
    count('sessions', data.active.length + data.history.length);
    count('weakness', data.open.length + data.solved.length);
    count('tasks', data.plans.length);
  }

  // ── 면접 세션 ──
  function mini(filled, now, total) {
    var label = '질문 ' + total + '개 중 ' + filled + '개 답변', h = '';
    for (var i = 0; i < Math.min(total, MINI_MAX); i++) {
      h += '<svg class="piece' + (i < filled ? '' : i === now ? ' is-point' : ' is-todo') + '"><use href="#pc"/></svg>';
    }
    return '<div class="mini" role="img" aria-label="' + label + '">' + h + '</div>';
  }
  function renderSessions() {
    document.querySelector('[data-list="doing"]').innerHTML = data.active.length ? data.active.map(function (s) {
      var at = s.currentQuestionOrder || s.completedQuestionCount + 1;
      return '<li><div><div class="name">' + esc(sessionName(s)) + '</div><div class="sub">' + esc(sessionSub(s, md(s.startedAt || s.createdAt) + ' 시작')) + '</div></div>' +
        mini(s.completedQuestionCount || 0, at - 1, s.questionCount || 0) +
        '<div class="status is-live">면접 중 <span class="num">' + at + ' / ' + s.questionCount + '</span></div>' +
        '<div class="act"><a class="btn btn-primary" href="' + route('/interview?resumeSessionId=' + s.sessionId) + '">이어서 하기</a></div></li>';
    }).join('') : empty('진행 중인 면접이 없습니다.');
    var done = data.history.slice(0, DONE_ROWS);
    document.querySelector('[data-list="done"]').innerHTML = done.length ? done.map(function (s) {
      var sc = data.scores[s.sessionId];
      return '<li><div><div class="name">' + esc(sessionName(s)) + '</div><div class="sub">' + esc(sessionSub(s, md(s.completedAt) + ' 완료')) + '</div></div>' +
        mini(s.questionCount || 0, -1, s.questionCount || 0) +
        '<div class="score num">' + (sc && sc.overallScore != null ? sc.overallScore + '<small>점</small>' : '') + '</div>' +
        '<div class="act"><a class="btn btn-ghost" href="' + route('/interview-results?sessionId=' + s.sessionId) + '">리포트 보기</a></div></li>';
    }).join('') : empty('완료된 면접이 없습니다.');
  }

  // ── 약점 태그 ──
  function weakRow(t, solved) {
    var last = (t.recentOccurrences || [])[0];
    var sub = solved ? '해결 완료' : [last && last.score != null ? '최근 시도 ' + last.score + '점' : '', '기준 70점'].filter(Boolean).join(' · ');
    return '<li><svg class="piece' + (solved ? '' : ' is-empty') + '" aria-hidden="true"><use href="#pc"/></svg>' +
      '<div><div class="name">' + esc(t.displayName || U.weakLabel(t.tag)) + '</div><div class="sub">' + esc(t.description ? t.description + ' · ' + sub : sub) + '</div></div>' +
      '<div class="count">' + (solved ? '' : '보완 필요 <b class="num">' + (t.unresolvedCount || t.occurrenceCount || 0) + '</b>건') + '</div>' +
      '<div class="act">' + (last && last.sessionId ? '<a class="btn btn-ghost" href="' + route('/interview-results?sessionId=' + last.sessionId) + '">최근 면접 보기</a>' : '') + '</div></li>';
  }
  function renderWeakness() {
    document.querySelector('[data-list="open"]').innerHTML = data.open.length ? data.open.map(function (t) { return weakRow(t, false); }).join('') : empty('해결하지 않은 약점이 없습니다.');
    document.querySelector('[data-list="solved"]').innerHTML = data.solved.length ? data.solved.map(function (t) { return weakRow(t, true); }).join('') : empty('아직 해결된 약점이 없습니다.');
  }

  // ── 과제: 목록 + 달력 ──
  function dueHtml(p) {
    var id = p.actionPlanId;
    if (!pending(p)) return '<div class="due">' + (p.completedAt ? md(p.completedAt) + ' 완료' : '완료') + '</div>';
    var input = '<input type="date" data-due-input="' + id + '" value="' + (p.deadline || '') + '" aria-label="마감일" tabindex="-1">';
    if (!p.deadline) return '<div class="due"><button type="button" data-due="' + id + '">마감일 정하기</button>' + input + '</div>';
    var days = Math.round((new Date(p.deadline + 'T00:00:00') - today) / 864e5);
    var text = (days < 0 ? -days + '일 지남 · ' : days === 0 ? '오늘 · ' : '') + md(p.deadline + 'T00:00:00');
    return '<div class="due' + (days < 0 ? ' is-late' : '') + '"><button type="button" class="is-set" data-due="' + id + '" aria-label="마감일 바꾸기, 지금 ' + text + '">' + text + '</button>' + input + '</div>';
  }
  function sortedPlans() {
    return data.plans.slice().sort(function (a, b) {
      return (pending(b) - pending(a)) || (overdue(b) - overdue(a)) || String(a.deadline || '9').localeCompare(String(b.deadline || '9'));
    });
  }
  function renderTasks() {
    var list = sortedPlans(), el = document.getElementById('task-list');
    el.innerHTML = list.length ? list.slice(0, TASK_ROWS).map(function (p) {
      var on = !pending(p), from = caseTitle(p.analysisCaseId);
      return '<li' + (on ? ' class="is-done"' : '') + '><button class="check" type="button" role="checkbox" aria-checked="' + on + '" aria-label="' + (on ? '완료 취소' : '완료로 표시') + '" data-toggle="' + p.actionPlanId + '"><svg aria-hidden="true"><use href="#tick"/></svg></button>' +
        '<div><div class="name">' + esc(p.suggestion || '보완 과제') + '</div><div class="sub">' + esc(from ? from + ' 지원 분석에서' : '지원 분석에서') + '</div></div>' +
        dueHtml(p) +
        '<div class="act"><a class="btn btn-ghost" href="' + route('/action-plans?analysisCaseId=' + encodeURIComponent(p.analysisCaseId)) + '">상세 보기</a></div></li>';
    }).join('') : empty('아직 과제가 없습니다. 지원 분석을 마치면 보완 과제가 생깁니다.');
    var more = document.querySelector('.tasks + .more');
    if (more) more.remove();
    if (list.length > TASK_ROWS) el.insertAdjacentHTML('afterend', '<p class="more" data-view-panel="list"' + (el.hidden ? ' hidden' : '') + '><a class="link" href="' + route('/action-plans') + '">전체 ' + list.length + '개 과제 보기' + arrow() + '</a></p>');
    renderCalendar();
  }
  function renderCalendar() {
    var y = today.getFullYear(), m = today.getMonth(), first = new Date(y, m, 1).getDay(), days = new Date(y, m + 1, 0).getDate();
    var prefix = y + '-' + String(m + 1).padStart(2, '0') + '-', byDay = {};
    data.plans.filter(function (p) { return pending(p) && p.deadline && p.deadline.indexOf(prefix) === 0; }).forEach(function (p) {
      var d = +p.deadline.slice(8); (byDay[d] = byDay[d] || []).push(p);
    });
    var cells = ['일', '월', '화', '수', '목', '금', '토'].map(function (d) { return '<div class="dow">' + d + '</div>'; });
    for (var i = 0; i < first; i++) cells.push('<div class="day"></div>');
    for (var d = 1; d <= days; d++) {
      cells.push('<div class="day' + (d === today.getDate() ? ' is-today' : '') + '"><b class="num">' + d + '</b>' + (byDay[d] || []).map(function (p) {
        return '<span class="task' + (overdue(p) ? ' is-late' : '') + '">' + esc(p.suggestion || '보완 과제') + '</span>';
      }).join('') + '</div>');
    }
    while (cells.length % 7) cells.push('<div class="day"></div>');
    document.getElementById('cal-title').textContent = y + '년 ' + (m + 1) + '월';
    document.getElementById('cal-grid').innerHTML = cells.join('');
    var nodue = data.plans.filter(function (p) { return pending(p) && !p.deadline; }).length;
    document.getElementById('cal-note').textContent = nodue ? nodue + '개는 마감일이 없어 달력에 보이지 않습니다. 목록에서 마감일을 정해 주십시오.' : '';
  }

  function renderAll() { renderHead(); renderStats(); renderSessions(); renderWeakness(); renderTasks(); }

  // ── 탭 (누르거나 왼쪽·오른쪽 화살표) ──
  var tabs = [].slice.call(document.querySelectorAll('[role="tab"]'));
  function showTab(name) {
    tabs.forEach(function (t) {
      var on = t.dataset.tab === name;
      t.setAttribute('aria-selected', String(on)); t.tabIndex = on ? 0 : -1;
      document.getElementById('p-' + t.dataset.tab).hidden = !on;
    });
  }
  tabs.forEach(function (t, i) {
    t.addEventListener('keydown', function (e) {
      var d = e.key === 'ArrowRight' ? 1 : e.key === 'ArrowLeft' ? -1 : 0;
      if (d) { var n = tabs[(i + d + tabs.length) % tabs.length]; showTab(n.dataset.tab); n.focus(); }
    });
  });

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
    else if (b.dataset.goto) { showTab(b.dataset.goto); document.querySelector('.tabs').scrollIntoView({ behavior: 'smooth' }); }
    else if (b.dataset.view) {
      document.querySelectorAll('[data-view]').forEach(function (x) { x.setAttribute('aria-pressed', String(x === b)); });
      document.querySelectorAll('[data-view-panel]').forEach(function (p) { p.hidden = p.dataset.viewPanel !== b.dataset.view; });
    }
    else if (b.dataset.toggle) {
      var on = b.getAttribute('aria-checked') !== 'true';
      patchPlan(b.dataset.toggle, '/completion', { completed: on }, function (p, u) { p.status = u.status || (on ? 'DONE' : 'PENDING'); p.completedAt = u.completedAt; });
    }
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
    renderAll();
    Promise.all([
      get('/job-category', []), get('/documents?size=100', { content: [] }), get('/analysis-cases/completed', []),
      get('/interview-sessions/active-list', []), get('/interview-sessions/history', []),
      get('/interview-weakness-tags/details', []), get('/interview-weakness-tags/resolved', []), get('/action-plan', [])
    ]).then(function (r) {
      data.jobs = r[0] || []; data.docs = (r[1] && r[1].content) || []; data.analyses = r[2] || [];
      data.active = r[3] || []; data.history = r[4] || []; data.open = r[5] || []; data.solved = r[6] || []; data.plans = r[7] || [];
      renderAll();
      data.history.slice(0, DONE_ROWS).forEach(function (s) {
        get('/interview-sessions/' + s.sessionId + '/score', null).then(function (sc) { data.scores[s.sessionId] = sc; renderSessions(); });
      });
    });
  });
})();
