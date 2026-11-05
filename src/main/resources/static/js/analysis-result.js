// analysis-result.js — 완료된 맞춤 분석 리포트: 연결도 점수 · 연결 분포 · 요구사항별 근거 · 예상 질문 · 보완 과제
(function () {
  'use strict';

  var root = document.getElementById('analysis-result-root');
  var caseId = root && root.dataset.analysisCaseId;
  var U = window.JPUI, route = window.JobPuzzleRoutes.path, esc = U.esc;
  // 화면 등급: 판단 보류(INSUFFICIENT)는 근거 부족과 같은 칸에 넣는다
  var LV = { HIGH: ['충족', 'high'], MEDIUM: ['일부 충족', 'medium'], LOW: ['낮은 연결', 'low'], NONE: ['근거 부족', 'none'], INSUFFICIENT: ['판단 보류', 'none'] };
  var TYPE = { EXPERIENCE: '경험', SKILL: '기술', PROBLEM_SOLVING: '문제 해결', COMPANY_FIT: '직무 적합', GENERAL: '일반' };
  var SRC = { RESUME: '이력서', COVER_LETTER: '자기소개서', PORTFOLIO: '포트폴리오', EXPERIENCE_NOTE: '경험 정리', JOB_POSTING: '채용공고', COMPANY_INFO: '회사정보' };
  var QUESTION_PREVIEW = 6;
  var filter = 'ALL', data = null;

  function lv(m) { return LV[m.matchLevel] || LV.NONE; }
  function connected(level) { return level === 'HIGH' || level === 'MEDIUM' || level === 'LOW'; }
  // 연결도 점수: 충족 100 · 일부 충족 50 · 낮은 연결 25 의 평균(약한 연결이 점수를 부풀리지 않게)
  function score(matches) {
    if (!matches.length) return 0;
    var sum = matches.reduce(function (s, m) { return s + ({ HIGH: 100, MEDIUM: 50, LOW: 25 }[m.matchLevel] || 0); }, 0);
    return Math.round(sum / matches.length);
  }
  function dateDot(v) { if (!v) return ''; var d = new Date(v); return d.getFullYear() + '.' + String(d.getMonth() + 1).padStart(2, '0') + '.' + String(d.getDate()).padStart(2, '0'); }

  function renderReqs() {
    var matches = data.result.requirementMatches || [];
    var qs = data.questions;
    document.querySelector('[data-reqs]').innerHTML = matches.filter(function (m) {
      var k = lv(m)[1];
      return filter === 'ALL' || (filter === 'high' && k === 'high') || (filter === 'part' && (k === 'medium' || k === 'low')) || (filter === 'none' && k === 'none');
    }).map(function (m) {
      var must = m.requirementType === 'REQUIRED';
      var related = qs.map(function (q, i) { return q.relatedRequirementId === m.requirementId ? (i + 1) + '번' : null; }).filter(Boolean);
      var refs = (m.candidateSourceRefs || []).map(function (r) { return (SRC[r.documentType] || '지원 자료') + (r.pageNumber ? ' ' + r.pageNumber + '쪽' : ''); })
        .filter(function (v, i, all) { return all.indexOf(v) === i; });
      return '<details class="req"><summary><span class="req__kind' + (must ? ' req__kind--must' : '') + '">' + (must ? '필수' : '우대') + '</span>' +
        '<p class="req__text">' + esc(m.requirement) + '</p><span class="lvp lvp--' + lv(m)[1] + '">' + lv(m)[0] + '</span></summary><div class="req__body">' +
        (m.candidateEvidence ? '<div class="ev"><small>확인된 근거</small>' + esc(m.candidateEvidence) + '</div>' : '') +
        (m.missingPoint ? '<div class="ev ev--miss"><small>부족한 근거</small>' + esc(m.missingPoint) + '</div>' : '') +
        (m.reason ? '<div class="ev ev--wide"><small>판단 이유</small>' + esc(m.reason) + '</div>' : '') +
        '<div class="ev ev--wide"><small>사용된 자료' + (related.length ? ' · 관련 질문 ' + related.join(', ') : '') + '</small><div class="ev__src">' +
        (refs.length ? refs.map(function (s) { return '<span>' + esc(s) + '</span>'; }).join('') : '<span>연결된 자료 없음</span>') + '</div></div>' +
        '</div></details>';
    }).join('') || '<p class="rs-sub">해당하는 요구사항이 없어요.</p>';
  }

  function render(result, analysisCase, plans) {
    var matches = result.requirementMatches || [];
    var questions = (result.questionSet && result.questionSet.questions || []).slice().sort(function (a, b) { return (a.displayOrder || 0) - (b.displayOrder || 0); });
    data = { result: result, questions: questions };
    var counts = { high: 0, medium: 0, low: 0, none: 0 };
    matches.forEach(function (m) { counts[lv(m)[1]]++; });
    var linked = matches.filter(function (m) { return connected(m.matchLevel); }).length;
    var req = matches.filter(function (m) { return m.requirementType === 'REQUIRED'; });
    var pref = matches.filter(function (m) { return m.requirementType === 'PREFERRED'; });
    var cat = (analysisCase && (analysisCase.mainCategory || analysisCase.subCategory)) ? analysisCase : (result.jobCategory || {});
    var career = { NEW: '신입', EXPERIENCED: '경력', ANY: '경력 무관' }[analysisCase && analysisCase.careerLevel] || '';
    var sources = analysisCase && analysisCase.sources || [];

    // 면접 연결: 시작 가능 / 이미 연결된 면접(완료·진행 중) / 둘 다 아님
    var canStart = questions.length > 0 && result.interviewStartAllowed === true;
    var sid = result.linkedSessionId, completed = result.linkedSessionStatus === 'COMPLETED';
    var cta = canStart ? ['맞춤 면접 시작', route('/interview?analysisCaseId=' + encodeURIComponent(caseId))]
      : sid ? (completed ? ['완료한 면접 결과 보기', route('/interview-results?sessionId=' + encodeURIComponent(sid))]
        : ['진행 중인 면접으로 돌아가기', route('/interview?resumeSessionId=' + encodeURIComponent(sid))])
        : ['면접 준비로 이동', route('/interview')];
    var ctaSub = canStart ? '부족한 근거를 중심으로 만든 질문으로 바로 면접을 볼 수 있어요.'
      : sid ? (completed ? '이 분석으로 본 면접 결과를 다시 확인할 수 있어요.' : '이 분석으로 진행 중인 면접이 있어요.')
        : '자료를 보완한 뒤 새 분석을 진행해 보세요.';

    var limits = result.readiness && result.readiness.limitations || [];
    root.innerHTML =
      '<div class="rs-links"><a href="' + route('/interview') + '">← 면접 준비</a><a href="' + route('/action-plans?analysisCaseId=' + encodeURIComponent(caseId)) + '">액션플랜 →</a></div>' +
      '<section class="rs-banner"><div><p class="rs-banner__label">JOB FIT REPORT</p>' +
      '<p class="rs-banner__title">' + esc([cat.mainCategory, cat.subCategory].filter(Boolean).join(' · ') || '맞춤') + ' 지원 분석</p>' +
      '<p class="rs-banner__meta">' + esc(['분석 #' + result.analysisCaseId, analysisCase && analysisCase.createdAt ? dateDot(analysisCase.createdAt) + ' 분석' : '', career].filter(Boolean).join(' · ')) +
      (sources.length ? ' · 사용 자료: ' + sources.map(function (s) { return esc(s.displayName || SRC[s.documentType]); }).join(' · ') : '') + '</p></div>' +
      '<div class="rs-banner__score"><b>' + score(matches) + '</b><small>요구사항 연결도 / 100</small></div></section>' +
      (limits.length ? '<p class="rs-sub" style="margin:12px 0 0; color:var(--warn)">' + limits.map(esc).join(' · ') + '</p>' : '') +
      '<div class="ar-top"><section class="dcard"><h2 class="rs-h">요구사항 연결 분포</h2>' +
      '<p class="rs-sub" style="margin-bottom:0">공고 요구사항 ' + matches.length + '개 중 ' + linked + '개에서 근거를 찾았고, ' + (matches.length - linked) + '개는 근거를 더 채우면 좋아요.</p>' +
      '<div class="ar-dist">' + Object.keys(counts).map(function (k) { return counts[k] ? '<i class="lv--' + k + '" style="flex:' + counts[k] + '"></i>' : ''; }).join('') + '</div>' +
      '<div class="ar-legend">' + ['high', 'medium', 'low', 'none'].map(function (k) { return '<span><i class="lv--' + k + '"></i>' + { high: '충족', medium: '일부 충족', low: '낮은 연결', none: '근거 부족' }[k] + ' <b>' + counts[k] + '</b></span>'; }).join('') + '</div>' +
      '<div class="ar-kind"><div>필수 요구사항<b>' + req.length + '개 중 ' + req.filter(function (m) { return connected(m.matchLevel); }).length + '개 연결</b></div>' +
      '<div>우대 요구사항<b>' + pref.length + '개 중 ' + pref.filter(function (m) { return connected(m.matchLevel); }).length + '개 연결</b></div></div></section>' +
      '<section class="dcard ar-next"><h2 class="rs-h">분석을 연습으로 이어가요</h2><p class="rs-sub" style="margin-bottom:0">' + ctaSub + '</p>' +
      '<div class="ar-next__row"><span>예상 면접 질문</span><b>' + questions.length + '개</b></div>' +
      '<div class="ar-next__row"><span>보완 과제</span><b>' + plans.length + '개</b></div>' +
      '<a href="' + cta[1] + '" class="dbtn dbtn--white" style="margin-top:16px">' + cta[0] + '</a></section></div>' +
      '<section class="ar-sec" aria-labelledby="req-title"><div class="ar-sec__head"><div><h2 id="req-title">요구사항 연결 분석<small>' + matches.length + '개</small></h2>' +
      '<p>공고의 조건과 내 자료에서 찾은 근거를 나란히 비교해요. 눌러서 자세히 볼 수 있어요.</p></div>' +
      '<div class="chips" role="group" aria-label="연결 정도">' + [['ALL', '전체'], ['high', '충족'], ['part', '일부 충족'], ['none', '근거 부족']].map(function (c) {
        return '<button type="button" class="chip" data-lv="' + c[0] + '" aria-pressed="' + (filter === c[0]) + '">' + c[1] + '</button>';
      }).join('') + '</div></div><div data-reqs></div></section>' +
      '<div class="dcols ar-sec" style="align-items:start"><section class="dcard" aria-labelledby="q-title"><h2 class="rs-h" id="q-title">예상 면접 질문</h2>' +
      '<p class="rs-sub">분석 근거를 바탕으로 실제 면접에서 나올 가능성이 높은 질문이에요.</p>' +
      (questions.length ? questions.slice(0, QUESTION_PREVIEW).map(function (q, i) {
        return '<div class="aq"><span class="aq__n">' + (i + 1) + '</span><div><span class="mbadge mbadge--basic">' + (TYPE[q.questionType] || '일반') + '</span>' +
          '<p class="aq__q">' + esc(q.question) + '</p>' + (q.intent ? '<p class="aq__why"><b>확인하는 것</b> · ' + esc(q.intent) + '</p>' : '') + '</div></div>';
      }).join('') + (questions.length > QUESTION_PREVIEW ? '<p class="rs-sub" style="margin:8px 0 0">외 ' + (questions.length - QUESTION_PREVIEW) + '개 · 전체 질문은 면접 시작 전 질문 고르기에서 볼 수 있어요.</p>' : '')
        : '<p class="rs-sub">만들어진 질문이 없어요.</p>') + '</section>' +
      '<section class="dcard" aria-labelledby="p-title"><h2 class="rs-h" id="p-title">보완 과제</h2><p class="rs-sub">마감일과 완료는 액션플랜에서 관리해요.</p>' +
      (plans.length ? plans.map(function (p) {
        var done = p.status === 'DONE';
        return '<div class="ap-row"><div><p>' + esc(p.suggestion || '보완 과제') + '</p><small>' + esc([p.requirement ? '#' + p.requirement : '', p.missingPoint].filter(Boolean).join(' · ')) + '</small></div>' +
          '<span class="pill ' + (done ? 'pill--done">완료' : 'pill--active">할 일') + '</span></div>';
      }).join('') : '<p class="rs-sub">보완 과제가 없어요.</p>') +
      '<a href="' + route('/action-plans?analysisCaseId=' + encodeURIComponent(caseId)) + '" class="dbtn dbtn--line" style="margin-top:12px; width:100%; height:40px">액션플랜에서 일정 관리하기 →</a></section></div>';
    root.setAttribute('aria-busy', 'false');
    renderReqs();
  }

  function renderUnavailable(message, href, label) {
    root.setAttribute('aria-busy', 'false');
    root.innerHTML = '<section class="dcard" style="margin-top:24px; text-align:center; padding:48px 24px"><h1 class="rs-h" style="font-size:20px">분석 결과를 아직 열 수 없어요</h1>' +
      '<p class="rs-sub">' + esc(message) + '</p><a class="dbtn dbtn--navy" href="' + href + '">' + esc(label) + '</a></section>';
  }

  if (!root || !caseId || !/^\d+$/.test(caseId) || Number(caseId) <= 0) {
    if (root) renderUnavailable('잘못된 분석 결과 주소예요.', route('/interview'), '면접 준비로 이동');
    return;
  }

  root.addEventListener('click', function (e) {
    var b = e.target.closest('[data-lv]');
    if (!b) return;
    filter = b.dataset.lv;
    root.querySelectorAll('[data-lv]').forEach(function (x) { x.setAttribute('aria-pressed', String(x === b)); });
    renderReqs();
  });

  U.api(route('/analysis/cases/' + caseId + '/status'))
    .then(function (status) {
      if (status.analysisCaseStatus !== 'COMPLETED') {
        renderUnavailable('분석이 진행 중이거나 결과가 아직 준비되지 않았어요.', route('/analysis/' + encodeURIComponent(caseId)), '분석 상태 확인');
        return null;
      }
      return Promise.all([
        U.api(route('/analysis/cases/' + caseId + '/result')),
        // 자료 정보·과제 상태는 보조 정보라 실패해도 결과는 보여준다
        U.api(route('/analysis-cases/' + caseId)).catch(function () { return null; }),
        U.api(route('/action-plan')).catch(function () { return null; })
      ]);
    })
    .then(function (v) {
      if (!v) return;
      var plans = Array.isArray(v[2]) ? v[2].filter(function (p) { return String(p.analysisCaseId) === String(caseId); }) : (v[0].actionPlans || []);
      render(v[0], v[1], plans);
    })
    .catch(function (error) {
      renderUnavailable(error.status === 403 ? '이 분석 결과를 볼 권한이 없어요.' : '잠시 후 다시 시도해 주세요.', route('/interview'), '면접 준비로 이동');
    });
})();
