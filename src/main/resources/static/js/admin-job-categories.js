// admin-job-categories.js — 관리자 직무 분류 관리 (/jobpuzzle/admin-api/job-categories)
(function () {
  'use strict';

  var CAREER_LABEL = { NEW: '신입', EXPERIENCED: '경력', ANY: '무관' };

  function api(path, opts) {
    opts = opts || {};
    var headers = opts.headers || {};
    var fetchOpts = { method: opts.method || 'GET', credentials: 'same-origin', headers: headers };
    if (opts.json !== undefined) {
      headers['Content-Type'] = 'application/json; charset=UTF-8';
      fetchOpts.body = JSON.stringify(opts.json);
    }
    return fetch(window.JobPuzzleRoutes.path(path.replace(/^\/admin/, '/admin-api')), fetchOpts).then(function (res) {
      return res.json().then(function (body) {
        if (!res.ok || !body.success) {
          throw new Error((body && body.message) || '요청에 실패했습니다.');
        }
        return body.data;
      });
    });
  }

  var state = {
    categories: [],
    editingId: null
  };

  function loadCategories() {
    return api('/admin/job-categories').then(function (categories) {
      state.categories = categories;
      renderRows();
      renderMainDatalist();
      renderSubDatalist();
    }).catch(function (e) { alert(e.message); });
  }

  function uniqueValues(values) {
    return values.filter(function (v, i) { return v && values.indexOf(v) === i; });
  }

  // 대분류 datalist는 등록된 전체 대분류 중복 없이 보여줌
  function renderMainDatalist() {
    var options = uniqueValues(state.categories.map(function (c) { return c.mainCategory; }));
    document.getElementById('job-category-main-list').innerHTML =
      options.map(function (v) { return '<option value="' + esc(v) + '"></option>'; }).join('');
  }

  // 중분류 datalist는 현재 입력된 대분류에 속한 것만 보여줌 (대분류 미입력이면 전체)
  function renderSubDatalist() {
    var mainValue = document.getElementById('job-category-main').value.trim();
    var scoped = mainValue
      ? state.categories.filter(function (c) { return c.mainCategory === mainValue; })
      : state.categories;
    var options = uniqueValues(scoped.map(function (c) { return c.subCategory; }));
    document.getElementById('job-category-sub-list').innerHTML =
      options.map(function (v) { return '<option value="' + esc(v) + '"></option>'; }).join('');
  }

  // 기존에 등록된 중분류를 그대로 선택하면 그 중분류가 속한 대분류를 자동으로 채워줌
  function fillMainFromSub() {
    var subInput = document.getElementById('job-category-sub');
    var mainInput = document.getElementById('job-category-main');
    var subValue = subInput.value.trim();
    var matched = state.categories.find(function (c) { return c.subCategory === subValue; });
    if (matched && mainInput.value.trim() !== matched.mainCategory) {
      mainInput.value = matched.mainCategory;
      renderSubDatalist();
    }
  }

  function renderRows() {
    var rows = state.categories.map(function (c) {
      return '<div class="table-row" style="grid-template-columns:1.2fr 1.2fr 0.8fr 1fr;">' +
        '<span style="font-size:13px;">' + esc(c.mainCategory) + '</span>' +
        '<span style="font-size:13px;">' + esc(c.subCategory) + '</span>' +
        '<span class="badge">' + (CAREER_LABEL[c.careerLevel] || esc(c.careerLevel)) + '</span>' +
        '<span style="display:flex; gap:6px;">' +
          '<button class="btn-sm" data-edit="' + c.jobCategoryId + '">수정</button>' +
          '<button class="btn-sm btn-sm--danger" data-del="' + c.jobCategoryId + '">삭제</button>' +
        '</span>' +
      '</div>';
    }).join('');
    document.getElementById('job-category-rows').innerHTML = rows || '<div class="table-empty">등록된 직무 분류가 없습니다</div>';

    document.querySelectorAll('[data-edit]').forEach(function (b) {
      b.addEventListener('click', function () { openForm(parseInt(b.dataset.edit, 10)); });
    });
    document.querySelectorAll('[data-del]').forEach(function (b) {
      b.addEventListener('click', function () { removeCategory(parseInt(b.dataset.del, 10)); });
    });
  }

  function openForm(editingId) {
    var form = document.getElementById('job-category-form');
    var title = document.getElementById('job-category-form-title');
    var mainInput = document.getElementById('job-category-main');
    var subInput = document.getElementById('job-category-sub');
    var careerSelect = document.getElementById('job-category-career');

    state.editingId = editingId || null;

    if (state.editingId) {
      var category = state.categories.find(function (c) { return c.jobCategoryId === state.editingId; });
      title.textContent = '직무 분류 수정';
      mainInput.value = category ? category.mainCategory : '';
      subInput.value = category ? category.subCategory : '';
      careerSelect.value = category ? category.careerLevel : 'NEW';
    } else {
      title.textContent = '새 직무 분류 추가';
      mainInput.value = '';
      subInput.value = '';
      careerSelect.value = 'NEW';
    }

    renderSubDatalist();
    form.hidden = false;
    mainInput.focus();
  }

  function closeForm() {
    document.getElementById('job-category-form').hidden = true;
    state.editingId = null;
  }

  function saveCategory() {
    var request = {
      mainCategory: document.getElementById('job-category-main').value.trim(),
      subCategory: document.getElementById('job-category-sub').value.trim(),
      careerLevel: document.getElementById('job-category-career').value
    };
    if (!request.mainCategory || !request.subCategory) {
      alert('대분류와 중분류를 입력해 주십시오.');
      return;
    }

    var call = state.editingId
      ? api('/admin/job-categories/' + state.editingId, { method: 'PUT', json: request })
      : api('/admin/job-categories', { method: 'POST', json: request });

    call.then(function () {
      closeForm();
      loadCategories();
    }).catch(function (e) { alert(e.message); });
  }

  function removeCategory(jobCategoryId) {
    if (!confirm('이 직무 분류를 삭제하시겠습니까?')) return;
    api('/admin/job-categories/' + jobCategoryId, { method: 'DELETE' })
      .then(loadCategories)
      .catch(function (e) { alert(e.message); });
  }

  document.addEventListener('DOMContentLoaded', function () {
    if (!document.getElementById('job-category-rows')) return;
    document.getElementById('job-category-add-btn').addEventListener('click', function () { openForm(null); });
    document.getElementById('job-category-save-btn').addEventListener('click', saveCategory);
    document.getElementById('job-category-cancel-btn').addEventListener('click', closeForm);
    document.getElementById('job-category-main').addEventListener('input', renderSubDatalist);
    document.getElementById('job-category-sub').addEventListener('input', fillMainFromSub);
    loadCategories();
  });
})();
