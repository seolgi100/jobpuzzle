// interview-report.js — 평가 명세 v2 면접 보고서 (6장). 저장된 평가로 서버가 만든 /report 응답을 그대로 그린다.
// 점수 계산은 모두 서버(ScoreCalculator). 이 화면은 가장 강한·약한 항목 고르기 정도만 한다.
(function () {
  'use strict';

  var U = window.JPUI, route = window.JobPuzzleRoutes.path, esc = U.esc;
  var sessionId = new URLSearchParams(window.location.search).get('sessionId');
  var MSG_WHO = { ORIGINAL_ANSWER: '첫 답변', FOLLOW_UP_QUESTION: 'AI 추가 질문', FOLLOW_UP_ANSWER: '추가 답변' };
  var report = null, tickets = null;

  function api(path, options) { return U.api(route(path), options); }
  function piece(empty) { return '<svg class="piece' + (empty ? ' is-empty' : '') + '" aria-hidden="true"><use href="#pc"/></svg>'; }
  function arrow() { return '<svg class="arrow" aria-hidden="true"><use href="#arrow"/></svg>'; }

  function dateText(value) {
    if (!value) return '';
    var d = new Date(value);
    return d.getFullYear() + '년 ' + (d.getMonth() + 1) + '월 ' + d.getDate() + '일';
  }

  // ── 1. 종합 점수 ──
  function overview(r) {
    var delta = '';
    if (r.previousTotalScore != null && r.totalScore != null) {
      var diff = r.totalScore - r.previousTotalScore;
      var prev = (r.round - 1) + '회차';
      delta = '<div class="delta' + (diff < 0 ? ' is-down' : '') + '">' +
        (diff === 0 ? '' : '<svg viewBox="0 0 12 12" aria-hidden="true"><path d="' + (diff > 0 ? 'M6 10.5V2M2 6l4-4 4 4' : 'M6 1.5V10M2 6l4 4 4-4') +
          '" fill="none" stroke="currentColor" stroke-width="1.5"/></svg>') +
        (diff === 0 ? prev + '와 같은 점수' : prev + '보다 ' + Math.abs(diff) + '점 ' + (diff > 0 ? '상승' : '하락')) + '</div>';
    }
    var scored = r.criteria.filter(function (c) { return c.score != null; });
    var max = Math.max.apply(null, scored.map(function (c) { return c.score; }));
    var min = Math.min.apply(null, scored.map(function (c) { return c.score; }));
    // 같은 점수끼리는 모두 이름을 댄다. 막대 강조(델프트 블루)는 최고점이 하나일 때만
    var names = function (v) {
      return scored.filter(function (c) { return c.score === v; }).map(function (c) { return '<b>' + esc(c.label) + '</b>'; }).join(', ');
    };
    var tops = scored.filter(function (c) { return c.score === max; });
    var high = tops.length === 1 && max !== min ? tops[0].code : null;
    var note = !scored.length ? '' : max === min
      ? '여섯 항목의 점수가 <b>' + max + '점</b>으로 같습니다.'
      : '가장 높은 항목은 ' + names(max) + '(' + max + '점), 가장 낮은 항목은 ' + names(min) + '(' + min + '점)입니다.';
    var evaluated = r.questions.filter(function (q) { return q.status === 'EVALUATED'; }).length;
    return '<section id="overview" aria-labelledby="overview-title"><h2 class="sr-only" id="overview-title">종합 점수</h2>' +
      '<div class="overview"><div class="total"><div class="label">면접 총점</div>' +
      '<div class="big num">' + (r.totalScore == null ? '-' : r.totalScore) + '<small>/ 100</small></div>' + delta +
      '<p class="basis">' + (r.totalScore == null ? '평가된 답변이 아직 없습니다.'
        : '질문 ' + r.tally.total + '개 중 평가된 ' + evaluated + '개를 종합한 결과입니다.') + '</p></div>' +
      '<figure class="board-strip"><div class="pieces" role="img" aria-label="질문 ' + r.tally.total + '개 중 ' + evaluated + '개 평가 완료">' +
      r.questions.map(function (q) { return piece(q.status !== 'EVALUATED'); }).join('') + '</div>' +
      '<figcaption>질문 하나가 조각 하나입니다. 비어 있는 조각은 답하지 않았거나 평가하지 못한 질문입니다.</figcaption></figure></div>' +
      (scored.length ? '<div class="criteria" aria-label="항목별 점수">' + r.criteria.map(function (c) {
        var v = c.score == null ? 0 : c.score;
        return '<div class="crit' + (c.code === high ? ' is-high' : '') + '"><span>' + esc(c.label) + '</span>' +
          '<span class="bar' + (c.code === high ? ' is-point' : '') + '"><b style="--v:' + v + '%"></b></span>' +
          '<span class="v num">' + (c.score == null ? '-' : c.score) + '</span></div>';
      }).join('') + '</div><p class="crit-note">' + note + '</p>' : '') + '</section>';
  }

  // ── 2. 질문별 결과 ──
  function questionBody(q, round) {
    if (q.status === 'SKIPPED') return '<p class="skip-note">답하지 않은 질문은 점수에서 빠집니다.</p>';
    var talk = '<ol class="talk">' + q.conversation.map(function (m) {
      return '<li' + (m.type === 'FOLLOW_UP_QUESTION' ? ' class="is-ai"' : '') + '><div class="who">' + MSG_WHO[m.type] + '</div><p>' + esc(m.text) + '</p></li>';
    }).join('') + '</ol>';
    var rows = [];
    if (q.competencyTags.length) rows.push(['확인하는 역량', '<span class="tags">' + q.competencyTags.map(esc).map(function (t) { return '<span>' + t + '</span>'; }).join('') + '</span>']);
    if (q.intent) rows.push(['출제 의도', esc(q.intent)]);
    if (q.items.length) rows.push(['항목별 점수', '<ul class="items">' + q.items.map(function (i) {
      return '<li><span>' + esc(i.label) + '</span><span class="v num">' + (i.score == null ? '-' : i.score + ' / 5') + '</span>' +
        '<span class="why">' + esc(i.reason || '') + '</span></li>';
    }).join('') + '</ul>']);
    if (q.strengths) rows.push(['잘한 점', esc(q.strengths)]);
    if (q.improvements) rows.push(['보완할 점', esc(q.improvements)]);
    if (q.recommendedAnswer) rows.push(['추천 답변 방향', esc(q.recommendedAnswer)]);
    if (q.comparison) {
      rows.push([(round - 1) + '회차보다 나아진 점', esc(q.comparison.improved)]);
      rows.push(['더 개선할 점', esc(q.comparison.toImprove)]);
    }
    if (q.status === 'FAILED') rows.push(['평가', '이 질문의 답변은 평가하지 못해 점수에서 빠졌습니다.']);
    return talk + '<dl class="verdict">' + rows.map(function (r) { return '<dt>' + r[0] + '</dt><dd>' + r[1] + '</dd>'; }).join('') + '</dl>';
  }

  function questions(r) {
    var t = r.tally;
    return '<section id="questions" aria-labelledby="questions-title"><h2 class="h2" id="questions-title">질문별 결과</h2>' +
      '<dl class="tally"><div><dt>답변한 질문</dt><dd class="num">' + t.answered + '<small>개</small></dd></div>' +
      '<div><dt>평가 성공</dt><dd class="num">' + t.evaluated + '<small>개</small></dd></div>' +
      '<div><dt>답하지 않음</dt><dd class="num">' + t.skipped + '<small>개</small></dd></div>' +
      '<div><dt>완료율</dt><dd class="num">' + t.completionRate + '<small>%</small></dd></div></dl>' +
      '<div class="qs">' + r.questions.map(function (q, i) {
        var score = q.status === 'EVALUATED' ? '<span class="score num">' + q.score + '</span>'
          : '<span class="score is-none">' + (q.status === 'SKIPPED' ? '답하지 않음' : '평가 실패') + '</span>';
        return '<details class="q"' + (i === 0 ? ' open' : '') + '><summary>' + piece(q.status !== 'EVALUATED') +
          '<span class="no num">질문 ' + (i + 1) + '</span><span class="text">' + esc(q.question) + '</span>' + score +
          '<svg class="chev" aria-hidden="true"><use href="#chev"/></svg></summary><div class="body">' + questionBody(q, r.round) + '</div></details>';
      }).join('') + '</div></section>';
  }

  // ── 3. 다음 연습 (다회차) ──
  function fare(n) { return n == null ? '' : ' <small>· ' + (n === 0 ? '티켓 없이' : '티켓 ' + n + '장') + '</small>'; }

  function actions(r, compact) {
    if (!r.completed) {
      return '<a class="btn btn-primary" href="' + route('/interview?resumeSessionId=' + encodeURIComponent(r.sessionId)) + '">남은 질문 이어서 답하기' + arrow() + '</a>' +
        (r.totalScore != null ? '<button type="button" class="btn btn-ghost" data-finish>면접 끝내기</button>' : '');
    }
    var c = tickets ? tickets.costs : null;
    var regen = r.mode === 'PERSONALITY' ? 0 : c && c.questionRegenerate;
    return '<button type="button" class="btn btn-primary" data-round="retry">같은 질문으로 다시 하기' + fare(c && c.sessionRetry) + '</button>' +
      '<button type="button" class="btn btn-ghost" data-round="regenerate">새 질문으로 다시 하기' + fare(regen) + '</button>' +
      (compact ? '' : '<a class="btn btn-ghost" href="' + route('/reports') + '">리포트 목록</a>');
  }

  function next(r) {
    if (!r.completed) return '';
    return '<section id="next" aria-labelledby="next-title"><h2 class="h2" id="next-title">다음 연습</h2>' +
      '<p class="next-lead">같은 질문으로 다시 하면 질문마다 이번 답변과 비교해 나아진 점을 알려 드립니다. 새 질문으로 하면 이번에 묻지 않은 내용을 묻습니다.</p>' +
      '<div class="next">' + actions(r, false) + '</div></section>';
  }

  function render(r) {
    document.title = r.title + ' 결과 — JobPuzzle';
    document.getElementById('r2-title').textContent = r.title;
    document.getElementById('r2-meta').textContent = [r.modeLabel, r.round + '회차',
      r.completed ? dateText(r.completedAt) + ' 완료' : '진행 중'].filter(Boolean).join(' · ');
    var toc = [['overview', '종합 점수'], ['questions', '질문별 결과']].concat(r.completed ? [['next', '다음 연습']] : []);
    document.getElementById('r2-body').innerHTML = '<div class="layout"><aside class="toc" aria-label="보고서 목차"><ol>' +
      toc.map(function (t) { return '<li><a href="#' + t[0] + '">' + t[1] + '</a></li>'; }).join('') + '</ol>' +
      '<div class="actions">' + actions(r, true) + '</div></aside>' +
      '<article class="paper doc" aria-label="면접 결과 보고서">' +
      (r.completed ? '' : '<p class="interim"><b>중간 결과입니다.</b> 면접을 끝내면 이 내용으로 보고서가 확정됩니다.</p>') +
      overview(r) + questions(r) + next(r) +
      '<p class="foot-note">AI가 평가 항목의 기준 문장에 따라 답변마다 1~5점을 매기고, 평균과 100점 환산은 서버가 계산합니다.</p>' +
      '</article></div>';
    document.getElementById('report').setAttribute('aria-busy', 'false');
    watchToc();
  }

  // 목차: 지금 읽는 장 표시
  function watchToc() {
    var links = Array.prototype.slice.call(document.querySelectorAll('.toc ol a'));
    if (!links.length || !('IntersectionObserver' in window)) return;
    var mark = function (id) { links.forEach(function (a) { a.setAttribute('aria-current', String(a.getAttribute('href') === '#' + id)); }); };
    var io = new IntersectionObserver(function (entries) {
      var seen = entries.filter(function (e) { return e.isIntersecting; })
        .sort(function (a, b) { return a.boundingClientRect.top - b.boundingClientRect.top; })[0];
      if (seen) mark(seen.target.id);
    }, { rootMargin: '-30% 0px -60% 0px' });
    links.forEach(function (a) { var s = document.querySelector(a.getAttribute('href')); if (s) io.observe(s); });
    mark('overview');
  }

  // ── 확인 창 ──
  function confirmBox(title, text, okLabel, onOk) {
    var dialog = document.getElementById('r2-confirm');
    document.getElementById('r2-confirm-title').textContent = title;
    document.getElementById('r2-confirm-text').textContent = text;
    document.getElementById('r2-confirm-ok').textContent = okLabel;
    dialog.onclose = function () { if (dialog.returnValue === 'ok') onOk(); };
    dialog.returnValue = '';
    dialog.showModal();
  }

  function startRound(kind, button) {
    var retry = kind === 'retry';
    confirmBox(retry ? '같은 질문으로 다시 하시겠습니까?' : '새 질문으로 다시 하시겠습니까?',
      retry ? '같은 질문으로 다음 회차를 시작하고, 질문마다 이번 답변과 비교합니다.'
        : '이번에 묻지 않은 질문을 새로 만들어 다음 회차를 시작합니다. 질문을 만들지 못하면 티켓은 돌려드립니다.',
      retry ? '다시 하기' : '새 질문 만들기',
      function () {
        button.disabled = true;
        api('/interview-sessions/' + sessionId + '/' + kind, { method: 'POST' }).then(function (nextRound) {
          window.location.href = route('/interview?resumeSessionId=' + encodeURIComponent(nextRound.sessionId));
        }).catch(function (err) { notify(err.message); button.disabled = false; });
      });
  }

  function finish(button) {
    confirmBox('면접을 끝내시겠습니까?', '답하지 않은 질문은 점수에서 빠지고, 지금까지의 답변으로 보고서가 확정됩니다.', '면접 끝내기', function () {
      button.disabled = true;
      api('/interview-sessions/' + sessionId + '/complete', { method: 'POST' }).then(load)
        .catch(function (err) { notify(err.message); button.disabled = false; });
    });
  }

  function notify(message) {
    if (window.showToast) window.showToast('error', message);
    else window.alert(message);
  }

  function load() {
    return Promise.all([
      api('/interview-sessions/' + sessionId + '/report'),
      api('/tickets/me').catch(function () { return null; })
    ]).then(function (v) {
      report = v[0]; tickets = v[1];
      render(report);
    }).catch(function (err) {
      document.getElementById('r2-meta').textContent = '';
      document.getElementById('r2-body').innerHTML = '<p class="error" role="alert">' + esc(err.message) + '</p>';
      document.getElementById('report').setAttribute('aria-busy', 'false');
    });
  }

  document.addEventListener('click', function (e) {
    var t = e.target.closest('button');
    if (!t || t.disabled || !document.getElementById('report').contains(t)) return;
    if (t.dataset.round) startRound(t.dataset.round, t);
    else if (t.hasAttribute('data-finish')) finish(t);
  });

  document.addEventListener('DOMContentLoaded', function () {
    if (!sessionId) { window.location.replace(route('/reports')); return; }
    load();
  });
})();
