// settings.js — /settings 화면: 프로필/희망 직무를 실제 API와 연동

(function () {
  'use strict';

  var CAREER_LEVEL_LABEL = {
    NEW: '신입',
    EXPERIENCED: '경력',
    ANY: '경력무관'
  };

  function showError(el, message) {
    el.textContent = message;
    el.classList.add('is-visible');
  }

  function hideError(el) {
    el.classList.remove('is-visible');
  }

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

  document.addEventListener('DOMContentLoaded', function () {
    var idInput = document.getElementById('settings-id');
    var emailInput = document.getElementById('settings-email');
    var nameInput = document.getElementById('settings-name');
    var profileError = document.getElementById('profile-error');
    var editProfileBtn = document.getElementById('edit-profile-btn');
    var saveProfileBtn = document.getElementById('save-profile-btn');
    var saveSuccessModal = document.getElementById('save-success-modal');

    // 아이디/이메일은 수정 대상이 아니라 항상 읽기 전용 - 이름만 편집 가능
    function setProfileEditMode(editing) {
      nameInput.disabled = !editing;
      editProfileBtn.hidden = editing;
      saveProfileBtn.hidden = !editing;
    }

    var majorSelect = document.getElementById('job-major');
    var minorSelect = document.getElementById('job-minor');
    var careerSelect = document.getElementById('job-career');
    var jobCategoryError = document.getElementById('job-category-error');
    var saveJobCategoryBtn = document.getElementById('save-job-category-btn');

    var jobCategories = [];
    var currentUser = null;
    var selectedJobCategoryId = null;

    function renderMinorOptions(mainCategory, selectedSubCategory) {
      var subCategories = uniqueInOrder(
        jobCategories.filter(function (c) { return c.mainCategory === mainCategory; })
          .map(function (c) { return c.subCategory; })
      );
      fillOptions(minorSelect, subCategories.map(function (s) {
        return { value: s, label: s };
      }), '선택해주세요');
      minorSelect.disabled = subCategories.length === 0;
      if (selectedSubCategory) {
        minorSelect.value = selectedSubCategory;
      }
    }

    function renderCareerOptions(mainCategory, subCategory, selectedCareerJobCategoryId) {
      var matches = jobCategories.filter(function (c) {
        return c.mainCategory === mainCategory && c.subCategory === subCategory;
      });
      fillOptions(careerSelect, matches.map(function (c) {
        return { value: c.jobCategoryId, label: CAREER_LEVEL_LABEL[c.careerLevel] || c.careerLevel };
      }), '선택해주세요');
      careerSelect.disabled = matches.length === 0;
      if (selectedCareerJobCategoryId) {
        careerSelect.value = String(selectedCareerJobCategoryId);
        selectedJobCategoryId = careerSelect.value || null;
      }
    }

    // 이미 저장돼 있는 희망 직무를 드롭다운에 그대로 반영 (대분류/중분류/경력수준까지 역추적)
    function applyCurrentJobCategory() {
      if (!currentUser || !currentUser.defaultJobCategoryId || jobCategories.length === 0) return;

      var current = jobCategories.find(function (c) {
        return c.jobCategoryId === currentUser.defaultJobCategoryId;
      });
      if (!current) return;

      majorSelect.value = current.mainCategory;
      renderMinorOptions(current.mainCategory, current.subCategory);
      renderCareerOptions(current.mainCategory, current.subCategory, current.jobCategoryId);
    }

    function fillProfile(user) {
      idInput.value = user.loginId || '';
      emailInput.value = user.email || '';
      nameInput.value = user.name || '';

      // 소셜 로그인 계정은 아이디를 직접 로그인에 쓰지 않으므로 아이디 항목을 숨김
      var idRow = document.getElementById('settings-id-row');
      idRow.style.display = user.socialProvider ? 'none' : '';
      var provider = { KAKAO: '카카오', GOOGLE: '구글' }[String(user.socialProvider || '').toUpperCase()];
      document.getElementById('settings-login-method').textContent = user.socialProvider ? (provider || user.socialProvider) + ' 계정' : '아이디·비밀번호';
      document.getElementById('settings-passwd-link').hidden = !!user.socialProvider;
    }

    // header.js가 헤더 표시를 위해 이미 /jobpuzzle/user/me를 불러오므로, 중복 호출 없이 그 결과를 그대로 재사용함
    document.addEventListener('app-user-loaded', function (event) {
      currentUser = event.detail;
      fillProfile(currentUser);
      applyCurrentJobCategory();
    });

    fetch(window.JobPuzzleRoutes.path('/job-category'))
      .then(function (res) { return res.json(); })
      .then(function (body) {
        jobCategories = body.data || [];
        var mainCategories = uniqueInOrder(jobCategories.map(function (c) { return c.mainCategory; }));
        fillOptions(majorSelect, mainCategories.map(function (m) {
          return { value: m, label: m };
        }), '선택해주세요');
        applyCurrentJobCategory();
      })
      .catch(function () {
        showError(jobCategoryError, '직무 목록을 불러오지 못했습니다. 새로고침 후 다시 시도해주세요.');
      });

    majorSelect.addEventListener('change', function () {
      selectedJobCategoryId = null;
      var mainCategory = majorSelect.value;

      if (!mainCategory) {
        minorSelect.disabled = true;
        careerSelect.disabled = true;
        fillOptions(minorSelect, [], '대분류를 먼저 선택해주세요');
        fillOptions(careerSelect, [], '중분류를 먼저 선택해주세요');
        return;
      }

      renderMinorOptions(mainCategory);
      careerSelect.disabled = true;
      fillOptions(careerSelect, [], '중분류를 먼저 선택해주세요');
    });

    minorSelect.addEventListener('change', function () {
      selectedJobCategoryId = null;
      renderCareerOptions(majorSelect.value, minorSelect.value);
    });

    careerSelect.addEventListener('change', function () {
      selectedJobCategoryId = careerSelect.value || null;
    });

    function saveMyInfo(payload, errorEl, onSuccess) {
      fetch(window.JobPuzzleRoutes.path('/user/me'), {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'same-origin',
        body: JSON.stringify(payload)
      })
        .then(function (res) {
          return res.json().then(function (body) { return { ok: res.ok, body: body }; });
        })
        .then(function (result) {
          if (result.ok && result.body.success) {
            onSuccess();
          } else {
            showError(errorEl, result.body.message || '저장에 실패했습니다.');
          }
        })
        .catch(function () {
          showError(errorEl, '일시적인 오류가 발생했습니다. 잠시 후 다시 시도해주세요.');
        });
    }

    editProfileBtn.addEventListener('click', function () {
      hideError(profileError);
      setProfileEditMode(true);
    });

    saveProfileBtn.addEventListener('click', function () {
      hideError(profileError);
      if (!currentUser) return;

      var name = nameInput.value.trim();
      if (!name) {
        showError(profileError, '이름을 입력해주세요.');
        return;
      }

      saveMyInfo({
        name: name,
        email: currentUser.email,
        defaultJobCategoryId: currentUser.defaultJobCategoryId
      }, profileError, function () {
        currentUser.name = name;
        setProfileEditMode(false);
        showSavedToast();
        document.dispatchEvent(new CustomEvent('app-user-updated', { detail: currentUser }));
      });
    });

    var saveToastTimer = 0;
    function showSavedToast() {
      saveSuccessModal.hidden = false;
      clearTimeout(saveToastTimer);
      saveToastTimer = setTimeout(function () { saveSuccessModal.hidden = true; }, 2000);
    }

    saveJobCategoryBtn.addEventListener('click', function () {
      hideError(jobCategoryError);
      if (!currentUser) return;

      if (!selectedJobCategoryId) {
        showError(jobCategoryError, '희망 직무를 선택해주세요.');
        return;
      }

      saveMyInfo({
        name: currentUser.name,
        email: currentUser.email,
        defaultJobCategoryId: Number(selectedJobCategoryId)
      }, jobCategoryError, function () {
        currentUser.defaultJobCategoryId = Number(selectedJobCategoryId);
        showSavedToast();
      });
    });

    var withdrawBtn = document.getElementById('withdraw-btn');
    var withdrawConfirmModal = document.getElementById('withdraw-confirm-modal');
    var withdrawDoneModal = document.getElementById('withdraw-done-modal');
    var withdrawError = document.getElementById('withdraw-error');
    var withdrawCancelBtn = document.getElementById('withdraw-cancel-btn');
    var withdrawConfirmBtn = document.getElementById('withdraw-confirm-btn');
    var withdrawDoneConfirm = document.getElementById('withdraw-done-confirm');

    var withdrawAgree = document.getElementById('withdraw-agree');
    withdrawAgree.addEventListener('change', function () { withdrawConfirmBtn.disabled = !withdrawAgree.checked; });

    withdrawBtn.addEventListener('click', function () {
      hideError(withdrawError);
      withdrawAgree.checked = false;
      withdrawConfirmBtn.disabled = true;
      withdrawConfirmModal.hidden = false;
    });

    withdrawCancelBtn.addEventListener('click', function () {
      withdrawConfirmModal.hidden = true;
    });

    withdrawConfirmBtn.addEventListener('click', function () {
      hideError(withdrawError);

      fetch(window.JobPuzzleRoutes.path('/user/me'), {
        method: 'DELETE',
        credentials: 'same-origin'
      })
        .then(function (res) {
          return res.json().then(function (body) { return { ok: res.ok, body: body }; });
        })
        .then(function (result) {
          if (result.ok && result.body.success) {
            withdrawConfirmModal.hidden = true;
            withdrawDoneModal.hidden = false;
          } else {
            showError(withdrawError, result.body.message || '탈퇴에 실패했습니다.');
          }
        })
        .catch(function () {
          showError(withdrawError, '일시적인 오류가 발생했습니다. 잠시 후 다시 시도해주세요.');
        });
    });

    withdrawDoneConfirm.addEventListener('click', function () {
      window.location.href = window.JobPuzzleRoutes.path('/');
    });
  });
})();
