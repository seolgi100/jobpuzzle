// job-category-setup.js — /job-category-setup 화면: 소셜 로그인 최초 진입 시 희망 직무 선택
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
    var form = document.getElementById('job-category-form');
    var errorEl = document.getElementById('job-category-error');

    var mainCategorySelect = document.getElementById('mainCategory');
    var subCategorySelect = document.getElementById('subCategory');
    var careerLevelSelect = document.getElementById('careerLevel');
    var jobCategoryHint = document.getElementById('jobCategory-hint');

    var jobCategories = [];
    var selectedJobCategoryId = null;
    var currentUser = null;

    // 저장 시 이름/이메일도 같이 보내야 해서(내 정보 수정 API는 항상 전체를 덮어씀) 로그인된 내 정보를 먼저 가져옴
    fetch(window.JobPuzzleRoutes.path('/user/me'), { credentials: 'same-origin' })
      .then(function (res) { return res.json(); })
      .then(function (body) {
        if (!body.success) {
          window.location.href = window.JobPuzzleRoutes.path('/login');
          return;
        }
        currentUser = body.data;
      })
      .catch(function () {
        window.location.href = window.JobPuzzleRoutes.path('/login');
      });

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

    form.addEventListener('submit', function (e) {
      e.preventDefault();
      hideError(errorEl);

      if (!selectedJobCategoryId) {
        showError(errorEl, '관심 직무를 선택해 주십시오.');
        return;
      }
      if (!currentUser) {
        showError(errorEl, '내 정보를 불러오는 중입니다. 잠시 후 다시 시도해 주십시오.');
        return;
      }

      var payload = {
        name: currentUser.name,
        email: currentUser.email,
        defaultJobCategoryId: Number(selectedJobCategoryId)
      };

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
            window.location.href = window.JobPuzzleRoutes.path('/');
          } else {
            showError(errorEl, result.body.message || '저장에 실패했습니다.');
          }
        })
        .catch(function () {
          showError(errorEl, '일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주십시오.');
        });
    });
  });
})();
