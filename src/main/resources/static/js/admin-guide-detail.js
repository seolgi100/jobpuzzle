// admin-guide-detail.js — 관리자 가이드 상세 페이지 (/admin/guides/{id})
(function () {
  'use strict';

  function api(path, opts) {
    opts = opts || {};
    return fetch(window.JobPuzzleRoutes.path(path.replace(/^\/admin/, '/admin-api')), {
      method: opts.method || 'GET',
      credentials: 'same-origin',
      headers: opts.headers || {},
      body: opts.body
    }).then(function (res) {
      return res.json().then(function (body) {
        if (!res.ok || !body.success) {
          var err = new Error((body && body.message) || '요청에 실패했습니다.');
          err.code = body && body.code;
          throw err;
        }
        return body.data;
      });
    });
  }

  // 전처리·검수·색인은 기존 관리자 CRUD와 분리된 안전한 파이프라인 API를 사용한다.
  function guideApi(path, opts) {
    opts = opts || {};
    return fetch(window.JobPuzzleRoutes.path('/guide-admin' + path), {
      method: opts.method || 'GET',
      credentials: 'same-origin',
      headers: opts.headers || {},
      body: opts.body
    }).then(function (res) {
      return res.json().then(function (body) {
        if (!res.ok || !body.success) {
          var err = new Error((body && body.message) || '요청에 실패했습니다.');
          err.code = body && body.code;
          throw err;
        }
        return body.data;
      });
    });
  }

  function notice(type, message) {
    if (typeof window.showToast === 'function') return window.showToast(type, message);
    alert(message);
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

  function detailField(label, value) {
    return '<div><p class="field-label" style="margin-bottom:4px;">' + esc(label) + '</p>' +
      '<p style="font-size:13.5px; margin:0; white-space:pre-line;">' + (value || '<span style="color:#9BA3B0;">-</span>') + '</p></div>';
  }

  function detailListField(label, items) {
    var content = (items && items.length)
      ? '<ul style="margin:0; padding-left:18px;">' + items.map(function (i) { return '<li style="font-size:13.5px;">' + esc(i) + '</li>'; }).join('') + '</ul>'
      : '<p style="font-size:13.5px; margin:0; color:#9BA3B0;">-</p>';
    return '<div><p class="field-label" style="margin-bottom:4px;">' + esc(label) + '</p>' + content + '</div>';
  }

  function guideIdFromUrl() {
    var parts = window.location.pathname.split('/').filter(Boolean);
    return parseInt(parts[parts.length - 1], 10);
  }

  var guideId = guideIdFromUrl();
  var jobCategories = [];
  var state = { currentGuide: null, pollTimer: null, disposed: false };

  function loadJobCategories() {
    return api('/admin/job-categories').then(function (categories) {
      jobCategories = categories;
      document.getElementById('gv-job-category').innerHTML = categories.map(function (c) {
        return '<option value="' + c.jobCategoryId + '">' + esc(c.mainCategory) + ' · ' + esc(c.subCategory) + ' · ' + esc(c.careerLevel) + '</option>';
      }).join('');
    });
  }

  function loadAndRender() {
    window.clearTimeout(state.pollTimer);
    return api('/admin/guides/' + guideId).then(function (guide) {
      state.currentGuide = guide;
      render(guide);
      schedulePipelinePoll(guide);
      return loadChunks().catch(function (e) {
        document.getElementById('guide-chunk-review').hidden = true;
        notice('error', e.message);
      });
    }).catch(function (e) {
      document.getElementById('guide-detail-title').textContent = '가이드를 불러올 수 없습니다';
      document.getElementById('guide-detail-body').innerHTML = '<p style="color:#8A4B14;">' + esc(e.message) + '</p>';
      document.getElementById('guide-detail-actions').innerHTML = '';
    });
  }

  function render(g) {
    var status = STATUS_BADGE[g.status] || { bg: '#F5F7FA', color: '#4F5764', label: g.status };
    var scope = SCOPE_LABEL[g.scopeType] || g.scopeType;
    var scopeDetail = g.scopeType === 'CATEGORY'
      ? (g.mainCategory ? ' (' + esc(g.mainCategory) + ' · ' + esc(g.subCategory) + ' · ' + esc(g.careerLevel || '') + ')' : '')
      : (g.scopeType === 'PARENT_CATEGORY' ? ' (' + esc(g.scopeMainCategory || '') + ')' : '');

    document.getElementById('guide-detail-title').innerHTML =
      esc(g.title) + ' ' + pill(status.label, status.bg, status.color);

    document.getElementById('guide-detail-body').innerHTML =
      detailField('가이드 코드 / 버전', esc(g.guideCode) + ' / ' + esc(g.version)) +
      detailField('적용 범위', esc(scope) + scopeDetail) +
      detailField('적용 범위 설명', esc(g.applicableScope)) +
      detailListField('평가 중점', g.evaluationFocus) +
      detailListField('근거 판단 기준', g.evidenceRules) +
      detailListField('질문 방향', g.questionDirection) +
      detailListField('피해야 할 질문', g.avoidQuestions) +
      detailField('자료 유형', esc(g.sourceType) + (g.hasFile ? ' (첨부파일 있음)' : '')) +
      detailField('청크', g.chunkCount + '개') +
      detailField('등록자 / 등록일', esc(g.createdByLoginId) + ' / ' + (g.createdAt || '').slice(0, 10));

    renderActions(g);
    renderPipeline(g);
  }

  function stateLabel(value, labels, fallback) {
    return labels[value] || fallback;
  }

  /** 서버의 색인 상태를 기준으로 중복 색인을 막고 다음 작업을 안내한다. */
  function renderPipeline(g) {
    var indexing = stateLabel(g.indexingStatus, {
      NOT_INDEXED: '색인 대기',
      INDEXING: '색인 중',
      INDEXED: '색인 완료',
      FAILED: '색인 실패'
    }, '색인 대기');
    var indexingClass = (g.indexingStatus || 'NOT_INDEXED').toLowerCase().replaceAll('_', '-');
    var statusHtml =
      '<div class="guide-pipeline-state guide-pipeline-state--' + indexingClass + '">' +
        '<span>벡터 색인</span><strong>' + esc(indexing) + '</strong>' +
      '</div>' +
      '<div class="guide-pipeline-state">' +
        '<span>검수 청크</span><strong>' + Number(g.chunkCount || 0) + '개</strong>' +
      '</div>';
    if (g.indexingError) {
      statusHtml += '<p class="guide-pipeline__error">색인 오류 · ' + esc(g.indexingError) + '</p>';
    }
    statusHtml += '<p class="guide-pipeline__next">' + esc(nextStepText(g)) + '</p>';
    document.getElementById('guide-pipeline-status').innerHTML = statusHtml;

    var actions = document.getElementById('guide-pipeline-actions');
    actions.innerHTML = '';
    if (g.status !== 'DRAFT') return;
    var indexButton = document.createElement('button');
    indexButton.type = 'button';
    indexButton.className = 'btn btn--ghost guide-index-btn';
    indexButton.id = 'guide-index-btn';
    indexButton.textContent = g.indexingStatus === 'INDEXED' ? '벡터 다시 색인' : '벡터 색인';
    indexButton.disabled = !Number(g.chunkCount || 0) || g.indexingStatus === 'INDEXING';
    indexButton.addEventListener('click', indexGuide);
    actions.appendChild(indexButton);
  }

  function nextStepText(g) {
    if (g.status === 'ACTIVE') return '현재 실제 분석에서 사용 중인 가이드입니다.';
    if (g.status === 'INACTIVE') return '비활성 버전입니다. 최신 버전을 생성해 수정할 수 있습니다.';
    if (g.indexingStatus === 'INDEXING') return '검수 청크를 벡터 저장소에 반영하고 있습니다.';
    if (g.indexingStatus === 'FAILED') return '오류 내용을 확인한 뒤 벡터 색인을 다시 실행해 주십시오.';
    if (g.indexingStatus !== 'INDEXED') return '청크를 확인·저장한 뒤 벡터 색인을 실행해 주십시오.';
    return '검수와 색인이 끝났습니다. 최신 버전 활성화를 진행할 수 있습니다.';
  }

  function indexGuide() {
    var button = document.getElementById('guide-index-btn');
    if (!button || button.disabled) return;
    if (state.currentGuide && state.currentGuide.indexingStatus === 'INDEXED'
        && !confirm('현재 검수 내용으로 벡터를 다시 색인하시겠습니까?')) {
      return;
    }
    button.disabled = true;
    button.textContent = '벡터 색인 중...';
    notice('info', '검수한 청크의 벡터 색인을 시작했습니다.');
    guideApi('/guides/' + guideId + '/index', { method: 'POST' })
      .then(function () {
        notice('success', '벡터 색인이 완료됐습니다. 이제 최신 버전을 활성화할 수 있습니다.');
        return loadAndRender();
      })
      .catch(function (e) {
        notice('error', e.message);
        loadAndRender();
      });
  }

  function loadChunks() {
    return guideApi('/guides/' + guideId + '/chunks').then(renderChunks).catch(function (e) {
      document.getElementById('guide-chunk-review').hidden = true;
      throw e;
    });
  }

  /**
   * 처리 도중 페이지를 다시 열어도 완료 상태를 확인할 수 있도록 상태 조회만 짧게 반복한다.
   * 원문과 청크 내용은 폴링 요청에 포함하지 않는다.
   */
  function schedulePipelinePoll(guide) {
    var running = guide.indexingStatus === 'INDEXING';
    if (!running || state.disposed) return;
    state.pollTimer = window.setTimeout(loadAndRender, 3000);
  }

  function renderChunks(chunks) {
    var section = document.getElementById('guide-chunk-review');
    var list = document.getElementById('guide-chunk-list');
    document.getElementById('guide-chunk-count').textContent = chunks.length + '개';
    section.hidden = chunks.length === 0;
    var editable = state.currentGuide && state.currentGuide.status === 'DRAFT'
      && state.currentGuide.indexingStatus !== 'INDEXING';
    list.innerHTML = chunks.map(function (chunk, index) {
      return '<article class="guide-chunk-item" data-chunk-index="' + index + '">' +
        '<div class="guide-chunk-item__number">' + String(index + 1).padStart(2, '0') + '</div>' +
        '<div class="guide-chunk-item__fields">' +
          '<label>청크 제목<input class="text-input" data-chunk-title value="' + esc(chunk.title || '') + '"' + (editable ? '' : ' disabled') + '></label>' +
          '<label>내용 요약<textarea class="textarea-input" data-chunk-summary' + (editable ? '' : ' disabled') + '>' + esc(chunk.contentSummary || '') + '</textarea></label>' +
          '<label>검색 원문<textarea class="textarea-input guide-chunk-item__content" data-chunk-content' + (editable ? '' : ' disabled') + '>' + esc(chunk.content || '') + '</textarea></label>' +
        '</div>' +
      '</article>';
    }).join('');
    document.getElementById('guide-chunks-save-btn').hidden = !editable;
    document.getElementById('guide-chunks-reload-btn').hidden = !editable;
    section.classList.toggle('is-readonly', !editable);
  }

  /** 부분 저장 대신 화면의 전체 청크를 보내 순서 중복과 누락을 방지한다. */
  function saveChunks() {
    if (!state.currentGuide || state.currentGuide.status !== 'DRAFT') {
      notice('error', '초안 상태의 최신 가이드만 청크를 수정할 수 있습니다.');
      return;
    }
    var chunks = Array.from(document.querySelectorAll('.guide-chunk-item')).map(function (item, index) {
      return {
        chunkIndex: index,
        title: item.querySelector('[data-chunk-title]').value.trim(),
        contentSummary: item.querySelector('[data-chunk-summary]').value.trim(),
        content: item.querySelector('[data-chunk-content]').value.trim()
      };
    });
    if (!chunks.length || chunks.some(function (chunk) { return !chunk.content; })) {
      notice('error', '모든 청크의 검색 원문을 입력해 주십시오.');
      return;
    }
    if (state.currentGuide.indexingStatus === 'INDEXED'
        && !confirm('청크를 저장하면 현재 벡터 색인이 해제됩니다. 저장 후 다시 색인하시겠습니까?')) {
      return;
    }
    var button = document.getElementById('guide-chunks-save-btn');
    button.disabled = true;
    guideApi('/guides/' + guideId + '/chunks', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ chunks: chunks })
    }).then(function () {
      notice('success', '검수한 청크를 저장했습니다.');
      return loadAndRender();
    }).catch(function (e) {
      notice('error', e.message);
    }).finally(function () {
      button.disabled = false;
    });
  }

  function renderActions(g) {
    var html = '';
    if (g.status === 'ACTIVE') {
      html += '<button class="btn btn--ghost" style="border:1px solid #D6DBE3;" id="guide-deactivate-btn">비활성화</button>';
    } else if (g.status === 'DRAFT') {
      html += '<button class="btn btn--primary" id="guide-activate-btn"' +
        (g.indexingStatus !== 'INDEXED' ? ' disabled title="검수와 벡터 색인을 먼저 완료해 주십시오."' : '') +
        '>최신 버전 활성화</button>';
    }
    html += '<button class="btn btn--ghost" style="border:1px solid #D6DBE3;" id="guide-version-btn">새 버전 만들기</button>';
    document.getElementById('guide-detail-actions').innerHTML = html;

    var activateBtn = document.getElementById('guide-activate-btn');
    if (activateBtn) activateBtn.addEventListener('click', activateGuide);
    var deactivateBtn = document.getElementById('guide-deactivate-btn');
    if (deactivateBtn) deactivateBtn.addEventListener('click', deactivateGuide);
    document.getElementById('guide-version-btn').addEventListener('click', function () { openVersionForm(g); });
  }

  /** 활성화 검증은 서버가 전담하며 성공 시 같은 범위의 이전 ACTIVE가 자동 교체된다. */
  function activateGuide() {
    var button = document.getElementById('guide-activate-btn');
    if (!button || button.disabled) return;
    if (!confirm('검수와 색인을 완료한 이 버전을 실제 분석 가이드로 활성화하시겠습니까?')) return;
    button.disabled = true;
    guideApi('/guides/' + guideId + '/activate', { method: 'POST' })
      .then(function () {
        notice('success', '최신 가이드 버전을 활성화했습니다.');
        return loadAndRender();
      })
      .catch(function (e) {
        notice('error', e.message);
        loadAndRender();
      });
  }

  function deactivateGuide() {
    api('/admin/guides/' + guideId + '/deactivate', { method: 'PATCH' })
      .then(function () {
        notice('success', '가이드를 비활성화했습니다.');
        return loadAndRender();
      })
      .catch(function (e) { notice('error', e.message); });
  }

  // ---- 새 버전 만들기 ----
  function linesToList(id) {
    return document.getElementById(id).value
      .split('\n')
      .map(function (s) { return s.trim(); })
      .filter(function (s) { return s.length > 0; });
  }

  function listToLines(list) {
    return (list || []).join('\n');
  }

  function onScopeTypeChange() {
    var scopeType = document.getElementById('gv-scope-type').value;
    document.getElementById('gv-scope-category-field').hidden = scopeType !== 'CATEGORY';
    document.getElementById('gv-scope-main-field').hidden = scopeType !== 'PARENT_CATEGORY';
  }

  function onSourceTypeChange() {
    var isPdf = document.getElementById('gv-source-type').value === 'PDF';
    document.getElementById('gv-file-field').hidden = !isPdf;
    document.getElementById('gv-source-text-field').hidden = isPdf;
  }

  function openVersionForm(g) {
    document.getElementById('gv-title').value = g.title || '';
    document.getElementById('gv-scope-type').value = g.scopeType;
    if (g.scopeType === 'CATEGORY') {
      var match = jobCategories.find(function (c) {
        return c.mainCategory === g.mainCategory && c.subCategory === g.subCategory && c.careerLevel === g.careerLevel;
      });
      if (match) document.getElementById('gv-job-category').value = match.jobCategoryId;
    }
    document.getElementById('gv-scope-main').value = g.scopeMainCategory || '';
    document.getElementById('gv-applicable-scope').value = g.applicableScope || '';
    document.getElementById('gv-evaluation-focus').value = listToLines(g.evaluationFocus);
    document.getElementById('gv-evidence-rules').value = listToLines(g.evidenceRules);
    document.getElementById('gv-question-direction').value = listToLines(g.questionDirection);
    document.getElementById('gv-avoid-questions').value = listToLines(g.avoidQuestions);
    document.getElementById('gv-extra-instructions').open = Boolean(
      g.applicableScope
      || (g.evaluationFocus && g.evaluationFocus.length)
      || (g.evidenceRules && g.evidenceRules.length)
      || (g.questionDirection && g.questionDirection.length)
      || (g.avoidQuestions && g.avoidQuestions.length)
    );
    document.getElementById('gv-source-type').value = g.sourceType === 'PDF' ? 'PDF' : 'DIRECT_INPUT';
    document.getElementById('gv-file').value = '';
    document.getElementById('gv-source-text').value = '';
    document.getElementById('gv-scope-type').disabled = true;
    document.getElementById('gv-job-category').disabled = true;
    document.getElementById('gv-scope-main').disabled = true;
    onScopeTypeChange();
    onSourceTypeChange();
    document.getElementById('guide-version-form').hidden = false;
    document.getElementById('guide-version-form').scrollIntoView({ behavior: 'smooth' });
  }

  function closeVersionForm() {
    document.getElementById('guide-version-form').hidden = true;
  }

  function saveVersion() {
    var scopeType = document.getElementById('gv-scope-type').value;
    var request = {
      title: document.getElementById('gv-title').value.trim(),
      scopeType: scopeType,
      jobCategoryId: scopeType === 'CATEGORY' ? parseInt(document.getElementById('gv-job-category').value, 10) : null,
      scopeMainCategory: scopeType === 'PARENT_CATEGORY' ? document.getElementById('gv-scope-main').value.trim() : null,
      applicableScope: document.getElementById('gv-applicable-scope').value.trim(),
      evaluationFocus: linesToList('gv-evaluation-focus'),
      evidenceRules: linesToList('gv-evidence-rules'),
      questionDirection: linesToList('gv-question-direction'),
      avoidQuestions: linesToList('gv-avoid-questions'),
      sourceType: document.getElementById('gv-source-type').value,
      sourceText: document.getElementById('gv-source-text').value.trim()
    };

    if (!request.title) {
      notice('error', '제목은 필수입니다.');
      return;
    }
    var fileInput = document.getElementById('gv-file');
    if (request.sourceType === 'PDF' && !fileInput.files[0]) {
      notice('error', '새 버전의 PDF 파일을 선택해 주십시오.');
      return;
    }
    if (request.sourceType === 'DIRECT_INPUT' && !request.sourceText) {
      notice('error', '새 버전의 가이드 원문을 입력해 주십시오.');
      return;
    }

    var formData = new FormData();
    formData.append('request', new Blob([JSON.stringify(request)], { type: 'application/json' }));
    if (request.sourceType === 'PDF' && fileInput.files[0]) {
      formData.append('file', fileInput.files[0]);
    }

    fetch(window.JobPuzzleRoutes.path('/admin-api/guides/' + guideId + '/versions'), { method: 'POST', credentials: 'same-origin', body: formData })
      .then(function (res) {
        return res.json().then(function (body) {
          if (!res.ok || !body.success) throw new Error((body && body.message) || '새 버전 등록에 실패했습니다.');
          return body.data;
        });
      })
      .then(function (newGuide) {
        notice('success', '새 가이드 버전을 만들었습니다.');
        window.location.href = window.JobPuzzleRoutes.path('/admin/guides/' + newGuide.guideId);
      })
      .catch(function (e) { notice('error', e.message); });
  }

  document.addEventListener('DOMContentLoaded', function () {
    if (!document.getElementById('guide-detail-body') || !guideId) return;
    document.getElementById('gv-scope-type').addEventListener('change', onScopeTypeChange);
    document.getElementById('gv-source-type').addEventListener('change', onSourceTypeChange);
    document.getElementById('gv-save-btn').addEventListener('click', saveVersion);
    document.getElementById('gv-cancel-btn').addEventListener('click', closeVersionForm);
    document.getElementById('guide-chunks-save-btn').addEventListener('click', saveChunks);
    document.getElementById('guide-chunks-reload-btn').addEventListener('click', function () {
      loadChunks().catch(function (e) { notice('error', e.message); });
    });
    window.addEventListener('pagehide', function () {
      state.disposed = true;
      window.clearTimeout(state.pollTimer);
    }, { once: true });
    loadJobCategories().then(loadAndRender).catch(function (e) {
      notice('error', e.message);
    });
  });
})();
