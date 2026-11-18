// join.js — /join 페이지: 회원가입 폼 + 아이디 중복확인 + 이메일 인증 코드 발송/검증
(function () {
  'use strict';

  function setHint(el, message, ok) {
    el.textContent = message;
    el.classList.remove('field-hint--ok', 'field-hint--error');
    el.classList.add(ok ? 'field-hint--ok' : 'field-hint--error');
  }

  var TOAST_DURATION_MS = 2500;
  var errorToastTimer = null;

  function showError(el, message) {
    el.textContent = message;
    el.classList.add('is-visible');
    if (errorToastTimer) {
      clearTimeout(errorToastTimer);
    }
    errorToastTimer = setTimeout(function () {
      hideError(el);
    }, TOAST_DURATION_MS);
  }

  function hideError(el) {
    el.classList.remove('is-visible');
  }

  var CAREER_LEVEL_LABEL = {
    NEW: '신입',
    EXPERIENCED: '경력',
    ANY: '경력무관'
  };

  var CODE_VALID_SECONDS = 180;

  function formatMMSS(totalSeconds) {
    var m = Math.floor(totalSeconds / 60);
    var s = totalSeconds % 60;
    return m + ':' + (s < 10 ? '0' + s : s);
  }

  document.addEventListener('DOMContentLoaded', function () {
    var form = document.getElementById('join-form');
    var errorEl = document.getElementById('join-error');

    var loginIdInput = document.getElementById('loginId');
    var loginIdHint = document.getElementById('loginId-hint');
    var emailInput = document.getElementById('email');
    var emailHint = document.getElementById('email-hint');
    var nameInput = document.getElementById('name');
    var nameHint = document.getElementById('name-hint');
    var passwordInput = document.getElementById('password');
    var passwordHint = document.getElementById('password-hint');
    var passwordCheckInput = document.getElementById('passwordCheck');
    var passwordCheckHint = document.getElementById('passwordCheck-hint');

    var EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
    var PASSWORD_PATTERN = /^(?=.*[A-Za-z])(?=.*\d).{8,20}$/;

    var mainCategorySelect = document.getElementById('mainCategory');
    var subCategorySelect = document.getElementById('subCategory');
    var careerLevelSelect = document.getElementById('careerLevel');
    var jobCategoryHint = document.getElementById('jobCategory-hint');

    var jobCategories = [];
    var selectedJobCategoryId = null;

    function uniqueInOrder(values) {
      var seen = {};
      var result = [];
      values.forEach(function (value) {
        if (!seen[value]) {
          seen[value] = true;
          result.push(value);
        }
      });
      return result;
    }

    function fillOptions(select, options, placeholder) {
      select.innerHTML = '';
      var placeholderOption = document.createElement('option');
      placeholderOption.value = '';
      placeholderOption.textContent = placeholder;
      select.appendChild(placeholderOption);
      options.forEach(function (option) {
        var el = document.createElement('option');
        el.value = option.value;
        el.textContent = option.label;
        select.appendChild(el);
      });
    }

    fetch(window.JobPuzzleRoutes.path('/job-category'))
      .then(function (res) { return res.json(); })
      .then(function (body) {
        jobCategories = body.data || [];
        var mainCategories = uniqueInOrder(jobCategories.map(function (c) { return c.mainCategory; }));
        fillOptions(mainCategorySelect, mainCategories.map(function (m) {
          return { value: m, label: m };
        }), '선택해 주십시오');
      })
      .catch(function () {
        jobCategoryHint.textContent = '직무 목록을 불러오지 못했습니다. 새로고침 후 다시 시도해 주십시오.';
        jobCategoryHint.classList.add('field-hint--error');
      });

    mainCategorySelect.addEventListener('change', function () {
      selectedJobCategoryId = null;
      var mainCategory = mainCategorySelect.value;

      if (!mainCategory) {
        subCategorySelect.disabled = true;
        careerLevelSelect.disabled = true;
        fillOptions(subCategorySelect, [], '대분류를 먼저 선택해 주십시오');
        fillOptions(careerLevelSelect, [], '중분류를 먼저 선택해 주십시오');
        return;
      }

      var subCategories = uniqueInOrder(
        jobCategories.filter(function (c) { return c.mainCategory === mainCategory; })
          .map(function (c) { return c.subCategory; })
      );
      fillOptions(subCategorySelect, subCategories.map(function (s) {
        return { value: s, label: s };
      }), '선택해 주십시오');
      subCategorySelect.disabled = false;

      careerLevelSelect.disabled = true;
      fillOptions(careerLevelSelect, [], '중분류를 먼저 선택해 주십시오');
    });

    subCategorySelect.addEventListener('change', function () {
      selectedJobCategoryId = null;
      var mainCategory = mainCategorySelect.value;
      var subCategory = subCategorySelect.value;

      if (!subCategory) {
        careerLevelSelect.disabled = true;
        fillOptions(careerLevelSelect, [], '중분류를 먼저 선택해 주십시오');
        return;
      }

      var matches = jobCategories.filter(function (c) {
        return c.mainCategory === mainCategory && c.subCategory === subCategory;
      });
      fillOptions(careerLevelSelect, matches.map(function (c) {
        return { value: c.jobCategoryId, label: CAREER_LEVEL_LABEL[c.careerLevel] || c.careerLevel };
      }), '선택해 주십시오');
      careerLevelSelect.disabled = false;
    });

    careerLevelSelect.addEventListener('change', function () {
      selectedJobCategoryId = careerLevelSelect.value || null;
    });

    // 아이디를 바꾸면 중복확인 결과는 다시 확인해야 하므로 초기화
    var loginIdChecked = false;
    loginIdInput.addEventListener('input', function () {
      loginIdChecked = false;
      loginIdHint.textContent = '';
    });

    var emailCodeInput = document.getElementById('emailCode');
    var emailCodeStep = document.getElementById('email-code-step');
    var emailCodeHint = document.getElementById('email-code-hint');
    var emailCodeCountdownEl = document.getElementById('email-code-countdown');
    var sendEmailCodeBtn = document.getElementById('send-email-code-btn');
    var verifyEmailCodeBtn = document.getElementById('verify-email-code-btn');

    var emailVerified = false;
    var emailCodeTimerId = null;

    function stopEmailCodeCountdown() {
      if (emailCodeTimerId) {
        clearInterval(emailCodeTimerId);
        emailCodeTimerId = null;
      }
    }

    function startEmailCodeCountdown() {
      stopEmailCodeCountdown();
      var remaining = CODE_VALID_SECONDS;
      emailCodeCountdownEl.textContent = formatMMSS(remaining);
      emailCodeHint.classList.remove('field-hint--error', 'field-hint--ok');
      verifyEmailCodeBtn.disabled = false;

      emailCodeTimerId = setInterval(function () {
        remaining -= 1;
        if (remaining <= 0) {
          stopEmailCodeCountdown();
          setHint(emailCodeHint, '인증 시간이 만료되었습니다. 인증코드를 다시 발송해 주십시오.', false);
          verifyEmailCodeBtn.disabled = true;
          return;
        }
        emailCodeCountdownEl.textContent = formatMMSS(remaining);
      }, 1000);
    }

    // 이메일을 바꾸면 이전 인증 결과는 무효이므로 초기화
    emailInput.addEventListener('input', function () {
      emailVerified = false;
      emailHint.textContent = '';
      stopEmailCodeCountdown();
      emailCodeStep.hidden = true;
      emailCodeInput.value = '';
      emailCodeInput.disabled = false;
    });

    sendEmailCodeBtn.addEventListener('click', function () {
      var email = emailInput.value.trim();
      if (!email) {
        setHint(emailHint, '이메일을 입력해 주십시오.', false);
        return;
      }
      if (!EMAIL_PATTERN.test(email)) {
        setHint(emailHint, '올바른 이메일 형식이 아닙니다.', false);
        return;
      }

      emailVerified = false;
      emailCodeInput.value = '';
      emailCodeInput.disabled = false;
      sendEmailCodeBtn.disabled = true;
      var sendBtnOriginalText = sendEmailCodeBtn.textContent;
      sendEmailCodeBtn.textContent = '전송 중...';
      fetch(window.JobPuzzleRoutes.path('/user/join/send-code'), {
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
            setHint(emailHint, '인증 코드를 보냈습니다. 이메일을 확인해 주십시오.', true);
            emailCodeStep.hidden = false;
            startEmailCodeCountdown();
          } else {
            setHint(emailHint, result.body.message || '인증 코드 발송에 실패했습니다.', false);
          }
        })
        .catch(function () {
          setHint(emailHint, '일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주십시오.', false);
        })
        .then(function () {
          sendEmailCodeBtn.disabled = false;
          sendEmailCodeBtn.textContent = sendBtnOriginalText;
        });
    });

    verifyEmailCodeBtn.addEventListener('click', function () {
      var email = emailInput.value.trim();
      var code = emailCodeInput.value.trim();
      if (!code) {
        emailCodeHint.textContent = '인증 코드를 입력해 주십시오.';
        emailCodeHint.classList.add('field-hint--error');
        return;
      }

      verifyEmailCodeBtn.disabled = true;
      fetch(window.JobPuzzleRoutes.path('/user/join/verify'), {
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
            emailVerified = true;
            stopEmailCodeCountdown();
            setHint(emailCodeHint, '이메일 인증이 완료되었습니다.', true);
            emailCodeInput.disabled = true;
          } else {
            emailVerified = false;
            setHint(emailCodeHint, result.body.message || '인증 코드가 올바르지 않습니다.', false);
            verifyEmailCodeBtn.disabled = false;
          }
        })
        .catch(function () {
          emailVerified = false;
          setHint(emailCodeHint, '일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주십시오.', false);
          verifyEmailCodeBtn.disabled = false;
        });
    });

    // 아이디/이메일 중복확인은 버튼 클릭으로만 하고, 그 외 형식 검증은
    // 입력칸 밖을 클릭(focusout)할 때와 제출 시 둘 다 씀
    function validateLoginId() {
      var value = loginIdInput.value.trim();
      if (!value) {
        setHint(loginIdHint, '아이디를 입력해 주십시오.', false);
        return false;
      }
      if (value.length < 4 || value.length > 50) {
        setHint(loginIdHint, '아이디는 4~50자로 입력해 주십시오.', false);
        return false;
      }
      if (!loginIdChecked) {
        setHint(loginIdHint, '아이디 중복확인을 먼저 진행해 주십시오.', false);
        return false;
      }
      return true;
    }

    function validateEmail() {
      var value = emailInput.value.trim();
      if (!value) {
        setHint(emailHint, '이메일을 입력해 주십시오.', false);
        return false;
      }
      if (!EMAIL_PATTERN.test(value)) {
        setHint(emailHint, '올바른 이메일 형식이 아닙니다.', false);
        return false;
      }
      if (!emailVerified) {
        setHint(emailHint, '이메일 인증을 먼저 완료해 주십시오.', false);
        return false;
      }
      return true;
    }

    function validateName() {
      var value = nameInput.value.trim();
      if (!value) {
        setHint(nameHint, '이름을 입력해 주십시오.', false);
        return false;
      }
      nameHint.textContent = '';
      return true;
    }

    function validatePassword() {
      var value = passwordInput.value;
      if (!value) {
        setHint(passwordHint, '비밀번호를 입력해 주십시오.', false);
        return false;
      }
      if (!PASSWORD_PATTERN.test(value)) {
        setHint(passwordHint, '비밀번호는 영문, 숫자를 포함해 8~20자로 입력해 주십시오.', false);
        return false;
      }
      setHint(passwordHint, '사용 가능한 비밀번호입니다.', true);

      if (passwordCheckInput.value) {
        setHint(passwordCheckHint,
          passwordInput.value === passwordCheckInput.value ? '비밀번호가 일치합니다.' : '비밀번호가 일치하지 않습니다.',
          passwordInput.value === passwordCheckInput.value);
      }
      return true;
    }

    function validatePasswordCheck() {
      var value = passwordCheckInput.value;
      if (!value) {
        setHint(passwordCheckHint, '비밀번호 확인을 입력해 주십시오.', false);
        return false;
      }
      var match = value === passwordInput.value;
      setHint(passwordCheckHint, match ? '비밀번호가 일치합니다.' : '비밀번호가 일치하지 않습니다.', match);
      return match;
    }

    function validateJobCategory() {
      if (!selectedJobCategoryId) {
        setHint(jobCategoryHint, '관심 직무를 선택해 주십시오.', false);
        return false;
      }
      jobCategoryHint.textContent = '';
      return true;
    }

    loginIdInput.addEventListener('focusout', validateLoginId);
    emailInput.addEventListener('focusout', validateEmail);
    nameInput.addEventListener('focusout', validateName);
    passwordInput.addEventListener('focusout', validatePassword);
    passwordCheckInput.addEventListener('focusout', validatePasswordCheck);
    careerLevelSelect.addEventListener('focusout', validateJobCategory);

    document.getElementById('check-id-btn').addEventListener('click', function () {
      var loginId = loginIdInput.value.trim();
      if (!loginId) {
        setHint(loginIdHint, '아이디를 입력해 주십시오.', false);
        return;
      }
      if (loginId.length < 4 || loginId.length > 50) {
        setHint(loginIdHint, '아이디는 4~50자로 입력해 주십시오.', false);
        return;
      }
      fetch(window.JobPuzzleRoutes.path('/user/check-id?loginId=' + encodeURIComponent(loginId)))
        .then(function (res) { return res.json(); })
        .then(function (body) {
          var duplicate = body.data;
          loginIdChecked = !duplicate;
          setHint(loginIdHint, duplicate ? '이미 사용 중인 아이디입니다.' : '사용 가능한 아이디입니다.', !duplicate);
        })
        .catch(function () {
          setHint(loginIdHint, '중복확인 중 오류가 발생했습니다.', false);
        });
    });

    form.addEventListener('submit', function (e) {
      e.preventDefault();
      hideError(errorEl);

      // 위에서부터 순서대로 검증해서, 맨 처음 걸린 항목으로 스크롤/포커스 이동
      var fields = [
        { valid: validateLoginId(), el: loginIdInput, hint: loginIdHint },
        { valid: validateEmail(), el: emailInput, hint: emailHint },
        { valid: validateName(), el: nameInput, hint: nameHint },
        { valid: validatePassword(), el: passwordInput, hint: passwordHint },
        { valid: validatePasswordCheck(), el: passwordCheckInput, hint: passwordCheckHint },
        { valid: validateJobCategory(), el: careerLevelSelect, hint: jobCategoryHint }
      ];
      var firstInvalid = fields.find(function (field) { return !field.valid; });

      if (firstInvalid) {
        firstInvalid.el.scrollIntoView({ behavior: 'smooth', block: 'center' });
        firstInvalid.el.focus();
        showError(errorEl, firstInvalid.hint.textContent || '입력한 내용을 다시 확인해 주십시오.');
        return;
      }

      var payload = {
        loginId: loginIdInput.value.trim(),
        password: passwordInput.value,
        email: emailInput.value.trim(),
        name: document.getElementById('name').value.trim(),
        defaultJobCategoryId: Number(selectedJobCategoryId),
        code: Number(emailCodeInput.value.trim())
      };

      fetch(window.JobPuzzleRoutes.path('/user/join'), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'same-origin',
        body: JSON.stringify(payload)
      })
        .then(function (res) {
          return res.json().then(function (body) { return { ok: res.ok, body: body }; });
        })
        .then(function (result) {
          if (result.ok && result.body.success) {
            // 가입 성공 - 로그인 페이지로 이동
            window.location.href = window.JobPuzzleRoutes.path('/login');
          } else {
            showError(errorEl, result.body.message || '회원가입에 실패했습니다.');
          }
        })
        .catch(function () {
          showError(errorEl, '일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주십시오.');
        });
    });
  });
})();
