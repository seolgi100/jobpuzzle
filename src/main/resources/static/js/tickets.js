// tickets.js — 티켓 잔액·이력 표시, 묶음 결제(토스페이먼츠), 결제창 successUrl/failUrl 처리
(function () {
  'use strict';

  var USAGE_LABEL = { BASIC: '기본 면접', WEAKNESS_REVIEW: '약점 보완 면접', COMPANY_FIT: '공고 맞춤 분석' };
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
    document.getElementById('ticket-free-badge').hidden = !data.freeBasicAvailable;
    var c = data.costs;
    var fitCost = c.companyFitMin === c.companyFitMax ? c.companyFitMin + '장' : c.companyFitMin + '~' + c.companyFitMax + '장';
    document.getElementById('ticket-costs').innerHTML =
      '<div class="ticket-cost"><b>기본 면접 ' + c.basic + '장</b>첫 1회는 무료</div>' +
      '<div class="ticket-cost"><b>약점 보완 ' + c.weakness + '장</b>쌓인 약점으로 질문 생성</div>' +
      '<div class="ticket-cost"><b>공고 맞춤 ' + fitCost + '</b>이력서만이면 ' + c.companyFitMin +
      '장, 자기소개서·포트폴리오·경험정리를 더할수록 1장씩</div>';
    var rows = (data.history || []).map(function (h) {
      var label = (TYPE_LABEL[h.type] || h.type) + (h.usage ? ' · ' + (USAGE_LABEL[h.usage] || h.usage) : '') +
        (h.freeUse ? ' (무료)' : '');
      var change = h.amount > 0 ? '+' + h.amount : String(h.amount);
      return '<tr><td>' + esc((h.createdAt || '').replace('T', ' ').slice(0, 16)) + '</td><td>' + esc(label) +
        '</td><td class="' + (h.amount > 0 ? 'plus' : h.amount < 0 ? 'minus' : '') + '">' + esc(change) +
        '</td><td>' + esc(h.balanceAfter) + '장</td></tr>';
    }).join('');
    document.getElementById('ticket-history').innerHTML = rows || '<tr><td colspan="4">이용 내역이 없어요.</td></tr>';
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
      showResult(false, '결제 모듈을 불러오지 못했어요. 새로고침 후 다시 시도해 주세요.');
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
      if (!error || error.code !== 'USER_CANCEL') showResult(false, (error && error.message) || '결제를 시작하지 못했어요.');
    }).then(function () { button.disabled = false; });
  }

  // successUrl: paymentKey·orderId·amount를 서버에 넘겨 금액 검증·승인 후 티켓을 충전한다.
  function handleRedirect() {
    var q = new URLSearchParams(window.location.search);
    var result = q.get('payment');
    if (!result) return Promise.resolve();
    window.history.replaceState(null, '', window.location.pathname);
    if (result === 'fail') {
      showResult(false, '결제가 완료되지 않았어요. ' + (q.get('message') || ''));
      return Promise.resolve();
    }
    showResult(true, '결제를 확인하고 있어요…');
    return api('/payments/confirm', {
      json: { paymentKey: q.get('paymentKey'), orderId: q.get('orderId'), amount: Number(q.get('amount')) }
    }).then(function (data) {
      showResult(true, '티켓 ' + data.chargedTickets + '장이 충전됐어요. 보유 티켓 ' + data.balance + '장');
    }).catch(function (error) { showResult(false, error.message); });
  }

  document.addEventListener('DOMContentLoaded', function () {
    handleRedirect().then(loadBalance);
    api('/tickets/products').then(renderProducts).catch(function (error) { showResult(false, error.message); });
  });
})();
