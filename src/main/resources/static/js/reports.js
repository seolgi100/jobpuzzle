// reports.js — 완료한 면접 목록(모드 필터) + 선택한 면접의 점수 요약
(function () {
  'use strict';
  var U = window.JPUI, path = window.JobPuzzleRoutes.path;
  var state = { sessions: [], loaded: false, error: '', filter: 'all', selectedId: null, scores: {} };

  function visible() {
    return state.sessions.filter(function (s) { return state.filter === 'all' || U.mode(s.mode)[2] === state.filter; });
  }

  function empty(title, desc) {
    return '<div style="padding:40px 16px; text-align:center"><b style="font-size:15px">' + U.esc(title) + '</b>' +
      '<p style="margin:6px 0 0; font-size:13.5px; color:var(--text-3)">' + U.esc(desc) + '</p></div>';
  }

  function renderChips() {
    var counts = { all: state.sessions.length, custom: 0, weak: 0, basic: 0 };
    state.sessions.forEach(function (s) { counts[U.mode(s.mode)[2]] += 1; });
    document.querySelectorAll('[data-mode]').forEach(function (b) {
      b.setAttribute('aria-pressed', String(b.dataset.mode === state.filter));
      b.querySelector('small').textContent = counts[b.dataset.mode];
    });
  }

  function scoreChip(id) {
    var sc = state.scores[id];
    if (!sc || sc.overallScore == null) return '<span class="score score--none">' + (sc ? '-' : '…') + '</span>';
    return '<span class="score score--' + U.tone(sc.overallScore) + '">' + sc.overallScore + '점</span>';
  }

  function renderList() {
    var el = document.getElementById('report-list');
    if (!state.loaded) { el.innerHTML = empty('면접 기록을 불러오고 있어요.', '잠시만 기다려 주세요.'); return; }
    if (state.error) { el.innerHTML = empty('면접 기록을 불러오지 못했어요.', state.error); return; }
    var list = visible();
    if (!list.length) {
      el.innerHTML = empty(state.sessions.length ? '이 모드로 완료한 면접이 없어요.' : '아직 완료한 면접이 없어요.', '면접을 마치면 이곳에 기록이 쌓여요.');
      return;
    }
    el.innerHTML = list.map(function (s, k) {
      return '<button type="button" class="rp-item" data-sel="' + s.sessionId + '" aria-current="' + (s.sessionId === state.selectedId) + '">' +
        '<span class="rp-item__no">' + String(k + 1).padStart(2, '0') + '</span>' +
        '<div style="min-width:0"><p class="rp-item__title">' + U.modeBadge(s.mode) + '</p>' +
        '<p class="rp-item__meta">' + U.esc(U.dateTime(s.completedAt)) + ' · 질문 ' + (s.questionCount || 0) + '개</p></div>' +
        scoreChip(s.sessionId) + '</button>';
    }).join('');
  }

  function bar(x) {
    return '<div class="mini-bar"><span style="text-align:left; font-weight:600; color:var(--text-2)">' + U.esc(x.d) + '</span>' +
      '<i><b style="width:' + x.v + '%"></b></i><span>' + x.v + '</span></div>';
  }

  function renderDetail() {
    var el = document.getElementById('report-detail');
    var s = visible().filter(function (x) { return x.sessionId === state.selectedId; })[0];
    if (!s) { el.innerHTML = empty('확인할 리포트가 없어요.', '면접을 마치면 점수와 보완 방향을 볼 수 있어요.'); return; }
    var sc = state.scores[s.sessionId];
    var link = path('/interview-results?sessionId=' + encodeURIComponent(s.sessionId));
    var html = '<div class="rp-detail__top"><div><p class="rp-detail__eyebrow">' + U.mode(s.mode)[0] + '</p>' +
      '<h2 class="rp-detail__title">' + U.mode(s.mode)[0] + ' 리포트</h2>' +
      '<p class="rp-detail__meta">' + U.esc(U.dateTime(s.completedAt)) + ' · 질문 ' + (s.questionCount || 0) + '개</p></div>' +
      '<a href="' + link + '" class="dbtn dbtn--navy">리포트 자세히 보기</a></div>';
    if (!sc) {
      html += '<div class="rp-preview"><p style="margin:0; color:var(--text-3)">점수를 불러오고 있어요.</p></div>';
    } else {
      var dims = Object.keys(sc.categoryScores || {}).map(function (k) { return { d: U.DIM_LABELS[k] || k, v: sc.categoryScores[k] }; })
        .filter(function (x) { return x.v != null && x.v > 0; }).sort(function (a, b) { return b.v - a.v; });
      var weak = [];
      (sc.questionScores || []).forEach(function (q) {
        (q.weaknessTags || []).forEach(function (t) { if (weak.indexOf(t) < 0) weak.push(t); });
      });
      html += '<div class="rp-preview">' + U.ring(sc.overallScore) + '<div>' +
        (dims.length
          ? '<h4>잘한 관점</h4><div class="mini-bars">' + dims.slice(0, 3).map(bar).join('') + '</div>' +
            (dims.length > 3 ? '<h4 style="margin-top:16px">보완할 관점</h4><div class="mini-bars">' + dims.slice(-2).reverse().map(bar).join('') + '</div>' : '')
          : '<p style="margin:0; color:var(--text-3)">평가된 관점이 아직 없어요.</p>') +
        '</div></div>' +
        '<div class="rp-block"><h4>반복된 약점</h4>' + (weak.length
          ? '<div class="tags">' + weak.map(function (w) { return '<span class="wtag">' + U.esc(U.weakLabel(w)) + '</span>'; }).join('') + '</div>'
          : '<p style="margin:0; font-size:13.5px; color:var(--text-3)">이 면접에서 확인된 약점이 없어요.</p>') + '</div>';
    }
    html += '<p class="rp-note">리포트에서 관점별 근거, 다음 연습 질문, 서류 보완 제안까지 볼 수 있어요. 처음 여는 리포트는 정리하는 데 몇 초 걸릴 수 있어요.</p>';
    el.innerHTML = html;
  }

  function render() {
    var list = visible();
    if (list.length && !list.some(function (s) { return s.sessionId === state.selectedId; })) state.selectedId = list[0].sessionId;
    renderChips(); renderList(); renderDetail();
  }

  // 목록 점수는 세션별 점수 API로 채운다(실패하면 '-').
  function loadScores() {
    state.sessions.forEach(function (s) {
      U.api(path('/interview-sessions/' + s.sessionId + '/score'))
        .then(function (sc) { state.scores[s.sessionId] = sc || {}; })
        .catch(function () { state.scores[s.sessionId] = {}; })
        .then(render);
    });
  }

  document.addEventListener('click', function (e) {
    var b = e.target.closest('button');
    if (!b) return;
    if (b.dataset.mode) { state.filter = b.dataset.mode; render(); }
    if (b.dataset.sel) { state.selectedId = Number(b.dataset.sel); render(); }
  });

  document.addEventListener('DOMContentLoaded', function () {
    render();
    U.api(path('/interview-sessions/history')).then(function (sessions) {
      state.sessions = Array.isArray(sessions) ? sessions : [];
      state.loaded = true;
      render();
      loadScores();
    }).catch(function (error) {
      state.loaded = true; state.error = error.message; render();
    });
  });
})();
