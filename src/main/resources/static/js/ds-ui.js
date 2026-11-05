// ds-ui.js — 새 디자인 화면들이 같이 쓰는 표시 도구(점수 색, 점수 원, 관점 이름, 면접 모드 배지, API 호출)
(function (global) {
  'use strict';

  var DIM_LABELS = {
    intentMatch: '질문 의도 이해',
    specificity: '경험 구체성',
    ownRole: '본인 역할',
    problemSolving: '문제 해결 과정',
    resultExpression: '성과 및 결과 표현',
    requirementConnection: '공고 요구사항 연결',
    guideAlignment: '직무 가이드 적합',
    deliveryClarity: '답변 전달력'
  };
  // 면접 모드 → [이름, 배지 클래스, 필터 키]
  var MODES = {
    COMPANY_FIT: ['맞춤 면접', 'mbadge--custom', 'custom'],
    BASIC: ['기본 질문', 'mbadge--basic', 'basic'],
    WEAKNESS_REVIEW: ['약점 보완', 'mbadge--weak', 'weak']
  };

  function esc(value) {
    var el = document.createElement('div');
    el.textContent = value == null ? '' : String(value);
    return el.innerHTML;
  }

  function tone(n) {
    if (n == null) return 'none';
    return n >= 70 ? 'good' : n >= 50 ? 'mid' : n >= 25 ? 'low' : 'bad';
  }

  // 종합 점수 원형 그래프
  function ring(n) {
    var r = 64, c = 2 * Math.PI * r;
    var color = { good: '#16A34A', mid: '#2F74E8', low: '#B45309', bad: '#DC2626', none: '#CBD5E1' }[tone(n)];
    var v = n == null ? 0 : Math.max(0, Math.min(100, n));
    return '<div class="ring" role="img" aria-label="종합 점수 ' + (n == null ? '없음' : n + '점') + '"><svg viewBox="0 0 160 160">' +
      '<circle cx="80" cy="80" r="' + r + '" fill="none" stroke="#E2E8F3" stroke-width="14"/>' +
      '<circle cx="80" cy="80" r="' + r + '" fill="none" stroke="' + color + '" stroke-width="14" stroke-linecap="round" stroke-dasharray="' + (c * v / 100) + ' ' + c + '"/></svg>' +
      '<div class="ring__num"><b>' + (n == null ? '-' : n) + '</b><small>/ 100점</small></div></div>';
  }

  function mode(m) { return MODES[m] || MODES.WEAKNESS_REVIEW; }
  function modeBadge(m) { var x = mode(m); return '<span class="mbadge ' + x[1] + '">' + x[0] + '</span>'; }

  // 약점 태그 코드(resultExpression_weak) → "성과 및 결과 표현 부족"
  function weakLabel(code) {
    var key = String(code || '').replace(/_weak$/, '');
    return DIM_LABELS[key] ? DIM_LABELS[key] + ' 부족' : String(code || '');
  }

  function dateTime(value) {
    if (!value) return '';
    return new Date(value).toLocaleString('ko-KR', { year: 'numeric', month: 'long', day: 'numeric', hour: 'numeric', minute: '2-digit' });
  }

  // 공통 API 응답({success,data,message})을 data로 풀어준다.
  function api(path, options) {
    options = options || {};
    var init = { method: options.method || 'GET', credentials: 'same-origin', headers: {} };
    if (options.json !== undefined) {
      init.headers['Content-Type'] = 'application/json; charset=UTF-8';
      init.body = JSON.stringify(options.json);
    }
    return fetch(path, init).then(function (response) {
      return response.json().catch(function () { return {}; }).then(function (body) {
        if (!response.ok || !body.success) {
          var error = new Error(body.message || '요청을 처리하지 못했어요.');
          error.status = response.status; error.code = body.code;
          throw error;
        }
        return body.data;
      });
    });
  }

  global.JPUI = { DIM_LABELS: DIM_LABELS, esc: esc, tone: tone, ring: ring, mode: mode, modeBadge: modeBadge, weakLabel: weakLabel, dateTime: dateTime, api: api };
})(window);
