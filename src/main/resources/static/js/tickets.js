// tickets.js — 티켓 잔액·이력 표시, 묶음 결제(토스페이먼츠), 결제창 successUrl/failUrl 처리
(function () {
  'use strict';

  var USAGE_LABEL = {
    ANALYSIS: '공고·지원자 분석', KNOWLEDGE: '기초 지식 면접', MATERIAL: '자료 기반 면접', PERSONALITY: '인성 면접',
    SESSION_RETRY: '면접 다시 진행', QUESTION_REGENERATE: '질문 다시 생성'
  };
  var TYPE_LABEL = { CHARGE: '충전', USE: '사용', REFUND: '환불' };

  function api(path, options) {
    var opts = Object.assign({ credentials: 'same-origin' }, options || {});
    if (opts.json) {
      opts.method = opts.method || 'POST';
      opts.headers = { 'Content-Type': 'application/json' };
      opts.body = JSON.stringify(opts.json);
    }
    return fetch(window.JobPuzzleRoutes.path(path), opts).then(function (response) {
      return response.json().catch(function () { return null; }).then(function (body) {
        if (!response.ok || !body || !body.success) {
          throw new Error(body && body.message ? body.message : '요청을 처리하지 못했습니다.');
        }
        return body.data;
      });
    });
  }

  function esc(value) {
    return String(value == null ? '' : value)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  function showResult(ok, text) {
    var box = document.getElementById('ticket-result');
    box.hidden = false;
    box.className = 'ticket-result ' + (ok ? 'ticket-result--ok' : 'ticket-result--error');
    box.textContent = text;
  }

  function renderBalance(data) {
    document.getElementById('ticket-balance').textContent = data.balance;
    var c = data.costs;
    document.getElementById('ticket-costs').innerHTML =
      '<div class="ticket-cost"><b>공고·지원자 분석 ' + c.analysis + '장</b>공고와 내 자료를 비교해 분석</div>' +
      '<div class="ticket-cost"><b>기초 지식 면접 ' + c.knowledge + '장</b>공고가 요구하는 지식을 묻는 질문</div>' +
      '<div class="ticket-cost"><b>자료 기반 면접 ' + c.material + '장</b>내 경험과 자료를 파고드는 질문</div>' +
      '<div class="ticket-cost"><b>인성 면접 ' + c.personality + '장</b>희망 직무의 인성 질문</div>' +
      '<div class="ticket-cost"><b>같은 질문으로 다시 하기 ' + c.sessionRetry + '장</b>끝난 면접을 같은 질문으로 한 번 더</div>' +
      '<div class="ticket-cost"><b>새 질문으로 다시 하기 ' + c.questionRegenerate + '장</b>끝난 면접을 새 질문으로 (인성 면접은 티켓 없이)</div>';
    var rows = (data.history || []).map(function (h) {
      var label = (TYPE_LABEL[h.type] || h.type) + (h.usage ? ' · ' + (USAGE_LABEL[h.usage] || h.usage) : '');
      var change = h.amount > 0 ? '+' + h.amount : String(h.amount);
      return '<tr><td>' + esc((h.createdAt || '').replace('T', ' ').slice(0, 16)) + '</td><td>' + esc(label) +
        '</td><td class="' + (h.amount > 0 ? 'plus' : h.amount < 0 ? 'minus' : '') + '">' + esc(change) +
        '</td><td>' + esc(h.balanceAfter) + '장</td></tr>';
    }).join('');
    document.getElementById('ticket-history').innerHTML = rows || '<tr><td colspan="4">이용 내역이 없습니다.</td></tr>';
  }

  function loadBalance() {
    return api('/tickets/me').then(renderBalance).catch(function (error) { showResult(false, error.message); });
  }

  function renderProducts(data) {
    document.getElementById('ticket-products').innerHTML = data.products.map(function (p) {
      return '<div class="ticket-product"><span class="ticket-product__count">' + esc(p.tickets) + '장</span>' +
        '<span class="ticket-product__price">' + Number(p.price).toLocaleString('ko-KR') + '원</span>' +
        '<button type="button" class="dbtn dbtn--navy" data-product="' + esc(p.code) + '">구매하기</button></div>';
    }).join('');
    document.querySelectorAll('[data-product]').forEach(function (button) {
      button.addEventListener('click', function () { buy(button.dataset.product, button); });
    });
  }

  // 서버가 금액을 정한 주문을 만들고 토스 결제창을 연다. 결과는 successUrl/failUrl로 이 화면에 돌아온다.
  function buy(productCode, button) {
    if (typeof window.TossPayments !== 'function') {
      showResult(false, '결제 모듈을 불러오지 못했습니다. 새로고침 후 다시 시도해 주십시오.');
      return;
    }
    button.disabled = true;
    api('/payments/orders', { json: { productCode: productCode } }).then(function (order) {
      var base = window.location.origin + window.JobPuzzleRoutes.path('/tickets');
      var payment = window.TossPayments(order.clientKey).payment({ customerKey: window.TossPayments.ANONYMOUS });
      return payment.requestPayment({
        method: 'CARD',
        amount: { currency: 'KRW', value: order.amount },
        orderId: order.orderId,
        orderName: order.orderName,
        successUrl: base + '?payment=success',
        failUrl: base + '?payment=fail'
      });
    }).catch(function (error) {
      // 사용자가 결제창을 닫은 경우(USER_CANCEL)는 조용히 넘긴다.
      if (!error || error.code !== 'USER_CANCEL') showResult(false, (error && error.message) || '결제를 시작하지 못했습니다.');
    }).then(function () { button.disabled = false; });
  }

  // successUrl: paymentKey·orderId·amount를 서버에 넘겨 금액 검증·승인 후 티켓을 충전한다.
  function handleRedirect() {
    var q = new URLSearchParams(window.location.search);
    var result = q.get('payment');
    if (!result) return Promise.resolve();
    window.history.replaceState(null, '', window.location.pathname);
    if (result === 'fail') {
      showResult(false, '결제가 완료되지 않았습니다. ' + (q.get('message') || ''));
      return Promise.resolve();
    }
    showResult(true, '결제를 확인하고 있습니다…');
    return api('/payments/confirm', {
      json: { paymentKey: q.get('paymentKey'), orderId: q.get('orderId'), amount: Number(q.get('amount')) }
    }).then(function (data) {
      showResult(true, '티켓 ' + data.chargedTickets + '장이 충전되었습니다. 보유 티켓 ' + data.balance + '장');
      document.dispatchEvent(new CustomEvent('app-tickets-updated'));
    }).catch(function (error) { showResult(false, error.message); });
  }

  document.addEventListener('DOMContentLoaded', function () {
    handleRedirect().then(loadBalance);
    api('/tickets/products').then(renderProducts).catch(function (error) { showResult(false, error.message); });
  });
})();
