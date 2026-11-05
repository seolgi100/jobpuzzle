// login.js — /login 페이지: 아이디/비밀번호 로그인 폼 처리
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
    var form = document.getElementById('login-form');
    var errorEl = document.getElementById('login-error');

    form.addEventListener('submit', function (e) {
      e.preventDefault();
      hideError(errorEl);

      var loginId = document.getElementById('loginId').value.trim();
      var password = document.getElementById('password').value;
      var autoLogin = document.getElementById('autoLogin').checked;

      fetch(window.JobPuzzleRoutes.path('/user/login'), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'same-origin',
        body: JSON.stringify({ loginId: loginId, password: password, autoLogin: autoLogin })
      })
        .then(function (res) {
          return res.json().then(function (body) { return { ok: res.ok, body: body }; });
        })
        .then(function (result) {
          if (result.ok && result.body.success) {
            // 보호된 화면에서 이동해 왔다면 서버가 보관한 원래 주소로, 아니면 메인으로 이동한다.
            window.location.href = result.body.data || window.JobPuzzleRoutes.path('/');
            return;
          }

          showError(errorEl, result.body.message || '로그인에 실패했습니다.');
        })
        .catch(function () {
          showError(errorEl, '일시적인 오류가 발생했습니다. 잠시 후 다시 시도해주세요.');
        });
    });
  });
})();
