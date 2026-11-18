// summary.js — 평가 명세 v2 4-3 사용자 종합 평가. 서버(/user/me/summary)가 계산한 값을 그대로 그린다 (AI 없음).
(function () {
  'use strict';

  var U = window.JPUI, route = window.JobPuzzleRoutes.path, esc = U.esc;

  function piece(empty) { return '<svg class="piece' + (empty ? ' is-empty' : '') + '" aria-hidden="true"><use href="#pc"/></svg>'; }

  function waiting(s) {
    var left = s.minReports - s.reportCount, pieces = '';
    for (var i = 0; i < s.minReports; i++) pieces += piece(i >= s.reportCount);
    return '<section aria-labelledby="wait-title"><h2 class="sr-only" id="wait-title">종합 평가 준비 상태</h2>' +
      '<div class="overview"><div class="total"><div class="label">끝낸 면접 보고서</div>' +
      '<div class="big num">' + s.reportCount + '<small>/ ' + s.minReports + '</small></div>' +
      '<p class="basis">보고서가 ' + left + '개 더 있으면 종합 평가를 계산합니다.</p></div>' +
      '<figure class="board-strip"><div class="pieces" role="img" aria-label="보고서 ' + s.minReports + '개 중 ' + s.reportCount + '개">' + pieces + '</div>' +
      '<figcaption>보고서 하나가 조각 하나입니다. 기초 지식·자료 기반·인성 면접을 끝내면 채워집니다.</figcaption></figure></div>' +
      '<div class="next"><a class="btn btn-primary" href="' + route('/interview') + '">면접 준비로 가기<svg class="arrow" aria-hidden="true"><use href="#arrow"/></svg></a></div></section>';
  }

  function example(label, e) {
    if (!e) return '';
    return '<dt>' + label + '</dt><dd><p class="tg-q">' + esc(e.question) + ' <span class="num">' + e.score + '점</span></p>' +
      (e.text ? '<p>' + esc(e.text) + '</p>' : '') +
      '<a class="tg-link" href="' + route('/interview-report?sessionId=' + encodeURIComponent(e.sessionId)) + '">이 면접 보고서 보기</a></dd>';
  }

  function tagList(tags, empty, point) {
    if (!tags.length) return '<p class="next-lead">' + empty + '</p>';
    return '<div class="qs">' + tags.map(function (t, i) {
      return '<details class="tg"' + (i === 0 ? ' open' : '') + '><summary>' + piece(!point) +
        '<span class="tg-name">' + esc(t.name) + '<small>질문 ' + t.questionCount + '개</small></span>' +
        '<span class="bar' + (point && i === 0 ? ' is-point' : '') + '"><b style="--v:' + t.average + '%"></b></span>' +
        '<span class="score num">' + t.average + '</span><svg class="chev" aria-hidden="true"><use href="#chev"/></svg></summary>' +
        '<dl class="verdict tg-body">' + example('가장 잘한 답변', t.best) + example('가장 아쉬운 답변', t.worst) + '</dl></details>';
    }).join('') + '</div>';
  }

  function ready(s) {
    return '<section id="overview" aria-labelledby="ov-title"><h2 class="sr-only" id="ov-title">요약</h2>' +
      '<dl class="tally tally--3"><div><dt>끝낸 면접 보고서</dt><dd class="num">' + s.reportCount + '<small>개</small></dd></div>' +
      '<div><dt>가장 많이 준비한 직무</dt><dd class="tally-text">' + esc(s.preferredJob || '-') + '</dd></div>' +
      '<div><dt>계산 기준</dt><dd class="tally-text">태그당 질문 ' + s.minQuestionsPerTag + '개 이상</dd></div></dl></section>' +
      '<section id="strengths" aria-labelledby="st-title"><h2 class="h2" id="st-title">강점</h2>' +
      '<p class="next-lead">역량 태그가 붙은 질문의 점수 평균이 가장 높은 태그입니다. 펼치면 그 태그에서 가장 잘한 답변과 가장 아쉬운 답변을 볼 수 있습니다.</p>' +
      tagList(s.strengths, '아직 질문이 ' + s.minQuestionsPerTag + '개 이상 모인 태그가 없습니다.', true) + '</section>' +
      '<section id="weaknesses" aria-labelledby="wk-title"><h2 class="h2" id="wk-title">아쉬운 점</h2>' +
      '<p class="next-lead">점수 평균이 가장 낮은 태그입니다. 다음 면접에서 먼저 채워 볼 조각입니다.</p>' +
      tagList(s.weaknesses, '강점과 겹치지 않는 아쉬운 태그가 아직 없습니다.', false) + '</section>';
  }

  function render(s) {
    document.getElementById('s2-meta').textContent = s.available
      ? '끝낸 면접 보고서 ' + s.reportCount + '개의 질문 점수로 계산했습니다.'
      : '면접 보고서가 ' + s.minReports + '개 이상 모이면 역량별 강점과 아쉬운 점을 계산합니다.';
    document.getElementById('s2-body').innerHTML = '<article class="paper doc s2-doc" aria-label="나의 종합 평가">' +
      (s.available ? ready(s) : waiting(s)) +
      '<p class="foot-note">AI를 쓰지 않고, 끝낸 면접 보고서의 질문 점수를 역량 태그별로 평균 내어 계산합니다.</p></article>';
    document.getElementById('summary').setAttribute('aria-busy', 'false');
  }

  document.addEventListener('DOMContentLoaded', function () {
    U.api(route('/user/me/summary')).then(render).catch(function (err) {
      document.getElementById('s2-meta').textContent = '';
      document.getElementById('s2-body').innerHTML = '<p class="error" role="alert">' + esc(err.message) + '</p>';
      document.getElementById('summary').setAttribute('aria-busy', 'false');
    });
  });
})();
