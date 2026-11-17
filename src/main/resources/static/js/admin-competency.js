// admin-competency.js — 관리자 직무별 역량 태그·인성 질문 관리 (/jobpuzzle/admin-api/competency)
(function () {
  'use strict';

  var CAREER_LABEL = { NEW: '신입', EXPERIENCED: '경력', ANY: '무관' };

  function api(path, opts) {
    opts = opts || {};
    var headers = {};
    var fetchOpts = { method: opts.method || 'GET', credentials: 'same-origin', headers: headers };
    if (opts.json !== undefined) {
      headers['Content-Type'] = 'application/json; charset=UTF-8';
      fetchOpts.body = JSON.stringify(opts.json);
    }
    return fetch(window.JobPuzzleRoutes.path('/admin-api/competency' + path), fetchOpts).then(function (res) {
      return res.json().then(function (body) {
        if (!res.ok || !body.success) throw new Error((body && body.message) || '요청에 실패했습니다.');
        return body.data;
      });
    });
  }

  var state = { jobs: [], job: null, data: null, editingTagId: null, editingQuestionId: null };

  function $(id) { return document.getElementById(id); }

  function tagLabel(code) {
    var all = state.data.commonTags.concat(state.data.jobTags);
    var found = all.find(function (t) { return t.code === code; });
    return found ? found.name : code;
  }

  function loadJobs() {
    return api('/jobs').then(function (jobs) {
      state.jobs = jobs;
      $('cp-job').innerHTML = jobs.map(function (j, i) {
        return '<option value="' + i + '">' + esc(j.mainCategory) + ' / ' + esc(j.subCategory) + '</option>';
      }).join('');
      if (jobs.length) selectJob(0);
    }).catch(function (e) { alert(e.message); });
  }

  function selectJob(index) {
    state.job = state.jobs[index];
    closeTagForm();
    closeQuestionForm();
    reload();
  }

  function reload() {
    var q = '?mainCategory=' + encodeURIComponent(state.job.mainCategory) +
      '&subCategory=' + encodeURIComponent(state.job.subCategory);
    return api(q).then(function (data) {
      state.data = data;
      render();
    }).catch(function (e) { alert(e.message); });
  }

  function render() {
    var d = state.data;
    $('cp-common').innerHTML = d.commonTags.map(function (t) {
      return '<span class="badge" title="' + esc(t.code) + '">' + esc(t.name) + '</span>';
    }).join('');

    $('cp-tag-rows').innerHTML = d.jobTags.map(function (t) {
      return '<div class="table-row" style="grid-template-columns:1fr 0.8fr 2.4fr 0.9fr;">' +
        '<span style="font-size:12px; font-family:monospace;">' + esc(t.code) + '</span>' +
        '<span style="font-size:13px;">' + esc(t.name) + '</span>' +
        '<span style="font-size:13px;">' + esc(t.description) + '</span>' +
        '<span style="display:flex; gap:6px;">' +
          '<button class="btn-sm" data-tag-edit="' + t.tagId + '">수정</button>' +
          '<button class="btn-sm btn-sm--danger" data-tag-del="' + t.tagId + '">삭제</button>' +
        '</span></div>';
    }).join('') || '<div class="table-empty">등록된 직무별 역량 태그가 없습니다.</div>';

    $('cp-question-count').textContent = '(' + d.personalityQuestions.length + '개)';
    $('cp-question-rows').innerHTML = d.personalityQuestions.map(function (q) {
      return '<div class="table-row" style="grid-template-columns:2.6fr 1.2fr 0.6fr 0.9fr;">' +
        '<span style="font-size:13px;">' + esc(q.question) +
          '<br><span style="font-size:12px; color:var(--text-3, #6b7280);">' + esc(q.intent) + '</span></span>' +
        '<span style="display:flex; gap:4px; flex-wrap:wrap;">' + q.competencyTags.map(function (c) {
          return '<span class="badge">' + esc(tagLabel(c)) + '</span>';
        }).join('') + '</span>' +
        '<span class="badge">' + (CAREER_LABEL[q.careerLevel] || esc(q.careerLevel)) + '</span>' +
        '<span style="display:flex; gap:6px;">' +
          '<button class="btn-sm" data-q-edit="' + q.questionId + '">수정</button>' +
          '<button class="btn-sm btn-sm--danger" data-q-del="' + q.questionId + '">삭제</button>' +
        '</span></div>';
    }).join('') || '<div class="table-empty">등록된 인성 질문이 없습니다.</div>';

    bindAll('[data-tag-edit]', function (id) { openTagForm(id); }, 'tagEdit');
    bindAll('[data-tag-del]', removeTag, 'tagDel');
    bindAll('[data-q-edit]', function (id) { openQuestionForm(id); }, 'qEdit');
    bindAll('[data-q-del]', removeQuestion, 'qDel');
  }

  function bindAll(selector, handler, key) {
    document.querySelectorAll(selector).forEach(function (b) {
      b.addEventListener('click', function () { handler(parseInt(b.dataset[key], 10)); });
    });
  }

  // ── 직무별 역량 태그 ──
  function openTagForm(tagId) {
    var tag = tagId ? state.data.jobTags.find(function (t) { return t.tagId === tagId; }) : null;
    state.editingTagId = tag ? tagId : null;
    $('cp-tag-form-title').textContent = tag ? '태그 수정' : '태그 추가';
    $('cp-tag-code').value = tag ? tag.code : '';
    $('cp-tag-name').value = tag ? tag.name : '';
    $('cp-tag-desc').value = tag ? tag.description : '';
    $('cp-tag-form').hidden = false;
    $('cp-tag-code').focus();
  }

  function closeTagForm() {
    $('cp-tag-form').hidden = true;
    state.editingTagId = null;
  }

  function saveTag() {
    var body = {
      mainCategory: state.job.mainCategory,
      subCategory: state.job.subCategory,
      code: $('cp-tag-code').value.trim().toUpperCase(),
      name: $('cp-tag-name').value.trim(),
      description: $('cp-tag-desc').value.trim()
    };
    if (!body.code || !body.name || !body.description) { alert('코드, 이름, 설명을 모두 입력해주세요.'); return; }
    var call = state.editingTagId
      ? api('/tags/' + state.editingTagId, { method: 'PUT', json: body })
      : api('/tags', { method: 'POST', json: body });
    call.then(function () { closeTagForm(); reload(); }).catch(function (e) { alert(e.message); });
  }

  function removeTag(tagId) {
    if (!confirm('이 역량 태그를 삭제할까요?')) return;
    api('/tags/' + tagId, { method: 'DELETE' }).then(reload).catch(function (e) { alert(e.message); });
  }

  // ── 인성 질문 ──
  function renderTagChoices(selected) {
    var all = state.data.commonTags.concat(state.data.jobTags);
    $('cp-question-tags').innerHTML = all.map(function (t) {
      var checked = selected.indexOf(t.code) >= 0 ? ' checked' : '';
      return '<label style="display:inline-flex; gap:4px; align-items:center; font-size:13px; margin-right:10px;">' +
        '<input type="checkbox" value="' + esc(t.code) + '"' + checked + '>' + esc(t.name) + '</label>';
    }).join('');
  }

  function openQuestionForm(questionId) {
    var q = questionId ? state.data.personalityQuestions.find(function (x) { return x.questionId === questionId; }) : null;
    state.editingQuestionId = q ? questionId : null;
    $('cp-question-form-title').textContent = q ? '질문 수정' : '질문 추가';
    $('cp-question-text').value = q ? q.question : '';
    $('cp-question-intent').value = q ? q.intent : '';
    $('cp-question-career').value = q ? q.careerLevel : 'ANY';
    renderTagChoices(q ? q.competencyTags : []);
    $('cp-question-form').hidden = false;
    $('cp-question-text').focus();
  }

  function closeQuestionForm() {
    $('cp-question-form').hidden = true;
    state.editingQuestionId = null;
  }

  function saveQuestion() {
    var tags = Array.prototype.map.call(
      document.querySelectorAll('#cp-question-tags input:checked'), function (i) { return i.value; });
    var body = {
      mainCategory: state.job.mainCategory,
      subCategory: state.job.subCategory,
      question: $('cp-question-text').value.trim(),
      intent: $('cp-question-intent').value.trim(),
      competencyTags: tags,
      careerLevel: $('cp-question-career').value
    };
    if (!body.question || !body.intent) { alert('질문과 출제 의도를 입력해주세요.'); return; }
    if (tags.length < 1 || tags.length > 2) { alert('역량 태그를 1~2개 선택해주세요.'); return; }
    var call = state.editingQuestionId
      ? api('/questions/' + state.editingQuestionId, { method: 'PUT', json: body })
      : api('/questions', { method: 'POST', json: body });
    call.then(function () { closeQuestionForm(); reload(); }).catch(function (e) { alert(e.message); });
  }

  function removeQuestion(questionId) {
    if (!confirm('이 인성 질문을 삭제할까요?')) return;
    api('/questions/' + questionId, { method: 'DELETE' }).then(reload).catch(function (e) { alert(e.message); });
  }

  document.addEventListener('DOMContentLoaded', function () {
    if (!$('cp-job')) return;
    $('cp-job').addEventListener('change', function () { selectJob(parseInt(this.value, 10)); });
    $('cp-tag-add').addEventListener('click', function () { openTagForm(null); });
    $('cp-tag-save').addEventListener('click', saveTag);
    $('cp-tag-cancel').addEventListener('click', closeTagForm);
    $('cp-question-add').addEventListener('click', function () { openQuestionForm(null); });
    $('cp-question-save').addEventListener('click', saveQuestion);
    $('cp-question-cancel').addEventListener('click', closeQuestionForm);
    loadJobs();
  });
})();
