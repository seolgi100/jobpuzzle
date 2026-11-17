// action-plans.js — 분석별 보완 과제 목록과 달력. 과제 내용은 분석 결과 그대로이고 완료·마감일만 바꾼다.
(function () {
  'use strict';
  var U = window.JPUI, route = window.JobPuzzleRoutes.path;
  var ICAL = '<svg viewBox="0 0 24 24"><rect x="3" y="5" width="18" height="16" rx="2"/><path d="M3 10h18M8 3v4M16 3v4"/></svg>';
  var today = new Date(); today.setHours(0, 0, 0, 0);
  var TODAY = key(today);
  var state = {
    plans: [], loaded: false, error: '', filter: 'ALL', view: 'list', savingIds: {}, errors: {},
    month: new Date(today.getFullYear(), today.getMonth(), 1), selDay: TODAY,
    focused: new URLSearchParams(window.location.search).get('analysisCaseId'), highlight: null
  };

  function key(d) { return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0'); }
  function fmt(d) { var p = d.split('-'); return +p[1] + '월 ' + +p[2] + '일'; }
  function done(p) { return p.status === 'DONE'; }
  function over(p) { return !done(p) && p.deadline && p.deadline < TODAY; }
  function show(p) { return state.filter === 'ALL' || (state.filter === 'DONE') === done(p); }
  function careerLabel(v) { return { NEW: '신입', EXPERIENCED: '경력', ANY: '경력 무관' }[v] || ''; }
  function api(p, method, body) { return U.api(route(p), { method: method, json: body }); }

  function groups() {
    var map = new Map();
    state.plans.forEach(function (p) {
      var k = String(p.analysisCaseId);
      if (!map.has(k)) map.set(k, { id: k, plans: [] });
      map.get(k).plans.push(p);
    });
    return Array.from(map.values()).sort(function (a, b) {
      if (a.id === state.focused) return -1;
      if (b.id === state.focused) return 1;
      return new Date(b.plans[0].createdAt || 0) - new Date(a.plans[0].createdAt || 0);
    });
  }

  function renderStats() {
    var all = state.plans, d = all.filter(done).length, rate = all.length ? Math.round(d / all.length * 100) : 0;
    document.querySelector('[data-stat="total"]').innerHTML = all.length + '<small>개</small>';
    document.querySelector('[data-stat="pending"]').innerHTML = (all.length - d) + '<small>개</small>';
    document.querySelector('[data-stat="over"]').innerHTML = all.filter(over).length + '<small>개</small>';
    document.querySelector('[data-stat="rate"]').innerHTML = rate + '<small>%</small>';
    document.querySelector('[data-stat="bar"]').style.width = rate + '%';
  }

  function dueControl(p) {
    if (done(p)) return '<span class="pill pill--done">완료</span>';
    var label;
    if (!p.deadline) label = '마감일 정하기';
    else {
      var days = Math.round((new Date(p.deadline + 'T00:00:00') - today) / 864e5);
      label = fmt(p.deadline) + ' · ' + (days < 0 ? -days + '일 지남' : days === 0 ? '오늘' : 'D-' + days);
    }
    var cls = 'ap-due' + (!p.deadline ? ' ap-due--empty' : over(p) ? ' ap-due--over' : '');
    return '<button type="button" class="' + cls + '" data-due="' + p.actionPlanId + '">' + ICAL + label + '</button>' +
      '<input type="date" class="ap-due__input" data-due-input="' + p.actionPlanId + '" value="' + (p.deadline || '') + '" aria-label="마감일" tabindex="-1">';
  }

  function itemHtml(p) {
    var id = p.actionPlanId, saving = !!state.savingIds[id];
    var tags = [p.requirement].filter(Boolean).map(function (t) { return '<span class="mbadge mbadge--tag">#' + U.esc(t) + '</span>'; }).join('');
    return '<div class="ap-item' + (done(p) ? ' is-done' : '') + (String(state.highlight) === String(id) ? ' is-hl' : '') + '" data-plan="' + id + '">' +
      '<button type="button" class="ap-check" data-check="' + id + '" aria-pressed="' + done(p) + '" aria-label="' + (done(p) ? '완료 취소' : '완료') + '"' + (saving ? ' disabled' : '') + '><svg viewBox="0 0 24 24"><path d="M20 6L9 17l-5-5"/></svg></button>' +
      '<div><p class="ap-item__title">' + U.esc(p.suggestion || '보완 과제') + '</p>' +
      '<p class="ap-item__desc">' + U.esc(p.missingPoint || '보완이 필요한 내용을 확인해 주세요.') + '</p>' +
      (tags ? '<div class="ap-item__tags">' + tags + '</div>' : '') +
      (state.errors[id] ? '<p class="ap-item__desc" style="color:var(--danger)">' + U.esc(state.errors[id]) + '</p>' : '') + '</div>' +
      '<div class="ap-item__side">' + dueControl(p) + '</div></div>';
  }

  function emptyBox(title, desc, withLink) {
    return '<div class="dcard ap-empty" style="margin-top:16px"><b>' + U.esc(title) + '</b>' + (desc ? '<p>' + U.esc(desc) + '</p>' : '') +
      (withLink ? '<a href="' + route('/interview') + '" class="dbtn dbtn--navy">지원 분석 시작하기</a>' : '') + '</div>';
  }

  function renderList() {
    var el = document.getElementById('ap-list');
    if (!state.loaded) { el.innerHTML = emptyBox('과제를 불러오고 있어요.'); return; }
    if (state.error) { el.innerHTML = emptyBox('과제를 불러오지 못했어요.', state.error); return; }
    if (!state.plans.length) { el.innerHTML = emptyBox('아직 과제가 없어요', '지원 분석을 마치면 부족한 근거를 채울 과제가 여기에 생겨요.', true); return; }
    var html = groups().map(function (g) {
      var items = g.plans.filter(show);
      if (!items.length) return '';
      var s = g.plans[0], d = g.plans.filter(done).length;
      var title = [s.mainCategory, s.subCategory].filter(Boolean).join(' · ') || '직무 분석';
      var meta = ['분석 #' + g.id, s.createdAt ? new Date(s.createdAt).toLocaleDateString('ko-KR') + ' 분석' : '', careerLabel(s.careerLevel)].filter(Boolean).join(' · ');
      return '<section class="dcard ap-group"><div class="ap-group__head"><div><h2 class="ap-group__title">' + U.esc(title) + '</h2><p class="ap-group__meta">' + U.esc(meta) + '</p></div>' +
        '<div class="ap-group__right"><span>' + d + ' / ' + g.plans.length + ' 완료</span><span class="ap-mini"><i style="width:' + (d / g.plans.length * 100) + '%"></i></span>' +
        '<a class="ap-group__link" href="' + route('/analysis-results/' + encodeURIComponent(g.id)) + '">분석 결과 보기</a></div></div>' +
        items.map(itemHtml).join('') + '</section>';
    }).join('');
    el.innerHTML = html || emptyBox('해당하는 과제가 없어요');
  }

  function renderCal() {
    var m = state.month, start = new Date(m.getFullYear(), m.getMonth(), 1 - m.getDay());
    var plans = state.plans.filter(show);
    var h = '<div class="cal__head"><b>' + m.getFullYear() + '년 ' + (m.getMonth() + 1) + '월</b><div class="cal__nav">' +
      '<button type="button" data-month="-1" aria-label="이전 달">‹</button><button type="button" class="cal__today" data-month="0">오늘</button>' +
      '<button type="button" data-month="1" aria-label="다음 달">›</button></div></div><div class="cal__grid">';
    h += ['일', '월', '화', '수', '목', '금', '토'].map(function (d) { return '<span class="cal__dow">' + d + '</span>'; }).join('');
    var weeks = Math.ceil((m.getDay() + new Date(m.getFullYear(), m.getMonth() + 1, 0).getDate()) / 7);
    for (var i = 0; i < weeks * 7; i++) {
      var d = new Date(start); d.setDate(start.getDate() + i);
      var k = key(d);
      var ev = plans.filter(function (p) { return p.deadline === k; });
      h += '<button type="button" class="cal__day' + (d.getMonth() !== m.getMonth() ? ' is-out' : '') + (k === TODAY ? ' is-today' : '') + '" data-day="' + k + '" aria-pressed="' + (k === state.selDay) + '"><span class="cal__n">' + d.getDate() + '</span>' +
        ev.map(function (p) { return '<span class="cal__ev' + (done(p) ? ' cal__ev--done' : over(p) ? ' cal__ev--over' : '') + '">' + U.esc(p.suggestion || '보완 과제') + '</span>'; }).join('') + '</button>';
    }
    document.getElementById('cal').innerHTML = h + '</div>';
    var day = plans.filter(function (p) { return p.deadline === state.selDay; });
    var nodue = plans.filter(function (p) { return !p.deadline && !done(p); }).length;
    document.getElementById('cal-side').innerHTML = '<p class="cal-side__date">' + fmt(state.selDay) + (state.selDay === TODAY ? ' · 오늘' : '') + '</p>' +
      (day.length ? day.map(function (p) {
        return '<button type="button" class="cal-side__item" data-goto="' + p.actionPlanId + '" style="width:100%; border:0; background:none; text-align:left; font:inherit; cursor:pointer"><p>' + U.esc(p.suggestion || '보완 과제') + '</p><small>' +
          U.esc([p.mainCategory, p.subCategory].filter(Boolean).join(' · ')) + ' · ' + (done(p) ? '완료' : over(p) ? '마감 지남' : '진행 중') + '</small></button>';
      }).join('') : '<p style="margin:0; font-size:14px; color:var(--text-3)">이 날 마감인 과제가 없어요.</p>') +
      (nodue ? '<p class="cal-side__nodue">마감일이 없는 과제 ' + nodue + '개는 달력에 안 보여요. 목록에서 마감일을 정해주세요.</p>' : '');
  }

  function render() {
    renderStats();
    var cal = state.view === 'cal' && state.plans.length > 0;
    document.getElementById('ap-list').hidden = cal;
    document.getElementById('ap-cal').hidden = !cal;
    document.querySelectorAll('[data-filter]').forEach(function (b) { b.setAttribute('aria-pressed', String(b.dataset.filter === state.filter)); });
    document.querySelectorAll('[data-view]').forEach(function (b) { b.setAttribute('aria-pressed', String(b.dataset.view === state.view)); });
    renderList();
    if (cal) renderCal();
  }

  function find(id) { return state.plans.filter(function (p) { return String(p.actionPlanId) === String(id); })[0]; }
  function patch(p, url, body, apply) {
    var id = p.actionPlanId;
    if (state.savingIds[id]) return;
    state.savingIds[id] = true; delete state.errors[id]; render();
    api(url, 'PATCH', body).then(function (updated) { apply(updated || {}); })
      .catch(function (error) { state.errors[id] = error.message; })
      .then(function () { delete state.savingIds[id]; render(); });
  }

  document.addEventListener('click', function (e) {
    var b = e.target.closest('button');
    if (!b) return;
    if (b.dataset.filter) { state.filter = b.dataset.filter; render(); }
    else if (b.dataset.view) { state.view = b.dataset.view; render(); }
    else if (b.dataset.check) {
      var p = find(b.dataset.check);
      patch(p, '/action-plan/' + encodeURIComponent(p.actionPlanId) + '/completion', { completed: !done(p) },
        function (u) { p.status = u.status || (done(p) ? 'PENDING' : 'DONE'); p.completedAt = u.completedAt; });
    } else if (b.dataset.due) {
      var input = document.querySelector('[data-due-input="' + b.dataset.due + '"]');
      if (input.showPicker) { try { input.showPicker(); return; } catch (err) { /* 아래로 */ } }
      input.focus(); input.click();
    } else if (b.dataset.month) {
      var n = Number(b.dataset.month);
      state.month = n === 0 ? new Date(today.getFullYear(), today.getMonth(), 1) : new Date(state.month.getFullYear(), state.month.getMonth() + n, 1);
      if (n === 0) state.selDay = TODAY;
      renderCal();
    } else if (b.dataset.day) { state.selDay = b.dataset.day; renderCal(); }
    else if (b.dataset.goto) {
      state.view = 'list'; state.filter = 'ALL'; state.highlight = b.dataset.goto; render();
      var el = document.querySelector('[data-plan="' + b.dataset.goto + '"]');
      if (el) el.scrollIntoView({ behavior: 'smooth', block: 'center' });
      setTimeout(function () { state.highlight = null; render(); }, 1600);
    }
  });

  document.addEventListener('change', function (e) {
    var id = e.target.dataset && e.target.dataset.dueInput;
    if (!id) return;
    var p = find(id), value = e.target.value || null;
    patch(p, '/action-plan/' + encodeURIComponent(id) + '/deadline', { deadline: value }, function (u) { p.deadline = u.deadline !== undefined ? u.deadline : value; });
  });

  document.addEventListener('DOMContentLoaded', function () {
    if (new URLSearchParams(window.location.search).get('view') === 'cal') state.view = 'cal';
    render();
    api('/action-plan').then(function (plans) {
      state.plans = Array.isArray(plans) ? plans : [];
      state.loaded = true; render();
    }).catch(function (error) { state.loaded = true; state.error = error.message; render(); });
  });
})();
