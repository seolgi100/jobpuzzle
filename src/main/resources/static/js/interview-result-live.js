// interview-result-live.js — 면접 결과: 최종 리포트(종합 점수·관점 레이더·약점·추천·서류 보완) + 질문별 결과 + 중간 결과 행동
(function () {
  'use strict';

  var U = window.JPUI, route = window.JobPuzzleRoutes.path, esc = U.esc;
  var sessionId = new URLSearchParams(window.location.search).get('sessionId');
  var DIMS = U.DIM_LABELS, DIM_KEYS = Object.keys(DIMS);
  var COLOR = { good: '#16A34A', mid: '#2F74E8', low: '#B45309', bad: '#DC2626', none: '#CBD5E1' };
  var QUESTION_TYPE = {
    GENERAL: '일반', COMPANY_FIT: '회사 맞춤', EXPERIENCE: '경험', PROBLEM_SOLVING: '문제 해결', SKILL: '기술',
    SELF_INTRO: '자기소개', MOTIVATION: '지원 동기', STRENGTH_WEAKNESS: '강점·약점',
    FAILURE_CONFLICT: '실패·갈등 경험', JOB_GENERAL: '직무 일반', WEAKNESS_FOLLOWUP: '약점 보완 후속'
  };
  var DOC_TARGET = { resume: '이력서', coverLetter: '자기소개서', portfolio: '포트폴리오', experienceNote: '경험 정리' };
  var ASSESSMENT = ['[잘한 점]', '[부족한 점]', '[총평]'];
  var s = { info: null, score: null, questions: [], byQuestion: {}, remaining: [], pending: [], cur: 0 };

  function notify(type, msg) { if (window.showToast) window.showToast(type, msg); }
  function get(p, o) { return U.api(route(p), o); }
  function badge(n, label) { return '<span class="score score--' + U.tone(n) + '">' + (label || n + '점') + '</span>'; }

  // 약점 태그 이름: 코드(resultExpression_weak)·영문 진단·한글 모두 받아서 한글로
  function weaknessLabel(tag) {
    var t = String(tag || '');
    if (/[가-힣]/.test(t)) return t;
    var v = t.toLowerCase().replace(/[_\-\s]/g, '');
    var map = [['lackofspecificdetail', '구체성 부족'], ['lackoftechnicaldepth', '기술적 깊이 부족'], ['insufficientcloudexperience', '클라우드 경험 부족'],
      ['vaguerole', '역할 설명 부족'], ['limitedownership', '주도성 부족'], ['requirementconnection', '공고 요구사항 연결 부족'],
      ['specificity', '경험 구체성 부족'], ['ownrole', '본인 역할 설명 부족'], ['problemsolving', '문제 해결 과정 부족'],
      ['resultexpression', '성과·결과 표현 부족'], ['guidealignment', '직무 기준 연결 부족'], ['deliveryclarity', '답변 전달력 부족'], ['intentmatch', '질문 의도 파악 부족']];
    for (var i = 0; i < map.length; i++) if (v.indexOf(map[i][0]) >= 0) return map[i][1];
    return '세부 진단 보완 필요';
  }

  // ── 질문별 결과 ──
  function qScore(q) { var x = s.byQuestion[q.sessionQuestionId]; return q.status === 'COMPLETED' && x && x.finalScore != null ? x.finalScore : null; }
  function qLabel(q) {
    var sc = qScore(q);
    if (sc != null) return sc + '점';
    var conv = q.conversation || [];
    var answered = conv.some(function (m) { return m.sender === 'USER'; });
    var failed = conv.some(function (m) { return m.messageType === 'EVALUATION_FAILED_ANSWER' || m.messageType === 'REJECTED_ANSWER'; });
    if (failed) return '미평가';
    if (q.status !== 'COMPLETED' && answered) return '진행 중';
    return answered ? '미평가' : '미응답';
  }

  function conversation(q) {
    var msgs = (q.conversation || []).filter(function (m) { return m.messageType !== 'ORIGINAL_QUESTION'; });
    if (!msgs.length) return '<p style="margin-top:20px; color:var(--text-3)">이 질문에는 답하지 않았어요.</p>';
    return '<div class="talk2"><h4>답변 과정</h4><small>점수에 반영된 실제 면접 대화예요</small>' + msgs.map(function (m, i) {
      var me = m.sender === 'USER';
      var failed = m.messageType === 'EVALUATION_FAILED_ANSWER' || m.messageType === 'REJECTED_ANSWER';
      var label = m.messageType === 'ORIGINAL_ANSWER' ? '첫 답변' : !me ? 'AI 추가 질문' : failed ? '평가 처리 실패 답변 · 점수 제외' : '추가 답변';
      var unanswered = m.messageType === 'FOLLOW_UP_QUESTION' && (!msgs[i + 1] || msgs[i + 1].messageType !== 'FOLLOW_UP_ANSWER');
      return '<div class="bubble bubble--' + (me ? 'me' : 'ai') + '"><b>' + label + (unanswered ? ' · 미응답' : '') + '</b>' + esc(m.messageText) + '</div>';
    }).join('') + '</div>';
  }

  function renderQuestions() {
    var qs = s.questions, sc = s.score;
    document.getElementById('qlist').innerHTML = qs.length ? qs.map(function (q, i) {
      return '<button type="button" class="qr-item" data-q="' + i + '" aria-current="' + (i === s.cur) + '"><b>' + (i + 1) + '</b><span>' + esc(q.questionText) + '</span>' + badge(qScore(q), qLabel(q)) + '</button>';
    }).join('') : '<p style="color:var(--text-3)">표시할 질문이 없어요.</p>';
    var q = qs[s.cur], el = document.getElementById('qdetail');
    if (!q) { el.innerHTML = ''; return; }
    var x = s.byQuestion[q.sessionQuestionId] || {}, done = q.status === 'COMPLETED';
    var dims = done ? x.dimensionScores || {} : {}, counts = done ? x.dimensionEvaluationCounts || {} : {};
    var evalCount = Object.keys(counts).reduce(function (m, k) { return Math.max(m, counts[k] || 0); }, 0);
    el.innerHTML = '<div class="qr-head"><span class="score score--none">질문 ' + (Number(q.displayOrder) > 0 ? q.displayOrder : s.cur + 1) + '</span>' + badge(qScore(q), qLabel(q)) + '</div>' +
      '<h2 class="qr-q">' + esc(q.questionText) + '</h2><p class="qr-intent">' + esc(q.intent || '질문 의도가 기록되지 않았어요.') + '</p>' +
      conversation(q) +
      '<div class="flow"><div>① 답변 분석<b>' + evalCount + '회</b></div><div>② 핵심 평가 기준<b>' + Object.keys(dims).length + '개</b></div><div>③ 질문 종합 점수<b>' + qLabel(q) + '</b></div></div>' +
      (Object.keys(dims).length ? '<div class="dims" style="margin-top:16px">' + Object.keys(dims).map(function (k) {
        return '<div class="dim"><span>' + esc(DIMS[k] || k) + '</span><i><b style="width:' + dims[k] + '%; background:' + COLOR[U.tone(dims[k])] + '"></b></i>' + badge(dims[k]) + '</div>';
      }).join('') + '</div>' : '') +
      '<div class="summary4"><div>답변한 질문<b>' + sc.submittedQuestionCount + '</b></div><div>평가 성공<b>' + sc.evaluatedQuestionCount + '</b></div>' +
      '<div>건너뜀<b>' + sc.skippedQuestionCount + '</b></div><div>완료율<b>' + sc.completionRate + '%</b></div></div>';
  }

  // ── 최종 리포트 ──
  function assessment(text) {
    if (!text) return '';
    var pos = ASSESSMENT.map(function (m) { return text.indexOf(m); });
    function body(t) {
      var lines = t.split('\n').map(function (l) { return l.trim(); }).filter(Boolean);
      return lines.length && lines.every(function (l) { return l.indexOf('-') === 0; })
        ? '<ul>' + lines.map(function (l) { return '<li>' + esc(l.replace(/^-\s*/, '')) + '</li>'; }).join('') + '</ul>'
        : '<p>' + esc(t).replace(/\n+/g, '<br>') + '</p>';
    }
    if (pos.some(function (p) { return p < 0; })) return '<div class="assess"><section>' + body(text) + '</section></div>';
    var part = function (i) { return text.slice(pos[i] + ASSESSMENT[i].length, i + 1 < ASSESSMENT.length ? pos[i + 1] : text.length).trim(); };
    var colors = ['var(--score-good)', 'var(--score-low)', 'var(--sky)'], titles = ['잘한 점', '부족한 점', '총평'];
    return '<div class="assess">' + titles.map(function (t, i) {
      var b = i === 1 ? part(i).replace(/^\([^)]*\)\s*/, '') : part(i);   // 옛 리포트의 "(세션 종합 점수: N점)" 제거
      return '<section><h4><i style="background:' + colors[i] + '"></i>' + t + '</h4>' + body(b) + '</section>';
    }).join('') + '</div>';
  }

  function radar(entries) {
    var R = 100, n = entries.length, svg = '';
    function pt(i, v) { var a = -Math.PI / 2 + i * 2 * Math.PI / n; return [Math.cos(a) * R * v / 100, Math.sin(a) * R * v / 100]; }
    [25, 50, 75, 100].forEach(function (lv) { svg += '<polygon class="grid" points="' + entries.map(function (_, i) { return pt(i, lv).join(','); }).join(' ') + '"/>'; });
    entries.forEach(function (e, i) {
      var end = pt(i, 100), l = pt(i, 122);
      svg += '<line class="grid" x1="0" y1="0" x2="' + end[0] + '" y2="' + end[1] + '"/>' +
        '<text x="' + l[0] + '" y="' + (l[1] + 4) + '" text-anchor="' + (Math.abs(l[0]) < 5 ? 'middle' : l[0] > 0 ? 'start' : 'end') + '">' + esc(DIMS[e.k]) + '</text>';
    });
    svg += '<polygon class="area" points="' + entries.map(function (e, i) { return pt(i, e.v).join(','); }).join(' ') + '"/>';
    entries.forEach(function (e, i) {
      var p = pt(i, e.v);
      svg += '<circle class="pt" cx="' + p[0] + '" cy="' + p[1] + '" r="4" style="fill:var(--score-' + U.tone(e.v) + ')"/><circle class="hit" cx="' + p[0] + '" cy="' + p[1] + '" r="12" data-i="' + i + '"/>';
    });
    return svg;
  }

  function renderFinal(r) {
    var entries = DIM_KEYS.filter(function (k) { return r.categoryScores && r.categoryScores[k] != null; }).map(function (k) { return { k: k, v: r.categoryScores[k] }; });
    var sorted = entries.slice().sort(function (a, b) { return b.v - a.v; });
    var over70 = entries.filter(function (e) { return e.v >= 70; }).length;
    document.querySelector('.rs-banner__score b').textContent = r.overallScore != null ? r.overallScore : '-';
    var weak = r.weaknessTagSummary || [], recs = r.nextPracticeRecommendation || [], learn = r.learningDirection || [], sug = r.improvementSuggestion || {};
    var docKeys = Object.keys(DOC_TARGET).filter(function (k) { return sug[k] && sug[k].length; });
    var html = '<div class="rs-grid"><section class="dcard"><h2 class="rs-h">면접 종합 점수</h2>' +
      '<p class="rs-sub">질문 ' + r.totalQuestionCount + '개 중 답한 ' + r.submittedQuestionCount + '개를 종합한 결과예요.</p>' +
      '<div class="overall">' + U.ring(r.overallScore) + (entries.length ? '<p style="margin:0; font-size:14px; color:var(--text-2); line-height:1.7">' + entries.length + '가지 관점 중 <b style="color:var(--text)">' + over70 + '개</b>가 70점을 넘었어요.<br>가장 아쉬운 관점은 <b style="color:var(--text)">' + esc(DIMS[sorted[sorted.length - 1].k]) + '</b>이에요.</p>' : '') + '</div>' +
      (assessment(r.overallAssessment) || '<p class="rs-sub">종합 평가 내용이 없어요.</p>') + '</section>' +
      '<section class="dcard"><h2 class="rs-h">관점별 점수</h2>' +
      (entries.length ? '<p class="dim-sum">' + (sorted[0].v === sorted[sorted.length - 1].v ? '관점별 점수가 <b>' + sorted[0].v + '점</b>으로 고르게 나왔어요.'
        : '가장 강한 관점은 <b>' + esc(DIMS[sorted[0].k]) + '(' + sorted[0].v + '점)</b>, 가장 약한 관점은 <b>' + esc(DIMS[sorted[sorted.length - 1].k]) + '(' + sorted[sorted.length - 1].v + '점)</b>이에요.') + '</p>' +
        (entries.length >= 3 ? '<div class="radar-wrap"><div style="position:relative"><svg class="radar" id="radar" viewBox="-150 -130 300 260" role="img" aria-label="관점별 점수 레이더 차트">' + radar(entries) + '</svg><div class="tip" id="tip" hidden></div></div>' +
          '<div class="legend" aria-label="점수 색 기준"><span><i style="background:var(--score-good)"></i>70점 이상</span><span><i style="background:var(--score-mid)"></i>50점 이상</span><span><i style="background:var(--score-low)"></i>25점 이상</span><span><i style="background:var(--score-bad)"></i>25점 미만</span></div></div>' : '') +
        '<div class="reasons">' + entries.map(function (e) {
          var reason = r.categoryScoreReasons && r.categoryScoreReasons[e.k];
          return '<details><summary>' + esc(DIMS[e.k]) + ' ' + badge(e.v) + '</summary><p>' + esc(reason || '아직 근거 설명이 없어요.') + '</p></details>';
        }).join('') + '</div>'
        : '<p class="rs-sub">평가에 성공한 관점이 없어요.</p>') + '</section></div>' +
      '<div class="rs-cols"><section class="dcard"><h2 class="rs-h">답변에서 발견한 약점</h2><p class="rs-sub">번호를 누르면 그 질문으로 이동해요</p>' +
      (weak.length ? weak.map(function (w) {
        var idx = [];
        s.questions.forEach(function (q, i) { var x = s.byQuestion[q.sessionQuestionId]; if (x && (x.weaknessTags || []).indexOf(w.tag) >= 0) idx.push(i); });
        return '<div class="weakpt"><div class="weakpt__top"><span class="score score--low">' + esc(weaknessLabel(w.tag)) + '</span><small>' + w.count + '회</small></div>' +
          (w.reason ? '<p>' + esc(w.reason) + '</p>' : '') +
          (idx.length ? '<div class="weakpt__qs">나온 질문 ' + idx.map(function (i) { return '<button type="button" class="qchip" data-goq="' + i + '">' + (i + 1) + '</button>'; }).join('') + '</div>' : '') + '</div>';
      }).join('') : '<p class="rs-sub">확인된 약점이 없어요.</p>') + '</section>' +
      '<section class="dcard"><h2 class="rs-h">다음에 연습하면 좋은 것</h2><p class="rs-sub">이번 면접에서 약했던 부분을 바로 이어서 연습해요</p>' +
      (recs.length ? recs.map(function (x) {
        return '<div class="next"><span class="mbadge">' + esc((QUESTION_TYPE[x.questionType] || x.questionType || '추천') + ' 질문') + '</span><p>' + esc(x.reason) + '</p></div>';
      }).join('') : '<p class="rs-sub">추천 항목이 없어요.</p>') + '</section></div>' +
      (docKeys.length || learn.length ? '<section class="dcard" style="margin-top:16px">' +
        (docKeys.length ? '<h2 class="rs-h">서류 보완 제안</h2><p class="rs-sub">면접에서 드러난 약점을 서류에도 반영해 보세요</p><div class="docs">' + docKeys.map(function (k) {
          return '<section><h4>' + DOC_TARGET[k] + '</h4><ul>' + sug[k].map(function (t) { return '<li>' + esc(t) + '</li>'; }).join('') + '</ul></section>';
        }).join('') + '</div>' : '') +
        (learn.length ? '<h2 class="rs-h" style="margin-top:' + (docKeys.length ? 22 : 0) + 'px">학습 방향</h2><ul class="learn" style="margin-top:8px">' + learn.map(function (t) { return '<li>' + esc(t) + '</li>'; }).join('') + '</ul>' : '') +
        '</section>' : '') +
      '<div class="rs-foot">' + (r.analysisCaseId ? '<a href="' + route('/analysis-results/' + encodeURIComponent(r.analysisCaseId)) + '" class="dbtn dbtn--line" style="height:40px">공고 요구사항 연결 분석 결과 보기</a>' : '') +
      '<a href="' + route('/interview?mode=weakness') + '" class="dbtn dbtn--navy">약점 보완 연습하기</a></div>';
    var panel = document.getElementById('p-final');
    panel.setAttribute('aria-busy', 'false');
    panel.innerHTML = html;
    var svg = document.getElementById('radar'), tip = document.getElementById('tip');
    if (svg) {
      svg.addEventListener('mousemove', function (e) {
        var h = e.target.closest('.hit');
        if (!h) { tip.hidden = true; return; }
        var x = entries[+h.dataset.i], box = svg.getBoundingClientRect(), hb = h.getBoundingClientRect();
        tip.textContent = DIMS[x.k] + ' ' + x.v + '점';
        tip.style.left = (hb.left + hb.width / 2 - box.left) + 'px'; tip.style.top = (hb.top - box.top) + 'px'; tip.hidden = false;
      });
      svg.addEventListener('mouseleave', function () { tip.hidden = true; });
    }
  }

  // AI 리포트를 기다리는 동안: 서버가 세부 진행률을 주지 않아서 100%는 표시하지 않는다
  function renderFinalLoading() {
    var steps = [[18, '저장된 면접 답변을 모으고 있어요.'], [42, '답변별 평가 근거를 정리하고 있어요.'], [68, '강점과 보완 방향을 종합하고 있어요.'], [88, '최종 리포트를 마무리하고 있어요.']];
    var i = 0, panel = document.getElementById('p-final');
    panel.setAttribute('aria-busy', 'true');
    panel.innerHTML = '<section class="dcard" role="status" style="text-align:center; padding:48px 24px"><span class="pill pill--active">AI 리포트 생성 중</span>' +
      '<h2 class="rs-h" style="margin-top:14px; font-size:20px">면접 답변을 종합하고 있어요</h2><p class="rs-sub" id="final-loading-copy">' + steps[0][1] + '</p>' +
      '<div class="an-bar" style="max-width:420px; margin:16px auto 0; height:8px; border-radius:99px; background:#EEF2F8; overflow:hidden"><i id="final-loading-bar" style="display:block; height:100%; width:' + steps[0][0] + '%; background:var(--sky); transition:width .6s"></i></div>' +
      '<p class="rs-sub" style="margin-top:12px">응답이 오면 최종 리포트가 자동으로 표시돼요.</p></section>';
    return setInterval(function () {
      i = Math.min(i + 1, steps.length - 1);
      document.getElementById('final-loading-bar').style.width = steps[i][0] + '%';
      document.getElementById('final-loading-copy').textContent = steps[i][1];
    }, 1800);
  }

  // ── 중간 결과(아직 확정 전) ──
  function renderInterim() {
    var el = document.getElementById('interim');
    if (!s.info || s.info.status === 'COMPLETED') { el.hidden = true; return; }
    var canFinalize = s.questions.some(function (q) { return qScore(q) != null; });
    el.hidden = false;
    el.innerHTML = '<span class="interim__label">중간 결과</span><h2>지금까지의 답변을 확인하고 다음 단계를 골라주세요</h2>' +
      '<p>아직 리포트가 확정되지 않았어요. 고른 질문을 이어서 답하거나 남은 질문을 추가할 수 있어요.</p><div class="interim__actions">' +
      (s.pending.length ? '<a class="dbtn dbtn--line" style="height:40px" href="' + route('/interview?resumeSessionId=' + encodeURIComponent(sessionId)) + '">고른 질문 ' + s.pending.length + '개 이어서 답하기</a>' : '') +
      (s.remaining.length ? '<button type="button" class="dbtn dbtn--line" style="height:40px" data-open-remaining>남은 질문 ' + s.remaining.length + '개 추가</button>'
        : s.pending.length ? '' : '<span class="rs-sub" style="align-self:center">준비된 질문을 모두 답했어요</span>') +
      (canFinalize ? '<button type="button" class="dbtn dbtn--navy" data-finalize>면접 끝내고 리포트 확정</button>'
        : '<span class="rs-sub" style="align-self:center">점수가 나온 질문이 1개 이상 있어야 리포트를 확정할 수 있어요.</span>') + '</div>';
  }

  function openRemaining() {
    var m = document.getElementById('remaining');
    document.getElementById('remaining-list').innerHTML = s.remaining.map(function (q, i) {
      var focus = (q.evaluationFocus || []).map(function (k) { return DIMS[k] || weaknessLabel(k); }).join(' · ');
      return '<label class="qr-item" style="cursor:pointer"><input type="checkbox" value="' + q.questionId + '" checked style="margin-top:3px; accent-color:var(--sky)">' +
        '<span><b style="margin-right:6px">' + (i + 1) + '</b>' + esc(q.questionText) +
        '<small style="display:block; margin-top:4px; color:var(--text-3)">' + esc(q.intent || '답변의 핵심 근거를 확인해요.') + (focus ? ' · ' + esc(focus) : '') + '</small></span></label>';
    }).join('');
    m.hidden = false;
  }

  function show(tab) {
    document.querySelectorAll('.dtab').forEach(function (t) {
      var on = t.dataset.tab === tab;
      t.setAttribute('aria-selected', String(on));
      document.getElementById('p-' + t.dataset.tab).hidden = !on;
    });
  }

  document.addEventListener('click', function (e) {
    var t = e.target.closest('button');
    if (!t || t.disabled) return;
    if (t.dataset.tab) show(t.dataset.tab);
    else if (t.dataset.q) { s.cur = +t.dataset.q; renderQuestions(); }
    else if (t.dataset.goq) { s.cur = +t.dataset.goq; renderQuestions(); show('questions'); window.scrollTo(0, 0); }
    else if (t.hasAttribute('data-open-remaining')) openRemaining();
    else if (t.hasAttribute('data-close')) t.closest('.modal').hidden = true;
    else if (t.hasAttribute('data-add-remaining')) {
      var ids = Array.prototype.map.call(document.querySelectorAll('#remaining-list input:checked'), function (i) { return Number(i.value); });
      if (!ids.length) { notify('warning', '이어갈 질문을 하나 이상 골라주세요.'); return; }
      t.disabled = true;
      get('/interview-sessions/' + sessionId + '/questions', { method: 'POST', json: { questionIds: ids } }).then(function () {
        window.location.href = route('/interview?resumeSessionId=' + encodeURIComponent(sessionId));
      }).catch(function (err) { notify('error', err.message); t.disabled = false; });
    }
    else if (t.hasAttribute('data-finalize')) {
      if (!window.confirm('이 면접을 확정할까요?\n\n확정하면 고르지 않은 질문은 더 추가할 수 없고, 리포트를 만들어요.')) return;
      t.disabled = true;
      get('/interview-sessions/' + sessionId + '/complete', { method: 'POST' }).then(function () {
        window.location.replace(route('/interview-results?sessionId=' + encodeURIComponent(sessionId)));
      }).catch(function (err) { notify('error', err.message); t.disabled = false; });
    }
  });

  document.addEventListener('DOMContentLoaded', function () {
    if (!sessionId) { window.location.replace(route('/reports')); return; }
    Promise.all([
      get('/interview-sessions/' + sessionId), get('/interview-sessions/' + sessionId + '/score'),
      get('/interview-sessions/' + sessionId + '/questions'), get('/interview-sessions/' + sessionId + '/questions/remaining')
    ]).then(function (v) {
      s.info = v[0]; s.score = v[1]; s.questions = v[2] || []; s.remaining = v[3] || [];
      s.pending = s.questions.filter(function (q) { return q.status === 'PENDING' || q.status === 'IN_PROGRESS'; });
      (s.score.questionScores || []).forEach(function (x) { s.byQuestion[x.sessionQuestionId] = x; });
      var sc = s.score;
      document.getElementById('result-title').textContent = U.mode(sc.mode)[0] + ' 결과';
      document.getElementById('result-meta').textContent = '질문 ' + sc.totalQuestionCount + '개 · 답변 ' + sc.submittedQuestionCount + '개 · 평가 성공 ' + sc.evaluatedQuestionCount + '개' +
        (s.info.completedAt ? ' · ' + U.dateTime(s.info.completedAt) : '');
      renderQuestions();
      renderInterim();
      if (s.info.status !== 'COMPLETED') {
        document.querySelectorAll('[data-final]').forEach(function (el) { el.hidden = true; });
        show('questions');
        return;
      }
      // 완료된 면접은 리포트 메뉴를 활성으로
      document.querySelectorAll('.site-header__nav a').forEach(function (a) { a.classList.toggle('is-active', a.getAttribute('href') === route('/reports')); });
      document.querySelector('.rs-banner__score b').textContent = sc.overallScore != null ? sc.overallScore : '-';
      var timer = renderFinalLoading();
      get('/final-report/sessions/' + sessionId).then(function (report) { clearInterval(timer); renderFinal(report); })
        .catch(function (err) {
          clearInterval(timer);
          document.getElementById('p-final').innerHTML = '<section class="dcard"><p class="rs-sub">' + esc(err.message) + '</p></section>';
          notify('error', err.message);
        });
    }).catch(function (err) { document.getElementById('result-meta').textContent = err.message; });
  });
})();
