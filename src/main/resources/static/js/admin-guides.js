// admin-guides.js — 관리자 가이드 등록/목록 (/jobpuzzle/admin-api/guides)
(function () {
  'use strict';

  function api(path) {
    return fetch(window.JobPuzzleRoutes.path(path.replace(/^\/admin/, '/admin-api')), { credentials: 'same-origin' }).then(function (res) {
      return res.json().then(function (body) {
        if (!res.ok || !body.success) {
          throw new Error((body && body.message) || '요청에 실패했습니다.');
        }
        return body.data;
      });
    });
  }

  var STATUS_BADGE = {
    DRAFT: { bg: '#F5F7FA', color: '#4F5764', label: '임시저장' },
    ACTIVE: { bg: '#EEF1F5', color: '#12151B', label: '사용중' },
    INACTIVE: { bg: '#F6EFE8', color: '#8A4B14', label: '비활성' }
  };
  var SCOPE_LABEL = {
    CATEGORY: '특정 직무분류',
    PARENT_CATEGORY: '상위 대분류',
    GLOBAL_COMMON: '전체 공통'
  };

  function pill(label, bg, color) {
    return '<span class="badge-pill" style="background:' + bg + '; color:' + color + ';">' + esc(label) + '</span>';
  }

  function notice(type, message) {
    if (typeof window.showToast === 'function') return window.showToast(type, message);
    alert(message);
  }

  /** 목록에서도 상세 진입 전에 현재 벡터 색인 단계와 실패 여부를 확인할 수 있게 한다. */
  function pipelineBadge(g) {
    if (g.indexingStatus === 'FAILED') {
      return '<span class="guide-list-stage guide-list-stage--error">색인 실패</span>';
    }
    if (g.indexingStatus === 'INDEXING') {
      return '<span class="guide-list-stage guide-list-stage--running">벡터 색인 중</span>';
    }
    if (g.indexingStatus === 'INDEXED') {
      return '<span class="guide-list-stage guide-list-stage--ready">색인 완료</span>';
    }
    return '<span class="guide-list-stage guide-list-stage--waiting">청크·색인 대기</span>';
  }

  var state = { guides: [], jobCategories: [] };

  function loadJobCategories() {
    return api('/admin/job-categories').then(function (categories) {
      state.jobCategories = categories;
      var select = document.getElementById('guide-job-category');
      select.innerHTML = categories.map(function (c) {
        return '<option value="' + c.jobCategoryId + '">' + esc(c.mainCategory) + ' · ' + esc(c.subCategory) + ' · ' + esc(c.careerLevel) + '</option>';
      }).join('');
    });
  }

  function loadGuides() {
    return api('/admin/guides').then(function (guides) {
      state.guides = guides;
      renderRows();
    }).catch(function (e) { notice('error', e.message); });
  }

  function renderRows() {
    var rows = state.guides.map(function (g) {
      var status = STATUS_BADGE[g.status] || { bg: '#F5F7FA', color: '#4F5764', label: g.status };
      var scope = SCOPE_LABEL[g.scopeType] || g.scopeType;
      var scopeDetail = g.scopeType === 'CATEGORY'
        ? (g.mainCategory ? ' (' + esc(g.mainCategory) + ' · ' + esc(g.subCategory) + ')' : '')
        : (g.scopeType === 'PARENT_CATEGORY' ? ' (' + esc(g.scopeMainCategory || '') + ')' : '');
      return '<div class="table-row" style="grid-template-columns:1fr 1.6fr 1fr 0.8fr 0.6fr 1fr; cursor:pointer;" data-guide="' + g.guideId + '">' +
        '<span style="font-size:12.5px; font-weight:600;">' + esc(g.guideCode) + '</span>' +
        '<span class="guide-list-title"><strong>' + esc(g.title) + '</strong>' + pipelineBadge(g) + '</span>' +
        '<span style="font-size:12px; color:#4F5764;">' + esc(scope) + scopeDetail + '</span>' +
        '<span>' + pill(status.label, status.bg, status.color) + '</span>' +
        '<span style="font-size:12.5px; color:#9BA3B0;">' + esc(g.version) + '</span>' +
        '<span style="font-size:12.5px; color:#9BA3B0;">' + (g.createdAt || '').slice(0, 10) + '</span>' +
      '</div>';
    }).join('');
    document.getElementById('guide-rows').innerHTML = rows || '<div class="table-empty">등록된 가이드가 없습니다</div>';

    document.querySelectorAll('[data-guide]').forEach(function (row) {
      row.addEventListener('click', function () {
        window.location.href = window.JobPuzzleRoutes.path('/admin/guides/' + row.dataset.guide);
      });
    });
  }

  function linesToList(id) {
    return document.getElementById(id).value
      .split('\n')
      .map(function (s) { return s.trim(); })
      .filter(function (s) { return s.length > 0; });
  }

  function onScopeTypeChange() {
    var scopeType = document.getElementById('guide-scope-type').value;
    document.getElementById('guide-scope-category-field').hidden = scopeType !== 'CATEGORY';
    document.getElementById('guide-scope-main-field').hidden = scopeType !== 'PARENT_CATEGORY';
  }

  function onSourceTypeChange() {
    var isPdf = document.getElementById('guide-source-type').value === 'PDF';
    document.getElementById('guide-file-field').hidden = !isPdf;
    document.getElementById('guide-source-text-field').hidden = isPdf;
  }

  function openForm() {
    document.getElementById('guide-code').value = '';
    document.getElementById('guide-title').value = '';
    document.getElementById('guide-scope-type').value = 'CATEGORY';
    document.getElementById('guide-scope-main').value = '';
    document.getElementById('guide-applicable-scope').value = '';
    document.getElementById('guide-evaluation-focus').value = '';
    document.getElementById('guide-evidence-rules').value = '';
    document.getElementById('guide-question-direction').value = '';
    document.getElementById('guide-avoid-questions').value = '';
    document.getElementById('guide-extra-instructions').open = false;
    document.getElementById('guide-source-type').value = 'DIRECT_INPUT';
    document.getElementById('guide-file').value = '';
    document.getElementById('guide-source-text').value = '';
    onScopeTypeChange();
    onSourceTypeChange();
    document.getElementById('guide-form').hidden = false;
  }

  function closeForm() {
    document.getElementById('guide-form').hidden = true;
  }

  function saveGuide() {
    var scopeType = document.getElementById('guide-scope-type').value;
    var request = {
      guideCode: document.getElementById('guide-code').value.trim(),
      title: document.getElementById('guide-title').value.trim(),
      scopeType: scopeType,
      jobCategoryId: scopeType === 'CATEGORY' ? parseInt(document.getElementById('guide-job-category').value, 10) : null,
      scopeMainCategory: scopeType === 'PARENT_CATEGORY' ? document.getElementById('guide-scope-main').value.trim() : null,
      applicableScope: document.getElementById('guide-applicable-scope').value.trim(),
      evaluationFocus: linesToList('guide-evaluation-focus'),
      evidenceRules: linesToList('guide-evidence-rules'),
      questionDirection: linesToList('guide-question-direction'),
      avoidQuestions: linesToList('guide-avoid-questions'),
      sourceType: document.getElementById('guide-source-type').value,
      sourceText: document.getElementById('guide-source-text').value.trim()
    };

    if (!request.guideCode || !request.title) {
      notice('error', '가이드 코드와 제목은 필수입니다.');
      return;
    }
    var fileInput = document.getElementById('guide-file');
    if (request.sourceType === 'PDF' && !fileInput.files[0]) {
      notice('error', '전처리할 PDF 파일을 선택해 주십시오.');
      return;
    }
    if (request.sourceType === 'DIRECT_INPUT' && !request.sourceText) {
      notice('error', '전처리할 가이드 원문을 입력해 주십시오.');
      return;
    }

    var formData = new FormData();
    formData.append('request', new Blob([JSON.stringify(request)], { type: 'application/json' }));
    if (request.sourceType === 'PDF' && fileInput.files[0]) {
      formData.append('file', fileInput.files[0]);
    }

    fetch(window.JobPuzzleRoutes.path('/admin-api/guides'), { method: 'POST', credentials: 'same-origin', body: formData })
      .then(function (res) {
        return res.json().then(function (body) {
          if (!res.ok || !body.success) throw new Error((body && body.message) || '등록에 실패했습니다.');
          return body.data;
        });
      })
      .then(function () {
        closeForm();
        notice('success', '새 가이드 초안을 등록했습니다.');
        loadGuides();
      })
      .catch(function (e) { notice('error', e.message); });
  }

  document.addEventListener('DOMContentLoaded', function () {
    if (!document.getElementById('guide-rows')) return;
    document.getElementById('guide-add-btn').addEventListener('click', openForm);
    document.getElementById('guide-cancel-btn').addEventListener('click', closeForm);
    document.getElementById('guide-save-btn').addEventListener('click', saveGuide);
    document.getElementById('guide-scope-type').addEventListener('change', onScopeTypeChange);
    document.getElementById('guide-source-type').addEventListener('change', onSourceTypeChange);
    loadJobCategories();
    loadGuides();
  });
})();
