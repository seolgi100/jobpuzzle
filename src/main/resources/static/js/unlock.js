// unlock.js — /unlock 페이지: 아이디+이메일 인증 코드 발송/검증으로 잠긴 계정 해제
(function () {
  'use strict';

  var CODE_VALID_SECONDS = 180;

  function showError(el, message) {
    el.textContent = message;
    el.classList.add('is-visible');
  }

  function hideError(el) {
    el.classList.remove('is-visible');
  }

  function formatMMSS(totalSeconds) {
    var m = Math.floor(totalSeconds / 60);
    var s = totalSeconds % 60;
    return m + ':' + (s < 10 ? '0' + s : s);
  }

  document.addEventListener('DOMContentLoaded', function () {
    var errorEl = document.getElementById('unlock-error');
    var loginIdInput = document.getElementById('loginId');
    var emailInput = document.getElementById('email');
    var codeInput = document.getElementById('code');

    var sendBtn = document.getElementById('send-code-btn');
    var verifyBtn = document.getElementById('verify-btn');

    var codeStep = document.getElementById('code-step');
    var resultBox = document.getElementById('unlock-result');

    var sendHint = document.getElementById('send-hint');
    var codeHint = document.getElementById('code-hint');
    var countdownEl = document.getElementById('countdown');

    var timerId = null;

    function stopCountdown() {
      if (timerId) {
        clearInterval(timerId);
        timerId = null;
      }
    }

    function startCountdown() {
      stopCountdown();
      var remaining = CODE_VALID_SECONDS;
      countdownEl.textContent = formatMMSS(remaining);
      codeHint.classList.remove('field-hint--error');
      verifyBtn.disabled = false;

      timerId = setInterval(function () {
        remaining -= 1;
        if (remaining <= 0) {
          stopCountdown();
          codeHint.textContent = '인증 시간이 만료되었습니다. 인증코드를 다시 발송해주세요.';
          codeHint.classList.add('field-hint--error');
          verifyBtn.disabled = true;
          return;
        }
        countdownEl.textContent = formatMMSS(remaining);
      }, 1000);
    }

    sendBtn.addEventListener('click', function () {
      hideError(errorEl);

      var loginId = loginIdInput.value.trim();
      var email = emailInput.value.trim();
      if (!loginId || !email) {
        showError(errorEl, '아이디와 이메일을 입력해주세요.');
        return;
      }

      sendBtn.disabled = true;
      var sendBtnOriginalText = sendBtn.textContent;
      sendBtn.textContent = '전송 중...';
      fetch(window.JobPuzzleRoutes.path('/user/unlock/send-code'), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'same-origin',
        body: JSON.stringify({ loginId: loginId, email: email })
      })
        .then(function (res) {
          return res.json().then(function (body) { return { ok: res.ok, body: body }; });
        })
        .then(function (result) {
          if (result.ok && result.body.success) {
            sendHint.textContent = '인증 코드를 보냈습니다. 이메일을 확인해주세요.';
            sendHint.className = 'field-hint field-hint--ok';
            codeHint.innerHTML = '이메일로 받은 6자리 코드를 <strong id="countdown"></strong> 안에 입력해주세요.';
            codeHint.classList.remove('field-hint--error');
            countdownEl = document.getElementById('countdown');
            codeStep.hidden = false;
            resultBox.hidden = true;
            startCountdown();
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

      var loginId = loginIdInput.value.trim();
      var email = emailInput.value.trim();
      var code = codeInput.value.trim();
      if (!code) {
        showError(errorEl, '인증 코드를 입력해주세요.');
        return;
      }

      verifyBtn.disabled = true;
      fetch(window.JobPuzzleRoutes.path('/user/unlock/verify'), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'same-origin',
        body: JSON.stringify({ loginId: loginId, email: email, code: Number(code) })
      })
        .then(function (res) {
          return res.json().then(function (body) { return { ok: res.ok, body: body }; });
        })
        .then(function (result) {
          if (result.ok && result.body.success) {
            stopCountdown();
            codeStep.hidden = true;
            document.getElementById('unlock-form').hidden = true;
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
