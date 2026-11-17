// find-id.js — /find-id 페이지: 이메일 인증 코드 발송/검증 후 로그인 아이디 안내
(function () {
  'use strict';

  function showError(el, message) {
    el.textContent = message;
    el.classList.add('is-visible');
  }

  function hideError(el) {
    el.classList.remove('is-visible');
  }

  document.addEventListener('DOMContentLoaded', function () {
    var errorEl = document.getElementById('find-id-error');
    var emailInput = document.getElementById('email');
    var codeInput = document.getElementById('code');
    var sendBtn = document.getElementById('send-code-btn');
    var verifyBtn = document.getElementById('verify-btn');
    var codeStep = document.getElementById('code-step');
    var sendHint = document.getElementById('send-hint');
    var resultBox = document.getElementById('find-id-result');
    var resultLoginId = document.getElementById('result-login-id');

    sendBtn.addEventListener('click', function () {
      hideError(errorEl);

      var email = emailInput.value.trim();
      if (!email) {
        showError(errorEl, '이메일을 입력해주세요.');
        return;
      }

      sendBtn.disabled = true;
      var sendBtnOriginalText = sendBtn.textContent;
      sendBtn.textContent = '전송 중...';
      fetch(window.JobPuzzleRoutes.path('/user/find-id/send-code'), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'same-origin',
        body: JSON.stringify({ email: email })
      })
        .then(function (res) {
          return res.json().then(function (body) { return { ok: res.ok, body: body }; });
        })
        .then(function (result) {
          if (result.ok && result.body.success) {
            sendHint.textContent = '인증 코드를 보냈습니다. 이메일을 확인해주세요.';
            sendHint.className = 'field-hint field-hint--ok';
            codeStep.hidden = false;
          } else {
            showError(errorEl, result.body.message || '인증 코드 발송에 실패했습니다.');
          }
        })
        .catch(function () {
          showError(errorEl, '일시적인 오류가 발생했습니다. 잠시 후 다시 시도해주세요.');
        })
        .then(function () {
          sendBtn.disabled = false;
          sendBtn.textContent = sendBtnOriginalText;
        });
    });

    verifyBtn.addEventListener('click', function () {
      hideError(errorEl);

      var email = emailInput.value.trim();
      var code = codeInput.value.trim();
      if (!code) {
        showError(errorEl, '인증 코드를 입력해주세요.');
        return;
      }

      verifyBtn.disabled = true;
      fetch(window.JobPuzzleRoutes.path('/user/find-id/verify'), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'same-origin',
        body: JSON.stringify({ email: email, code: Number(code) })
      })
        .then(function (res) {
          return res.json().then(function (body) { return { ok: res.ok, body: body }; });
        })
        .then(function (result) {
          if (result.ok && result.body.success) {
            resultLoginId.textContent = result.body.data;
            document.getElementById('find-id-form').hidden = true;
            resultBox.hidden = false;
          } else {
            showError(errorEl, result.body.message || '인증 코드가 올바르지 않습니다.');
          }
        })
        .catch(function () {
          showError(errorEl, '일시적인 오류가 발생했습니다. 잠시 후 다시 시도해주세요.');
        })
        .then(function () {
          verifyBtn.disabled = false;
        });
    });
  });
})();
