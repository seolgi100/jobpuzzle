(function () {
  'use strict';

  function notify(type, message, duration) {
    if (typeof window.showToast === 'function') {
      return window.showToast(type, message, duration);
    }
    window.alert(message);
    return null;
  }

  var params = new URLSearchParams(window.location.search);
  var analysisCaseId = params.get('analysisCaseId');
  var resumeSessionId = params.get('resumeSessionId');
  var requestedMode = params.get('mode');

  var root;
  var questionSet;
  var session;
  var questions = [];
  var currentIndex = 0;
  var followUpMessageId = null;
  var activeSection;
  var reviewSection;
  var activeSessions = [];
  var analysisSection;
  var analysisAttentionSection;
  var preparedAnalysisSection;
  var preparedQuestionSets = [];
  var preparedAnalyses = [];
  var weaknessTagDetails = [];
  var completing = false;
  var speechRecognition = null;
  var speechListening = false;
  var selectionModal = null;
  var selectionHistoryActive = false;
  var selectionRequiresExitWarning = false;
  var PAGE_SIZE = 5;
  var sectionPages = {};
  var allowLiveNavigation = false;
  var sectionBoard = null;
  var sectionOrder = [
    'ACTIVE_ANALYSIS',
    'ACTIVE_INTERVIEW',
    'PREPARED_QUESTION',
    'REVIEW_INTERVIEW'
  ];
  var sectionLabels = {
    ACTIVE_ANALYSIS: '진행 중인 맞춤 분석',
    ACTIVE_INTERVIEW: '진행 중인 면접',
    PREPARED_QUESTION: '면접 질문이 준비된 항목',
    REVIEW_INTERVIEW: '결과 검토 중인 면접'
  };
  var sectionOrderEditing = false;
  var sectionOrderBeforeEdit = null;
  var draggedSectionSlot = null;
  var sectionOrderPageAction = null;

  function createSectionBoard() {
    if (sectionBoard || analysisCaseId || !root) return;
    sectionBoard = document.createElement('div');
    sectionBoard.className = 'interview-section-board';
    sectionBoard.innerHTML =
      '<div class="interview-section-order-actions" hidden>' +
      '<button type="button" class="btn btn--ghost" data-section-order-reset>초기화</button>' +
      '<button type="button" class="btn btn--ghost" data-section-order-cancel>취소</button>' +
      '<button type="button" class="btn btn--primary" data-section-order-save>순서 저장</button>' +
      '</div><div class="interview-section-slots"></div>';
    root.insertAdjacentElement('afterend', sectionBoard);
    createSectionOrderPageAction();
    sectionBoard.querySelector('[data-section-order-reset]').addEventListener('click', function () {
      sectionOrder = ['ACTIVE_ANALYSIS', 'ACTIVE_INTERVIEW', 'PREPARED_QUESTION', 'REVIEW_INTERVIEW'];
      applySectionOrder();
    });
    sectionBoard.querySelector('[data-section-order-cancel]').addEventListener('click', cancelSectionOrderEdit);
    sectionBoard.querySelector('[data-section-order-save]').addEventListener('click', finishSectionOrderEdit);
    var slots = sectionBoard.querySelector('.interview-section-slots');
    slots.addEventListener('dragstart', handleSectionDragStart);
    slots.addEventListener('dragover', handleSectionDragOver);
    slots.addEventListener('dragend', handleSectionDragEnd);
    applySectionOrder();
  }

  function slotFor(key) {
    if (!sectionBoard) return null;
    var slots = sectionBoard.querySelector('.interview-section-slots');
    var slot = slots.querySelector('[data-section-slot="' + key + '"]');
    if (!slot) {
      slot = document.createElement('div');
      slot.className = 'interview-section-slot';
      slot.dataset.sectionSlot = key;
      slot.innerHTML = emptySectionShell(key);
      slots.appendChild(slot);
    }
    return slot;
  }

  function applySectionOrder() {
    if (!sectionBoard) return;
    var slots = sectionBoard.querySelector('.interview-section-slots');
    sectionOrder.forEach(function (key) {
      slots.appendChild(slotFor(key));
    });
  }

  function mountSection(key, section) {
    var slot = slotFor(key);
    if (!slot) return;
    slot.replaceChildren();
    if (section) slot.appendChild(section);
    else slot.innerHTML = emptySectionShell(key);
  }

  function loadSectionOrder() {
    return api(window.JobPuzzleRoutes.path('/api/interview-view-preference')).then(function (preference) {
      if (preference && Array.isArray(preference.sectionOrder) && preference.sectionOrder.length === 4) {
        sectionOrder = preference.sectionOrder.slice();
        applySectionOrder();
      }
    }).catch(function () {});
  }

  function saveSectionOrder(order) {
    return api(window.JobPuzzleRoutes.path('/api/interview-view-preference'), {
      method: 'PUT',
      json: { sectionOrder: order }
    }).then(function (preference) {
      sectionOrder = preference.sectionOrder.slice();
      applySectionOrder();
      notify('success', '섹션 순서를 저장했습니다.');
    });
  }

  function emptySectionShell(key) {
    return '<section class="interview-empty-order-section" aria-label="' + esc(sectionLabels[key]) + '">' +
      '<span class="interview-section-drag-handle" aria-hidden="true">⋮⋮</span>' +
      '<div><h2>' + esc(sectionLabels[key]) + '</h2><p>현재 표시할 항목이 없습니다.</p></div>' +
      '<small>데이터가 생기면 이 위치에 표시됩니다.</small></section>';
  }

  function beginSectionOrderEdit() {
    if (!sectionBoard || sectionOrderEditing) return;
    sectionOrderEditing = true;
    sectionOrderBeforeEdit = sectionOrder.slice();
    sectionBoard.classList.add('is-order-editing');
    if (sectionOrderPageAction) sectionOrderPageAction.hidden = true;
    sectionBoard.querySelector('.interview-section-order-actions').hidden = false;
    sectionBoard.querySelectorAll('.interview-section-slot').forEach(function (slot) {
      slot.draggable = true;
    });
    window.requestAnimationFrame(function () {
      sectionBoard.scrollIntoView({ behavior: 'smooth', block: 'start' });
    });
  }

  function leaveSectionOrderEdit() {
    sectionOrderEditing = false;
    sectionOrderBeforeEdit = null;
    sectionBoard.classList.remove('is-order-editing');
    if (sectionOrderPageAction) sectionOrderPageAction.hidden = false;
    sectionBoard.querySelector('.interview-section-order-actions').hidden = true;
    sectionBoard.querySelectorAll('.interview-section-slot').forEach(function (slot) {
      slot.draggable = false;
    });
  }

  function createSectionOrderPageAction() {
    if (sectionOrderPageAction || !root) return;
    sectionOrderPageAction = document.createElement('div');
    sectionOrderPageAction.className = 'interview-section-order-page-action';
    sectionOrderPageAction.innerHTML =
      '<button type="button" class="btn btn--outline" data-section-order-edit>섹션 순서 설정</button>';
    var subtitle = document.querySelector('.page-subtitle');
    if (subtitle) subtitle.insertAdjacentElement('afterend', sectionOrderPageAction);
    else root.parentNode.insertBefore(sectionOrderPageAction, root);
    sectionOrderPageAction.querySelector('[data-section-order-edit]').addEventListener('click', beginSectionOrderEdit);
  }

  function cancelSectionOrderEdit() {
    sectionOrder = sectionOrderBeforeEdit.slice();
    applySectionOrder();
    leaveSectionOrderEdit();
  }

  function finishSectionOrderEdit() {
    var order = Array.from(sectionBoard.querySelectorAll('.interview-section-slot'))
      .map(function (slot) { return slot.dataset.sectionSlot; });
    saveSectionOrder(order).then(leaveSectionOrderEdit)
      .catch(function (error) { notify('error', error.message); });
  }

  function handleSectionDragStart(event) {
    if (!sectionOrderEditing) return;
    draggedSectionSlot = event.target.closest('.interview-section-slot');
    if (!draggedSectionSlot) return;
    draggedSectionSlot.classList.add('is-dragging');
    event.dataTransfer.effectAllowed = 'move';
  }

  function handleSectionDragOver(event) {
    if (!sectionOrderEditing || !draggedSectionSlot) return;
    event.preventDefault();
    var target = event.target.closest('.interview-section-slot');
    if (!target || target === draggedSectionSlot) return;
    var rect = target.getBoundingClientRect();
    target.parentNode.insertBefore(
      draggedSectionSlot,
      event.clientY < rect.top + rect.height / 2 ? target : target.nextSibling
    );
  }

  function handleSectionDragEnd() {
    if (draggedSectionSlot) draggedSectionSlot.classList.remove('is-dragging');
    draggedSectionSlot = null;
  }

  function pageItems(items, key) {
    var pageCount = Math.max(1, Math.ceil((items || []).length / PAGE_SIZE));
    var page = Math.min(sectionPages[key] || 1, pageCount);
    sectionPages[key] = page;
    return (items || []).slice((page - 1) * PAGE_SIZE, page * PAGE_SIZE);
  }

  function paginationHtml(items, key) {
    var pageCount = Math.ceil((items || []).length / PAGE_SIZE);
    if (pageCount <= 1) return '';
    var current = sectionPages[key] || 1;
    var buttons = '';
    for (var page = 1; page <= pageCount; page += 1) {
      buttons += '<button type="button" class="' + (page === current ? 'is-active' : '') +
        '" data-page-key="' + key + '" data-page="' + page + '" aria-label="' + page +
        '페이지">' + page + '</button>';
    }
    return '<nav class="section-pagination" aria-label="목록 페이지">' + buttons + '</nav>';
  }

  function bindPagination(section, key, render) {
    section.querySelectorAll('[data-page-key="' + key + '"]').forEach(function (button) {
      button.addEventListener('click', function () {
        sectionPages[key] = Number(button.dataset.page);
        render();
      });
    });
  }

  function esc(value) {
    var element = document.createElement('div');
    element.textContent = value == null ? '' : String(value);
    return element.innerHTML;
  }

  function koreanizeEvaluationTerms(value) {
    var labels = {
      intentMatch: '질문 의도 이해',
      specificity: '경험 구체성',
      ownRole: '본인 역할',
      problemSolving: '문제 해결 과정',
      resultExpression: '성과 및 결과 표현',
      requirementConnection: '공고 요구사항 연결',
      guideAlignment: '직무 기준 연결',
      deliveryClarity: '답변 전달력'
    };
    var text = value == null ? '' : String(value);
    Object.keys(labels).forEach(function (key) {
      text = text.replace(new RegExp(key, 'gi'), labels[key]);
    });
    return text;
  }

  function weaknessOriginHtml(question) {
    if (!question.originSessionId) return '';
    var mode = question.originMode === 'WEAKNESS_REVIEW' ? '약점 보완' : '맞춤 면접';
    var diagnostics = Array.isArray(question.originDiagnostics)
      ? question.originDiagnostics.map(function (item) {
          return '<i>' + esc(koreanizeEvaluationTerms(weaknessLabel(item))) + '</i>';
        }).join('')
      : '';
    return '<small class="q-gen-item__origin"><b>질문 출처</b>' +
      '<span>' + mode + ' · ' + (question.originScore == null ? '미평가' : question.originScore + '점') +
      '</span>' + diagnostics + '</small>';
  }

  function api(path, options) {
    options = options || {};
    var fetchOptions = {
      method: options.method || 'GET',
      credentials: 'same-origin',
      headers: {}
    };
    if (options.json !== undefined) {
      fetchOptions.headers['Content-Type'] = 'application/json; charset=UTF-8';
      fetchOptions.body = JSON.stringify(options.json);
    }
    return fetch(path, fetchOptions).then(function (response) {
      return response.json().then(function (body) {
        if (!response.ok || !body.success) {
          var error = new Error(body.message || '요청에 실패했습니다.');
          error.status = response.status;
          throw error;
        }
        return body.data;
      });
    });
  }

  function message(text, error, generationMode) {
    hideActiveSection();
    if (window.InterviewHome) window.InterviewHome.hide();
    var loadingContent = generationMode
      ? '<div class="question-generation-loading" role="status" aria-live="polite">' +
          '<canvas class="question-generation-loading__puzzle" data-job-puzzle-scene aria-hidden="true"></canvas>' +
          '<p class="question-generation-loading__message">' + esc(text) + '</p>' +
        '</div>'
      : '<p style="font-size:14px;color:' + (error ? '#B5433D' : '#5B6370') + ';">' + esc(text) + '</p>';
    root.innerHTML = '<div class="card card--pad-lg" style="text-align:center;padding:48px 20px;">' +
      loadingContent +
      (error ? '<a class="btn btn--primary" href="' + window.JobPuzzleRoutes.path('/interview') + '" style="text-decoration:none;">면접 준비로 돌아가기</a>' : '') +
      '</div>';
  }

  function modeLabel(mode) {
    if (mode === 'BASIC') return '기본 질문';
    if (mode === 'COMPANY_FIT') return '맞춤 면접';
    return '약점 보완';
  }

  function modeIcon(mode) {
    var icons = {
      BASIC: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><path d="M14 2v6h6"/></svg>',
      WEAKNESS_REVIEW: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M20.59 13.41l-7.17 7.17a2 2 0 0 1-2.83 0L2 12V2h10l8.59 8.59a2 2 0 0 1 0 2.82z"/><circle cx="7" cy="7" r="1"/></svg>',
      COMPANY_FIT: '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 2l1.9 5.8L20 10l-6.1 2.2L12 18l-1.9-5.8L4 10l6.1-2.2z"/></svg>'
    };
    return icons[mode] || icons.BASIC;
  }

  function weaknessLabel(tag) {
    var value = String(tag || '').toLowerCase();
    var compact = value.replace(/[_\-\s]/g, '');
    if (compact.indexOf('lackofspecificdetail') >= 0) return '구체성 부족';
    if (compact.indexOf('lackoftechnicaldepth') >= 0) return '기술적 깊이 부족';
    if (compact.indexOf('insufficientcloudexperience') >= 0) return '클라우드 경험 부족';
    if (compact.indexOf('vaguerole') >= 0) return '역할 설명 부족';
    if (compact.indexOf('limitedownership') >= 0) return '주도성 부족';
    if (value.indexOf('requirementconnection') >= 0) return '공고 요구사항 연결 부족';
    if (value.indexOf('specificity') >= 0) return '경험 구체성 부족';
    if (value.indexOf('ownrole') >= 0) return '본인 역할 설명 부족';
    if (value.indexOf('problemsolving') >= 0) return '문제 해결 과정 부족';
    if (value.indexOf('resultexpression') >= 0) return '성과·결과 표현 부족';
    if (value.indexOf('guidealignment') >= 0) return '직무 기준 연결 부족';
    if (value.indexOf('deliveryclarity') >= 0) return '답변 전달력 부족';
    if (value.indexOf('intentmatch') >= 0) return '질문 의도 파악 부족';
    return tag || '';
  }

  function focusLabels(value) {
    var labels = {
      intentMatch: '질문 의도 이해',
      specificity: '경험 구체성',
      ownRole: '본인 역할',
      problemSolving: '문제 해결 과정',
      resultExpression: '성과 및 결과 표현',
      requirementConnection: '공고 요구사항 연결',
      guideAlignment: '직무 가이드 적합',
      deliveryClarity: '답변 전달력'
    };
    var items = Array.isArray(value)
      ? value
      : value && typeof value === 'object'
        ? Object.keys(value).map(function (key) { return value[key]; })
        : value ? [value] : [];
    return items.length
      ? items.map(function (item) { return labels[item] || weaknessLabel(item) || '평가 기준'; }).join(' · ')
      : '질문의 의도와 답변 근거';
  }

  function hideActiveSection() {
    if (activeSection) activeSection.hidden = true;
  }

  function stopSpeechInput() {
    if (speechRecognition && speechListening) {
      speechRecognition.stop();
    }
  }

  function setupSpeechInput() {
    var button = document.getElementById('voice-input-toggle');
    var textarea = document.getElementById('live-answer');
    var status = document.getElementById('voice-input-status');
    if (!button || !textarea || !status) return;

    var Recognition = window.SpeechRecognition || window.webkitSpeechRecognition;
    if (!Recognition) {
      button.disabled = true;
      status.textContent = '이 브라우저는 음성 입력을 지원하지 않습니다. 키보드로 답변해 주세요.';
      return;
    }

    speechRecognition = new Recognition();
    speechRecognition.lang = 'ko-KR';
    speechRecognition.continuous = true;
    speechRecognition.interimResults = true;
    speechRecognition.maxAlternatives = 1;

    var baseText = '';
    var finalText = '';

    speechRecognition.onstart = function () {
      speechListening = true;
      button.classList.add('is-listening');
      button.setAttribute('aria-pressed', 'true');
      button.innerHTML = '<i aria-hidden="true"></i>음성 입력 멈추기';
      status.textContent = '듣고 있습니다. 자연스럽게 답변해 주세요.';
    };
    speechRecognition.onresult = function (event) {
      var interimText = '';
      for (var i = event.resultIndex; i < event.results.length; i += 1) {
        var transcript = event.results[i][0].transcript.trim();
        if (event.results[i].isFinal) {
          finalText += (finalText ? ' ' : '') + transcript;
        } else {
          interimText += (interimText ? ' ' : '') + transcript;
        }
      }
      textarea.value = [baseText, finalText, interimText].filter(Boolean).join(' ').trim();
      status.textContent = interimText
        ? '인식 중: ' + interimText
        : '음성이 텍스트로 입력되었습니다. 계속 말하거나 입력을 종료할 수 있습니다.';
    };
    speechRecognition.onerror = function (event) {
      var copy = event.error === 'not-allowed'
        ? '마이크 권한이 필요합니다. 브라우저 주소창의 마이크 권한을 허용해 주세요.'
        : event.error === 'no-speech'
          ? '음성이 들리지 않았습니다. 다시 시도해 주세요.'
          : '음성 인식이 중단되었습니다. 다시 시도해 주세요.';
      status.textContent = copy;
    };
    speechRecognition.onend = function () {
      speechListening = false;
      button.classList.remove('is-listening');
      button.setAttribute('aria-pressed', 'false');
      button.innerHTML = '<i aria-hidden="true"></i>음성으로 답하기';
      if (!status.textContent || status.textContent.indexOf('인식 중:') === 0) {
        status.textContent = '음성 입력이 종료되었습니다. 변환된 문장을 확인해 주세요.';
      }
    };

    button.addEventListener('click', function () {
      if (speechListening) {
        speechRecognition.stop();
        return;
      }
      baseText = textarea.value.trim();
      finalText = '';
      try {
        speechRecognition.start();
      } catch (error) {
        status.textContent = '음성 인식을 바로 다시 시작할 수 없습니다. 잠시 후 시도해 주세요.';
      }
    });
  }

  function load() {
    message('분석 상태와 질문 세트를 확인하고 있습니다.');
    api(window.JobPuzzleRoutes.path('/analysis/cases/' + analysisCaseId + '/status')).then(function (status) {
      if (status.analysisCaseStatus !== 'COMPLETED') {
        throw new Error('분석이 아직 완료되지 않았습니다. 분석 화면에서 상태를 확인해주세요.');
      }
      if (!status.canGenerateQuestions || !status.questionSetAvailable) {
        throw new Error('현재 분석 결과로는 회사 맞춤 질문을 생성할 수 없습니다.');
      }
      return api(window.JobPuzzleRoutes.path('/analysis/cases/' + analysisCaseId + '/question-set'));
    }).then(function (set) {
      if (!set.canGenerateQuestions || !set.questions || !set.questions.length) {
        throw new Error('선택할 수 있는 회사 맞춤 질문이 없습니다.');
      }
      questionSet = set;
      selectionRequiresExitWarning = false;
      renderSelection();
    }).catch(function (error) {
      message(error.message, true);
    });
  }

  // 브라우저 기본 confirm 대신 디자인 시스템 확인 창(interview-home.js의 JPDialog)을 쓴다
  function ask(opts) {
    if (window.JPDialog) { window.JPDialog.confirm(opts); return; }
    if (window.confirm(opts.title + (opts.body ? '\n' + opts.body : ''))) opts.onConfirm();
    else if (opts.onCancel) opts.onCancel();
  }

  var MAX_ANSWER_ATTEMPTS = 3; // 서버 MAX_ADDITIONAL_ANSWER_RETRIES(2) + 첫 제출 1
  var pendingNotice = null;    // 다음 화면에서 한 번 보여줄 안내(질문이 넘어갈 때)

  // 질문 선택을 닫거나 생성이 막혔을 때 면접 준비 홈으로 돌아간다
  function returnHome() {
    if (root) root.innerHTML = '';
    if (window.InterviewPage) window.InterviewPage.showModes();
    if (window.InterviewHome) window.InterviewHome.load();
  }

  // 서버가 티켓 부족(402)을 돌려주면 오류 카드 대신 홈으로 돌아가 충전 안내 창을 띄운다
  function handleGenerationError(error, usage) {
    if (error && error.status === 402 && window.TicketConfirm) {
      returnHome();
      window.TicketConfirm.open({
        usage: usage,
        shortage: true,
        what: '질문을 만들려면 티켓이 더 필요해요. 충전한 뒤 다시 시도해 주세요.'
      });
      return;
    }
    message(error.message, true);
  }

  function openQuestionSet(questionSetId) {
    message('저장된 질문을 불러오고 있습니다.');
    api(window.JobPuzzleRoutes.path('/question-sets/' + encodeURIComponent(questionSetId)))
      .then(function (set) {
        questionSet = set;
        selectionRequiresExitWarning = false;
        renderSelection();
      })
      .catch(function (error) { message(error.message, true); });
  }

  function startBasic() {
    message('직무 기준에 맞는 기본 질문을 생성하고 있습니다.', false, 'BASIC');
    Promise.all([api(window.JobPuzzleRoutes.path('/user/me')), api(window.JobPuzzleRoutes.path('/job-category'))]).then(function (values) {
      var user = values[0];
      var categories = values[1] || [];
      var category = categories.filter(function (item) {
        return String(item.jobCategoryId) === String(user.defaultJobCategoryId);
      })[0];
      if (!category) throw new Error('마이페이지에서 희망 직무를 먼저 설정해주세요.');
      return api(window.JobPuzzleRoutes.path('/question-sets/basic'), {
        method: 'POST',
        json: { jobCategoryId: category.jobCategoryId, careerLevel: category.careerLevel }
      });
    }).then(function (set) {
      questionSet = set;
      selectionRequiresExitWarning = true;
      renderSelection();
    }).catch(function (error) { handleGenerationError(error, 'BASIC'); });
  }

  function startWeakness(tag) {
    message('선택한 약점에 맞는 보완 질문을 생성하고 있습니다.', false, 'WEAKNESS_REVIEW');
    api(window.JobPuzzleRoutes.path('/question-sets/weakness'), {
      method: 'POST',
      json: { targetWeaknessTag: tag }
    }).then(function (set) {
      questionSet = set;
      selectionRequiresExitWarning = true;
      renderSelection();
    }).catch(function (error) { handleGenerationError(error, 'WEAKNESS_REVIEW'); });
  }

  function renderSelection() {
    var rows = questionSet.questions.map(function (question, index) {
      return '<label class="q-gen-item q-gen-item--detailed" style="cursor:pointer;">' +
        '<input type="checkbox" name="question" value="' + question.questionId + '" checked>' +
        '<span class="q-gen-item__num">' + (index + 1) + '</span>' +
        '<span class="q-gen-item__content"><strong class="q-gen-item__text">' +
        esc(question.question) + '</strong><button type="button" class="question-hint-toggle" ' +
        'aria-expanded="false"><span>질문 힌트 보기</span><span class="question-hint-chevron" aria-hidden="true"></span></button>' +
        '<span class="question-hint-panel" hidden><small><b>이 질문의 의도</b>' +
        esc(koreanizeEvaluationTerms(question.intent || '답변의 구체적인 근거와 본인 역할을 확인합니다.')) +
        '</small><small><b>집중 평가 기준</b>' +
        esc(koreanizeEvaluationTerms(focusLabels(question.evaluationFocus))) + '</small>' +
        weaknessOriginHtml(question) + '</span></span></label>';
    }).join('');
    if (window.InterviewPage && window.InterviewPage.showModes) window.InterviewPage.showModes();
    if (activeSection) activeSection.hidden = false;
    closeSelectionModal(true);
    selectionModal = document.createElement('div');
    selectionModal.className = 'question-selection-modal';
    selectionModal.innerHTML =
      '<div class="question-selection-backdrop" data-close-selection></div>' +
      '<section class="question-selection-dialog" role="dialog" aria-modal="true" aria-labelledby="question-selection-title">' +
      '<header><div><span class="question-selection-mode">' + esc(modeLabel(questionSet.mode)) +
      '</span>' + (questionSet.mode === 'WEAKNESS_REVIEW'
        ? '<span class="question-selection-weakness">#' +
          esc(questionSet.targetWeaknessDisplayName || weaknessLabel(questionSet.targetWeaknessTag) || '선택한 약점') + '</span>'
        : '') + '<h2 id="question-selection-title">연습할 질문을 골라주세요</h2>' +
      '<p>선택한 순서가 아니라, 아래 표시 순서대로 면접이 진행됩니다.</p></div>' +
      '<button type="button" class="question-selection-close" data-close-selection aria-label="질문 선택 닫기">×</button></header>' +
      '<div class="q-gen-list">' + rows + '</div>' +
      '<footer><span>질문은 하나 이상 선택해야 합니다.</span>' +
      '<button id="create-live-session" class="btn btn--primary">선택한 질문으로 시작</button></footer></section>';
    document.body.appendChild(selectionModal);
    document.body.classList.add('has-selection-modal');
    window.history.pushState({ interviewSelection: true }, '', '#question-selection');
    selectionHistoryActive = true;
    selectionModal.querySelectorAll('[data-close-selection]').forEach(function (button) {
      button.addEventListener('click', function () {
        requestCloseSelection();
      });
    });
    selectionModal.querySelectorAll('.question-hint-toggle').forEach(function (button) {
      button.addEventListener('click', function (event) {
        event.preventDefault();
        event.stopPropagation();
        var panel = button.parentElement.querySelector('.question-hint-panel');
        var expanded = button.getAttribute('aria-expanded') === 'true';
        button.setAttribute('aria-expanded', String(!expanded));
        button.querySelector('span').textContent = expanded ? '질문 힌트 보기' : '질문 힌트 숨기기';
        panel.hidden = expanded;
      });
    });
    document.getElementById('create-live-session').addEventListener('click', createSession);
  }

  function requestCloseSelection() {
    if (!selectionModal) return;
    if (selectionRequiresExitWarning) {
      ask({
        title: '질문 고르기를 닫을까요?',
        body: '만든 질문은 면접 준비 화면의 "만들어 두고 아직 시작하지 않은 질문"에 남아 있어요.',
        confirmLabel: '닫기',
        cancelLabel: '계속 고르기',
        onConfirm: closeSelectionNow
      });
      return;
    }
    closeSelectionNow();
  }

  function closeSelectionNow() {
    if (!selectionModal) return;
    closeSelectionModal(true);
    window.history.replaceState({}, '', window.location.pathname + window.location.search);
    questionSet = null;
    selectionRequiresExitWarning = false;
    if (analysisCaseId) {
      window.location.href = window.JobPuzzleRoutes.path(
        '/analysis-results/' + encodeURIComponent(analysisCaseId)
      );
      return;
    }
    returnHome();
  }

  function closeSelectionModal(fromHistory) {
    if (selectionModal) selectionModal.remove();
    selectionModal = null;
    document.body.classList.remove('has-selection-modal');
    if (fromHistory) selectionHistoryActive = false;
  }

  // 질문 선택을 취소하면 선택용 해시와 분석 진입 파라미터를 현재 주소에서 제거합니다.
  function clearSelectionLocation() {
    var cleanUrl = new URL(window.location.href);
    cleanUrl.searchParams.delete('analysisCaseId');
    cleanUrl.hash = '';
    window.history.replaceState({}, '', cleanUrl.pathname + cleanUrl.search);
  }

  function createSession() {
    var selected = Array.prototype.slice.call(
      (selectionModal || document).querySelectorAll('input[name="question"]:checked')
    )
      .map(function (input) { return Number(input.value); });
    if (!selected.length) {
      notify('warning', '질문을 하나 이상 선택해주세요.');
      return;
    }
    closeSelectionModal(true);
    selectionRequiresExitWarning = false;
    window.history.replaceState({}, '', window.location.pathname + window.location.search);
    message('면접을 준비하고 있습니다.');
    api(window.JobPuzzleRoutes.path('/interview-sessions'), {
      method: 'POST',
      json: { questionSetId: questionSet.questionSetId, selectedQuestionIds: selected }
    }).then(function (created) {
      session = created;
      allowLiveNavigation = false;
      localStorage.setItem('jobpuzzle_interview_session_id', String(session.sessionId));
      window.history.replaceState(
        {},
        '',
        window.JobPuzzleRoutes.path(
          '/interview?resumeSessionId=' + encodeURIComponent(session.sessionId)
        )
      );
      return api(window.JobPuzzleRoutes.path('/interview-sessions/' + session.sessionId + '/questions'));
    }).then(function (items) {
      questions = items;
      currentIndex = 0;
      renderQuestion();
      scrollLiveQuestionIntoView();

    }).catch(function (error) {
      message(error.message, true);
    });
  }

  function resumeSession(active) {
    if (active.status === 'COMPLETED') {
      window.location.replace(window.JobPuzzleRoutes.path(
        '/interview-results?sessionId=' + encodeURIComponent(active.sessionId)
      ));
      return;
    }
    hideActiveSection();
    session = active;
    allowLiveNavigation = false;
    window.history.replaceState(
      {},
      '',
      window.JobPuzzleRoutes.path(
        '/interview?resumeSessionId=' + encodeURIComponent(session.sessionId)
      )
    );
    api(window.JobPuzzleRoutes.path('/interview-sessions/' + session.sessionId + '/questions')).then(function (items) {
      questions = items;
      currentIndex = 0;
      for (var i = 0; i < questions.length; i += 1) {
        if (questions[i].status === 'PENDING' || questions[i].status === 'IN_PROGRESS') {
          currentIndex = i;
          break;
        }
      }
      renderQuestion();
      scrollLiveQuestionIntoView();
    }).catch(function (error) { message(error.message, true); });
  }

  function scrollLiveQuestionIntoView() {
    window.requestAnimationFrame(function () {
      var card = root && root.querySelector('.lv');
      if (!card) return;
      card.scrollIntoView({ behavior: 'smooth', block: 'start' });
    });
  }

  // 이 질문(현재 부모 질문)에 낸 답 중 다시 답하게 된 수: 마지막 추가 질문 이후의 거절·평가 실패 답변
  function usedAttempts(question) {
    var used = 0;
    (question.conversation || []).forEach(function (item) {
      if (item.messageType === 'FOLLOW_UP_QUESTION') used = 0;
      else if (item.messageType === 'REJECTED_ANSWER' || item.messageType === 'EVALUATION_FAILED_ANSWER') used += 1;
    });
    return used;
  }

  function renderQuestion() {
    stopSpeechInput();
    var question = questions[currentIndex];
    if (!question) {
      renderFinish();
      return;
    }
    followUpMessageId = question.pendingFollowUpMessageId || null;
    var hasAnyAnswer = questions.some(function (item) { return item.answerSubmitted; });
    var hasAnotherPendingQuestion = questions.some(function (item, index) {
      return index !== currentIndex &&
        (item.status === 'PENDING' || item.status === 'IN_PROGRESS');
    });
    var finished = questions.filter(function (item) {
      return item.status === 'COMPLETED' || item.status === 'SKIPPED';
    }).length;
    var percent = questions.length ? Math.round(finished / questions.length * 100) : 0;
    var remaining = Math.max(0, MAX_ANSWER_ATTEMPTS - usedAttempts(question));

    var subActions = [];
    if (hasAnotherPendingQuestion) {
      subActions.push('<button type="button" id="skip-current-question" class="lv-text">건너뛰기</button>');
    }
    subActions.push('<button type="button" id="view-current-result" class="lv-text">지금까지 결과 보기</button>');
    if (!hasAnyAnswer) {
      subActions.push('<button type="button" id="finish-live-session" class="lv-text">면접 취소</button>');
    } else if (question.answerSubmitted) {
      subActions.push('<button type="button" id="finish-live-session" class="lv-text">이 질문 마치기</button>');
    }

    // 아직 답하지 않은 추가 질문은 아래 상자에만 보여준다(대화 목록과 두 번 나오지 않게)
    var all = question.conversation || [];
    var lastFollowUp = -1;
    all.forEach(function (item, index) { if (item.messageType === 'FOLLOW_UP_QUESTION') lastFollowUp = index; });
    var conversation = all.filter(function (item, index) {
      if (item.messageType === 'ORIGINAL_QUESTION') return false;
      return !(followUpMessageId && index === lastFollowUp);
    });
    var conversationHtml = conversation.length
      ? '<section class="lv-talk" aria-labelledby="lv-talk-title">' +
        '<h2 id="lv-talk-title">답변 과정</h2><small>실제 면접처럼 이어진 대화예요</small>' +
        conversation.map(function (item) {
          var isUser = item.sender === 'USER';
          var label = item.messageType === 'ORIGINAL_ANSWER' ? '첫 답변'
            : item.messageType === 'REJECTED_ANSWER' ? '다시 답해 달라고 한 답변'
            : item.messageType === 'EVALUATION_FAILED_ANSWER' ? '평가가 멈춘 답변'
            : item.messageType === 'FOLLOW_UP_QUESTION' ? 'AI 추가 질문'
            : '추가 답변';
          var note = item.messageType === 'REJECTED_ANSWER' ? '평가에 넣지 않았어요'
            : item.messageType === 'EVALUATION_FAILED_ANSWER' ? '다시 내면 평가해요' : '';
          return '<div class="lv-turn' + (isUser ? '' : ' lv-turn--ai') + (note ? ' lv-turn--retry' : '') + '">' +
            '<b>' + label + (note ? '<span>' + note + '</span>' : '') + '</b>' +
            '<p>' + esc(item.messageText) + '</p></div>';
        }).join('') + '</section>'
      : '';

    var notice = question.notice || pendingNotice;
    pendingNotice = null;
    question.notice = null;

    root.innerHTML = '<div class="lv">' +
      '<div class="lv-bar"><button type="button" id="leave-live-interview" class="lv-back">' +
      '<span aria-hidden="true">←</span> 면접 준비로 나가기</button>' +
      '<span class="lv-bar__right">' +
      (session.analysisCaseId ? '<a class="lv-link" href="' +
        window.JobPuzzleRoutes.path('/analysis-results/' + encodeURIComponent(session.analysisCaseId)) + '">분석 결과</a>' : '') +
      '<span class="lv-mode">' + esc(modeLabel(session.mode)) + '</span></span></div>' +
      '<div class="lv-progress"><span>질문 <b>' + (currentIndex + 1) + '</b> / ' + questions.length + '</span>' +
      '<div class="lv-progress__bar" role="progressbar" aria-label="답변을 마친 질문" aria-valuemin="0" aria-valuemax="' +
      questions.length + '" aria-valuenow="' + finished + '"><i style="width:' + percent + '%"></i></div>' +
      '<span>' + percent + '%</span></div>' +
      '<section class="lv-q" aria-labelledby="lv-q-text"><span class="lv-q__label">질문 ' + (currentIndex + 1) + '</span>' +
      '<p class="lv-q__text" id="lv-q-text">' + esc(question.questionText) + '</p></section>' +
      conversationHtml +
      (followUpMessageId
        ? '<div class="lv-follow" id="live-follow-up"><b>AI 추가 질문</b><p>' + esc(question.pendingFollowUpQuestion || '') + '</p>' +
          '<small>답변을 조금 더 확인해야 할 때만 이어져요. 여기서 이 질문을 마쳐도 돼요.</small></div>'
        : '') +
      '<div class="lv-answer">' +
      '<label for="live-answer">' + (followUpMessageId ? 'AI 추가 질문에 답하기' : '답변 쓰기') + '</label>' +
      (notice ? '<p class="lv-notice" role="alert">' + esc(notice) + '</p>' : '') +
      '<textarea id="live-answer" aria-describedby="lv-attempts" placeholder="상황, 본인 역할, 행동, 결과를 중심으로 답해 주세요"></textarea>' +
      '<p class="lv-attempts" id="lv-attempts">이 질문에 낼 수 있는 답 <b>' + remaining + '번</b> 남았어요' +
      (remaining < MAX_ANSWER_ATTEMPTS ? '' : ' · 질문과 관계없는 답은 다시 쓰게 돼요') + '</p>' +
      '<div class="lv-voice"><button type="button" id="voice-input-toggle" class="lv-voice__btn" aria-pressed="false">' +
      '<i aria-hidden="true"></i>음성으로 답하기</button>' +
      '<p id="voice-input-status" aria-live="polite">버튼을 누르면 한국어 음성을 글자로 바꿔 드려요.</p></div>' +
      '</div>' +
      '<p class="lv-status" id="lv-status" role="status" aria-live="polite"></p>' +
      '<div class="lv-foot"><div class="lv-sub">' + subActions.join('') + '</div>' +
      '<button type="button" id="submit-live-answer" class="lv-submit">' +
      (followUpMessageId ? '추가 답변 제출' : '답변 제출') + '</button></div></div>';

    document.getElementById('submit-live-answer').addEventListener('click', submitAnswer);
    var finish = document.getElementById('finish-live-session');
    if (finish) finish.addEventListener('click', finishOrCancel);
    var skipQuestion = document.getElementById('skip-current-question');
    if (skipQuestion) skipQuestion.addEventListener('click', skipCurrentQuestion);
    document.getElementById('view-current-result').addEventListener('click', viewCurrentResult);
    document.getElementById('leave-live-interview').addEventListener('click', leaveLiveInterview);
    setupSpeechInput();
  }

  function hasUnsubmittedDraft() {
    var textarea = document.getElementById('live-answer');
    return Boolean(textarea && textarea.value.trim());
  }

  // 작성 중인 답변이 있을 때만 확인하고, 없으면 바로 진행한다(제출한 답은 이미 저장돼 있다)
  function confirmDraftLoss(opts) {
    if (!hasUnsubmittedDraft()) { opts.onConfirm(); return; }
    ask({
      title: opts.title,
      body: '작성 중인 답변은 저장되지 않아요.' + (opts.extra ? ' ' + opts.extra : ''),
      confirmLabel: opts.confirmLabel,
      cancelLabel: '계속 쓰기',
      onConfirm: opts.onConfirm
    });
  }

  function leaveLiveInterview() {
    confirmDraftLoss({
      title: '면접 준비로 나갈까요?',
      extra: '면접은 나중에 이어서 할 수 있어요.',
      confirmLabel: '나가기',
      onConfirm: function () {
        stopSpeechInput();
        allowLiveNavigation = true;
        window.location.href = window.JobPuzzleRoutes.path('/interview');
      }
    });
  }

  function skipCurrentQuestion() {
    var question = questions[currentIndex];
    if (!question) return;
    confirmDraftLoss({
      title: '이 질문을 건너뛸까요?',
      extra: '건너뛴 질문은 다른 질문을 마친 뒤 다시 나와요.',
      confirmLabel: '건너뛰기',
      onConfirm: function () { deferQuestion(question); }
    });
  }

  function deferQuestion(question) {
    stopSpeechInput();
    api(window.JobPuzzleRoutes.path('/interview-session-questions/' +
      question.sessionQuestionId + '/defer'), { method: 'POST' }).then(function () {
      question.status = 'PENDING';
      for (var offset = 1; offset < questions.length; offset += 1) {
        var index = (currentIndex + offset) % questions.length;
        if (questions[index].status === 'PENDING' || questions[index].status === 'IN_PROGRESS') {
          currentIndex = index;
          renderQuestion();
          return;
        }
      }
      // 마지막 남은 질문도 결과 화면으로 보내지 않고 다시 이어서 답한다.
      renderQuestion();
    }).catch(function (error) {
      notify('error', error.message);
    });
  }

  function submitAnswer() {
    stopSpeechInput();
    var textarea = document.getElementById('live-answer');
    var text = textarea.value.trim();
    if (!text) return;
    var button = document.getElementById('submit-live-answer');
    var status = document.getElementById('lv-status');
    button.disabled = true;
    button.textContent = '평가하는 중';
    if (status) status.textContent = 'AI가 답변을 평가하고 있어요. 잠시만 기다려 주세요.';
    var liveCard = root.querySelector('.lv');
    if (liveCard) liveCard.classList.add('is-evaluating');
    textarea.disabled = true;
    api(window.JobPuzzleRoutes.path('/interview-session-questions/' + questions[currentIndex].sessionQuestionId + '/answers'), {
      method: 'POST',
      json: {
        messageText: text,
        answerType: followUpMessageId ? 'FOLLOW_UP_ANSWER' : 'ORIGINAL_ANSWER',
        parentQuestionMessageId: followUpMessageId
      }
    }).then(function (result) {
      questions[currentIndex].conversation = questions[currentIndex].conversation || [];
      if (result.answerAttemptsExhausted) {
        questions[currentIndex].conversation.push({
          sender: 'USER',
          messageType: result.failureTraceId
            ? 'EVALUATION_FAILED_ANSWER'
            : 'REJECTED_ANSWER',
          messageText: text
        });
        questions[currentIndex].answerSubmitted = true;
        questions[currentIndex].evaluationFailed = true;
        questions[currentIndex].status = 'COMPLETED';
        questions[currentIndex].pendingFollowUpMessageId = null;
        questions[currentIndex].pendingFollowUpQuestion = null;
        followUpMessageId = null;
        pendingNotice = (result.summary || '이 질문은 답을 3번 모두 써서 평가 없이 마쳤어요.') +
          ' 다음 질문으로 넘어왔어요.';
        moveToNextPendingQuestion();
        return;
      }
      if (result.evaluationRetryRequired) {
        questions[currentIndex].conversation.push({
          sender: 'USER',
          messageType: 'EVALUATION_FAILED_ANSWER',
          messageText: text
        });
        questions[currentIndex].answerSubmitted = true;
        questions[currentIndex].evaluationFailed = true;
        questions[currentIndex].evaluationRetryDraft = text;
        questions[currentIndex].notice = result.evaluationRetryMessage ||
          '평가가 잠시 멈췄어요. 쓴 답변은 그대로 두었으니 잠시 후 다시 제출해 주세요.';
        renderQuestion();
        var retryTextarea = document.getElementById('live-answer');
        if (retryTextarea) {
          retryTextarea.value = text;
          retryTextarea.focus();
        }
        return;
      }
      if (result.retryAnswerRequired) {
        questions[currentIndex].conversation.push({
          sender: 'USER',
          messageType: 'REJECTED_ANSWER',
          messageText: text
        });
        questions[currentIndex].notice =
          result.retryAnswerMessage || result.summary || '질문에 맞는 경험이나 생각으로 다시 답해 주세요.';
        renderQuestion();
        return;
      }
      questions[currentIndex].answerSubmitted = true;
      questions[currentIndex].conversation.push({
        sender: 'USER',
        messageType: followUpMessageId ? 'FOLLOW_UP_ANSWER' : 'ORIGINAL_ANSWER',
        messageText: text
      });
      if (result.evaluationFailed) {
        questions[currentIndex].evaluationFailed = true;
        notify('warning', result.summary ||
          '평가 처리에 문제가 생겼습니다. 답변은 저장됐으니 잠시 후 다시 제출해 주세요.');
      }
      if (result.followUpQuestion) {
        followUpMessageId = result.followUpQuestionMessageId;
        questions[currentIndex].pendingFollowUpMessageId = result.followUpQuestionMessageId;
        questions[currentIndex].pendingFollowUpQuestion = result.followUpQuestion;
        questions[currentIndex].followUpCount = (questions[currentIndex].followUpCount || 0) + 1;
        questions[currentIndex].conversation.push({
          sender: 'AI',
          messageType: 'FOLLOW_UP_QUESTION',
          messageText: result.followUpQuestion
        });
        renderQuestion();
        return;
      }
      followUpMessageId = null;
      questions[currentIndex].status = 'COMPLETED';
      questions[currentIndex].pendingFollowUpMessageId = null;
      questions[currentIndex].pendingFollowUpQuestion = null;
      moveToNextPendingQuestion();
    }).catch(function (error) {
      button.disabled = false;
      button.textContent = '다시 제출';
      textarea.disabled = false;
      if (liveCard) liveCard.classList.remove('is-evaluating');
      if (status) status.textContent = '';
      notify('error', error.message);
    });
  }

  function renderFinish() {
    if (!session) return;
    stopSpeechInput();
    allowLiveNavigation = true;
    window.location.href = window.JobPuzzleRoutes.path('/interview-results?sessionId=' +
      encodeURIComponent(session.sessionId) + '&interim=true');
  }

  function completeSession() {
    if (!session || completing) return;
    stopSpeechInput();
    completing = true;
    root.innerHTML = '<div class="card card--pad-lg live-completing">' +
      '<span class="live-completing__pulse" aria-hidden="true"></span>' +
      '<div><strong>면접 결과를 정리하고 있습니다</strong>' +
      '<p>답변 평가와 관점별 점수를 집계한 뒤 결과 화면으로 바로 이동합니다.</p></div></div>';
    api(window.JobPuzzleRoutes.path('/interview-sessions/' + session.sessionId + '/complete'), { method: 'POST' })
      .then(function () {
        allowLiveNavigation = true;
        window.location.href = window.JobPuzzleRoutes.path('/interview-results?sessionId=' + encodeURIComponent(session.sessionId));
      }).catch(function (error) {
        completing = false;
        message(error.message, true);
      });
  }

  function finishOrCancel() {
    if (!session) return;
    var hasAnyAnswer = questions.some(function (item) { return item.answerSubmitted; });
    if (!hasAnyAnswer) {
      ask({
        title: '면접을 취소할까요?',
        body: '아직 낸 답변이 없어서 기록은 남지 않아요. 고른 질문은 다시 만들어야 해요.',
        confirmLabel: '면접 취소',
        cancelLabel: '계속 하기',
        onConfirm: cancelSessionNow
      });
      return;
    }
    var currentQuestion = questions[currentIndex];
    if (!currentQuestion || !currentQuestion.answerSubmitted) return;
    ask({
      title: '이 질문을 마칠까요?',
      body: '지금까지 낸 답으로 평가하고 다음 질문으로 넘어가요.' +
        (hasUnsubmittedDraft() ? ' 작성 중인 답변은 저장되지 않아요.' : ''),
      confirmLabel: '이 질문 마치기',
      cancelLabel: '계속 쓰기',
      onConfirm: function () { finishCurrentQuestion(currentQuestion); }
    });
  }

  function finishCurrentQuestion(currentQuestion) {
    api(window.JobPuzzleRoutes.path(
      '/interview-session-questions/' + currentQuestion.sessionQuestionId + '/finish-current'
    ), { method: 'POST' }).then(function () {
      currentQuestion.status = 'COMPLETED';
      currentQuestion.pendingFollowUpMessageId = null;
      currentQuestion.pendingFollowUpQuestion = null;
      followUpMessageId = null;
      moveToNextPendingQuestion();
    }).catch(function (error) {
      notify('error', error.message);
    });
  }

  function cancelSessionNow() {
    message('면접을 취소하고 있어요.');
    api(window.JobPuzzleRoutes.path('/interview-sessions/' + session.sessionId + '/cancel'), { method: 'POST' })
      .then(function () {
        allowLiveNavigation = true;
        localStorage.removeItem('jobpuzzle_interview_session_id');
        window.location.href = window.JobPuzzleRoutes.path('/interview');
      })
      .catch(function (error) { message(error.message, true); });
  }

  function viewCurrentResult() {
    if (!session) return;
    // 현재 질문을 완료 처리하지 않고, 저장된 답변 현황만 중간 결과에서 조회한다.
    confirmDraftLoss({
      title: '지금까지 결과를 볼까요?',
      extra: '면접은 결과 화면에서 이어서 할 수 있어요.',
      confirmLabel: '결과 보기',
      onConfirm: renderFinish
    });
  }

  function moveToNextPendingQuestion() {
    for (var offset = 1; offset <= questions.length; offset += 1) {
      var index = (currentIndex + offset) % questions.length;
      if (questions[index].status === 'PENDING' || questions[index].status === 'IN_PROGRESS') {
        currentIndex = index;
        renderQuestion();
        return;
      }
    }
    renderFinish();
  }

  function renderActiveSessions(items) {
    activeSessions = items || [];
    if (activeSection) activeSection.remove();
    activeSection = null;
    if (!items || !items.length || analysisCaseId) {
      mountSection('ACTIVE_INTERVIEW', null);
      return;
    }
    activeSection = document.createElement('section');
    activeSection.className = 'active-session-section';
    activeSection.innerHTML =
      '<div class="active-session-heading"><div><h2>진행 중인 면접</h2>' +
      '<p>새 모드를 선택하거나, 저장된 위치에서 이어갈 수 있습니다.</p></div>' +
      '<span class="session-status-count is-interview">' + items.length + '개 진행 중</span></div>' +
      '<div class="active-session-list">' + pageItems(items, 'activeSessions').map(function (item) {
        var activityAt = item.lastActivityAt || item.createdAt;
        var created = activityAt ? new Date(activityAt).toLocaleString('ko-KR', {
          month: 'long', day: 'numeric', hour: '2-digit', minute: '2-digit'
        }) : '';
        var questionOrder = item.currentQuestionOrder || 1;
        var qaDepth = item.currentQaDepth || 0;
        var qaLabel = qaDepth === 0 ? '첫 답변 작성 전'
          : qaDepth === 1 ? '첫 답변 완료 · 추가 질문 확인 단계'
            : qaDepth === 2 ? '첫 번째 추가 답변 완료'
              : '두 번째 추가 답변 완료 · 결과 확인 가능';
        var progress = item.questionCount
          ? Math.round((item.completedQuestionCount || 0) / item.questionCount * 100)
          : 0;
        return '<article class="active-session-card mode-' + String(item.mode).toLowerCase().replace('_', '-') +
          '" tabindex="0">' +
          '<div class="session-mode-icon" aria-hidden="true">' + modeIcon(item.mode) + '</div>' +
          '<div class="active-session-main"><span class="active-session-mode session-mode-badge">' +
          esc(modeLabel(item.mode)) + '</span>' +
          (item.targetWeaknessTag ? '<span class="active-session-weakness-badge">#' +
          esc(weaknessLabel(item.targetWeaknessTag)) + '</span>' : '') +
          '<h3>' + esc(item.questionCount) + '개 질문으로 진행 중</h3>' +
          '<p>' + esc(created) + '</p></div>' +
          '<div class="active-session-actions">' +
          (item.analysisCaseId ? '<a class="btn btn--outline" href="' +
          window.JobPuzzleRoutes.path('/analysis-results/' + encodeURIComponent(item.analysisCaseId)) + '">분석 결과 보기</a>' : '') +
          '<button class="btn btn--primary" data-resume-session="' +
          item.sessionId + '">이어서 진행</button>' +
          (item.answerSubmitted ? '' : '<button class="btn btn--ghost" data-cancel-session="' +
          item.sessionId + '">답변 전 취소</button>') + '</div>' +
          '<div class="active-session-insight" role="status">' +
          '<div class="active-session-progress-head"><strong>현재 진행 위치</strong><span>' +
          progress + '%</span></div><div class="active-session-progress"><i style="width:' +
          progress + '%"></i></div><dl><div><dt>질문</dt><dd>' + questionOrder + ' / ' +
          item.questionCount + '</dd></div><div><dt>답변 상태</dt><dd>' + esc(qaLabel) +
          '</dd></div><div><dt>진행 상태</dt><dd>' + (progress >= 100 ? '답변 완료' : '면접 진행 중') +
          '</dd></div></dl></div></article>';
      }).join('') + '</div>' + paginationHtml(items, 'activeSessions');
    mountSection('ACTIVE_INTERVIEW', activeSection);
    activeSection.querySelectorAll('[data-resume-session]').forEach(function (button) {
      button.addEventListener('click', function () {
        var selected = items.find(function (item) {
          return String(item.sessionId) === button.dataset.resumeSession;
        });
        resumeSession(selected);
      });
    });
    activeSection.querySelectorAll('[data-cancel-session]').forEach(function (button) {
      button.addEventListener('click', function () {
        if (!window.confirm('답변을 제출하지 않은 세션만 취소할 수 있습니다. 취소할까요?')) return;
        api(window.JobPuzzleRoutes.path('/interview-sessions/' + button.dataset.cancelSession + '/cancel'), { method: 'POST' })
          .then(function () { activeSection.remove(); activeSection = null; loadActiveSessions(); })
          .catch(function (error) { notify('error', error.message); });
      });
    });
    bindPagination(activeSection, 'activeSessions', function () {
      renderActiveSessions(activeSessions);
    });
  }

  function loadActiveSessions() {
    Promise.all([
      api(window.JobPuzzleRoutes.path('/interview-sessions/active-list')),
      api(window.JobPuzzleRoutes.path('/interview-sessions/review-list'))
    ]).then(function (values) {
      renderActiveSessions(values[0]);
      renderReviewReadySessions(values[1]);
    }).catch(function () {});
  }

  function renderReviewReadySessions(items) {
    if (reviewSection) reviewSection.remove();
    reviewSection = null;
    if (!items || !items.length || analysisCaseId) {
      mountSection('REVIEW_INTERVIEW', null);
      return;
    }
    reviewSection = document.createElement('section');
    reviewSection.className = 'review-ready-section';
    reviewSection.innerHTML =
      '<div class="active-session-heading"><div><h2>결과 검토 중인 면접</h2>' +
      '<p>선택한 질문의 답변은 끝났습니다. 남은 질문을 더 연습하거나 현재 결과를 확정할 수 있어요.</p></div>' +
      '<span class="session-status-count is-review">' + items.length + '개 검토 중</span></div><div class="review-ready-list">' +
      pageItems(items, 'reviewSessions').map(function (item) {
        var reviewAt = item.lastEvaluatedAt || item.lastActivityAt || item.createdAt;
        var created = reviewAt ? new Date(reviewAt).toLocaleString('ko-KR') : '';
        return '<article class="review-ready-card mode-' +
          String(item.mode).toLowerCase().replace('_', '-') + '"><div class="session-mode-icon" aria-hidden="true">' +
          modeIcon(item.mode) + '</div><div><span class="session-mode-badge">' + esc(modeLabel(item.mode)) + '</span>' +
          '</span><h3>' + esc(item.completedQuestionCount) + '개 질문 답변 완료</h3><p>' +
          esc(created) + '</p></div><a class="btn btn--primary" href="' +
          window.JobPuzzleRoutes.path('/interview-results?sessionId=' + encodeURIComponent(item.sessionId)) +
          '">중간 결과 확인</a></article>';
      }).join('') + '</div>' + paginationHtml(items, 'reviewSessions');
    mountSection('REVIEW_INTERVIEW', reviewSection);
    bindPagination(reviewSection, 'reviewSessions', function () {
      renderReviewReadySessions(items);
    });
    selectionModal.querySelectorAll('.question-hint-toggle').forEach(function (button) {
      button.addEventListener('click', function (event) {
        event.preventDefault();
        event.stopPropagation();
        var panel = button.nextElementSibling;
        var expanded = button.getAttribute('aria-expanded') === 'true';
        button.setAttribute('aria-expanded', String(!expanded));
        button.querySelector('span').textContent = expanded ? '질문 힌트 보기' : '질문 힌트 접기';
        panel.hidden = expanded;
      });
    });
  }

  function analysisStatusLabel(status) {
    if (status === 'ANALYZING') return '분석 진행 중';
    if (status === 'FAILED') return '분석 확인 필요';
    return '분석 시작 전';
  }

  function analysisStageLabel(stage) {
    var labels = {
      JOB_POSTING_ANALYSIS: '채용공고 분석',
      CANDIDATE_MATERIAL_ANALYSIS: '지원자 자료 분석',
      GUIDE_CONTEXT_BUILD: '직무 가이드 적용',
      CUSTOMIZED_SYNTHESIS: '맞춤 결과 생성'
    };
    return labels[stage] || '맞춤 결과 생성';
  }

  function analysisStages(item) {
    return [
      { key: 'jobPostingAnalysisStatus', label: '채용공고 분석' },
      { key: 'candidateMaterialAnalysisStatus', label: '지원자 자료 분석' },
      { key: 'guideContextStatus', label: '직무 가이드 적용' },
      { key: 'customizedAnalysisStatus', label: '맞춤 결과 생성' }
    ].map(function (stage) {
      return {
        label: stage.label,
        status: item[stage.key] || 'NOT_STARTED'
      };
    });
  }

  function renderActiveAnalyses(items) {
    if (analysisSection) analysisSection.remove();
    if (!items.length || analysisCaseId) {
      analysisSection = null;
      mountSection('ACTIVE_ANALYSIS', null);
      return;
    }
    analysisSection = document.createElement('section');
    analysisSection.className = 'active-analysis-section';
    analysisSection.innerHTML =
      '<div class="active-session-heading"><div><h2>진행 중인 맞춤 분석</h2>' +
      '<p>자료를 분석하는 동안 다른 화면을 이용해도 괜찮아요.</p></div>' +
      '<span class="session-status-count is-analyzing">' + items.length + '개 확인 중</span></div>' +
      '<div class="active-analysis-list">' + pageItems(items, 'activeAnalyses').map(function (item) {
        var label = [item.mainCategory, item.subCategory].filter(Boolean).join(' · ') || '맞춤 면접';
        var steps = analysisStages(item);
        var completed = steps.filter(function (stage) { return stage.status === 'SUCCEEDED'; }).length;
        var runningIndex = steps.findIndex(function (stage) { return stage.status === 'RUNNING'; });
        var currentIndex = runningIndex >= 0 ? runningIndex : Math.min(completed, steps.length - 1);
        var current = steps[currentIndex];
        var progress = item.status === 'INPUT_CONFIRMED' ? 0 : Math.round((completed + (runningIndex >= 0 ? .45 : 0)) / steps.length * 100);
        var detail = steps.map(function (stage, index) {
          var stateClass = stage.status === 'SUCCEEDED' ? 'is-done' :
            stage.status === 'RUNNING' ? 'is-running' : '';
          return '<li class="' + stateClass + '"><i>' + (index + 1) + '</i><span>' +
            esc(stage.label) + '</span></li>';
        }).join('');
        return '<article class="active-analysis-card ' + (item.status === 'INPUT_CONFIRMED' ? 'is-waiting' : '') + '">' +
          '<div class="active-analysis-visual"><i></i><span>AI</span></div>' +
          '<div class="active-analysis-copy"><span>' + analysisStatusLabel(item.status) + '</span>' +
          '<h3>' + esc(label) + '</h3><p><strong>' + esc(current.label) + '</strong> 단계입니다. ' +
          (item.status === 'INPUT_CONFIRMED' ? '분석 시작을 기다리고 있어요.' : '자료와 근거를 차근차근 확인하고 있어요.') + '</p>' +
          '<ol class="active-analysis-stages">' + detail + '</ol>' +
          '<div class="active-analysis-progress" aria-label="분석 진행률 ' + progress + '%"><i style="width:' + progress + '%"></i></div></div>' +
          '<a class="btn btn--primary" href="' +
          window.JobPuzzleRoutes.path('/analysis/' + item.analysisCaseId) + '">상태 확인</a></article>';
      }).join('') + '</div>' + paginationHtml(items, 'activeAnalyses');
    mountSection('ACTIVE_ANALYSIS', analysisSection);
    bindPagination(analysisSection, 'activeAnalyses', function () {
      renderActiveAnalyses(items);
    });
  }

  function renderAnalysisAttention(items) {
    if (analysisAttentionSection) analysisAttentionSection.remove();
    analysisAttentionSection = null;
    if (!items.length || analysisCaseId) return;

    analysisAttentionSection = document.createElement('section');
    analysisAttentionSection.className = 'analysis-attention-section';
    analysisAttentionSection.innerHTML =
      '<div class="active-session-heading"><div><h2>분석 확인 필요</h2>' +
      '<p>멈춘 분석입니다. 선택한 자료와 앞선 분석 결과는 보존되어 있어요.</p></div>' +
      '<span class="session-status-count is-attention">' + items.length + '개 확인 필요</span></div>' +
      '<div class="analysis-attention-list">' + pageItems(items, 'failedAnalyses').map(function (item) {
        var label = [item.mainCategory, item.subCategory].filter(Boolean).join(' · ') || '맞춤 면접';
        return '<article class="analysis-attention-card">' +
          '<div class="analysis-attention-visual" aria-hidden="true">!</div>' +
          '<div class="analysis-attention-copy"><span>' + esc(analysisStageLabel(item.latestFailureStage)) + ' 단계 확인 필요</span>' +
          '<h3>' + esc(label) + '</h3><p>' + esc(item.userMessage || '분석 결과를 생성하지 못했어요.') + '</p>' +
          '<small>자료를 다시 선택하지 않아도 상태 화면에서 다시 실행할 수 있습니다.</small></div>' +
          '<a class="btn btn--outline" href="' +
          window.JobPuzzleRoutes.path('/analysis/' + encodeURIComponent(item.analysisCaseId)) +
          '">상태 확인</a></article>';
      }).join('') + '</div>' + paginationHtml(items, 'failedAnalyses');
    var anchor = analysisSection || activeSection || root;
    anchor.insertAdjacentElement('afterend', analysisAttentionSection);
    bindPagination(analysisAttentionSection, 'failedAnalyses', function () {
      renderAnalysisAttention(items);
    });
  }

  function preparedWeaknessHistory(detail) {
    function date(value) {
      if (!value) return '';
      var parsed = new Date(value);
      return String(parsed.getMonth() + 1).padStart(2, '0') + '.' +
        String(parsed.getDate()).padStart(2, '0');
    }
    function diagnostics(values) {
      return values && values.length
        ? '<div class="weak-history-diagnostics"><em>세부 진단</em><div>' +
          values.map(function (value) {
            return '<span>' + esc(koreanizeEvaluationTerms(weaknessLabel(value))) + '</span>';
          }).join('') + '</div></div>'
        : '';
    }
    function report(sessionId, label) {
      return sessionId
        ? '<a class="weak-history-report" href="' +
          window.JobPuzzleRoutes.path('/interview-results?sessionId=' + encodeURIComponent(sessionId)) +
          '">' + esc(label) + '</a>'
        : '';
    }
    var occurrences = detail.recentOccurrences || [];
    return '<div class="weak-pick-history prepared-question-history"><header><span>보완 기록</span>' +
      '<strong>' + esc(detail.displayName) + ' 질문 생성 근거</strong>' +
      '<p>이 질문을 만들 당시 실제로 참고한 약점 이력만 표시합니다.</p></header>' +
      '<section class="weak-history-group"><h4>생성 당시 참고한 면접 <b>' +
      esc(occurrences.length) + '</b></h4>' +
      (occurrences.length ? occurrences.map(function (occurrence) {
        var mode = occurrence.mode === 'WEAKNESS_REVIEW' ? '약점 보완' :
          occurrence.mode === 'BASIC' ? '기본 면접' : '맞춤 면접';
        var attempts = (occurrence.attempts || []).map(function (attempt) {
          return '<div class="weak-history-attempt"><div class="weak-history-branch">└</div>' +
            '<div class="weak-history-content"><div class="weak-history-summary"><b>' +
            esc(date(attempt.occurredAt)) + '</b><span>약점 보완</span>' +
            (attempt.score == null ? '' : '<strong>' + esc(attempt.score) + '점</strong>') +
            '<i class="' + (attempt.status === 'RESOLVED' ? 'is-resolved' : 'is-unresolved') +
            '">' + (attempt.status === 'RESOLVED' ? '해결' : '미해결') + '</i></div>' +
            diagnostics(attempt.diagnostics) +
            report(attempt.sessionId, attempt.resultLabel || '약점 보완 리포트 보기') +
            '</div></div>';
        }).join('');
        return '<article class="weak-history-origin"><div class="weak-history-content">' +
          '<div class="weak-history-summary"><b>' + esc(date(occurrence.occurredAt)) +
          '</b><span>' + esc(mode) + '</span>' +
          (occurrence.score == null ? '' : '<strong>' + esc(occurrence.score) + '점</strong>') +
          '<i class="' + (occurrence.status === 'RESOLVED' ? 'is-resolved' : 'is-unresolved') + '">' +
          (occurrence.status === 'RESOLVED' ? '해결' : '미해결') + '</i></div>' +
          diagnostics(occurrence.diagnostics) +
          report(occurrence.sessionId, occurrence.resultLabel || mode + ' 리포트 보기') +
          '</div>' + attempts + '</article>';
      }).join('') : '<p class="prepared-detail-empty">저장된 생성 근거 이력이 없습니다.</p>') +
      '</section></div>';
  }

  function preparedCareerLabel(value) {
    return {
      NEW: '신입',
      EXPERIENCED: '경력',
      ANY: '경력 무관'
    }[value] || value || '경력 무관';
  }

  function preparedMaterialDetails(sources) {
    var labels = {
      JOB_POSTING: '채용공고',
      COMPANY_INFO: '회사정보',
      RESUME: '이력서',
      COVER_LETTER: '자기소개서',
      PORTFOLIO: '포트폴리오',
      EXPERIENCE_NOTE: '경험 자료'
    };
    var order = Object.keys(labels);
    var grouped = (sources || []).reduce(function (result, source) {
      var type = source.documentType || 'OTHER';
      if (!result[type]) result[type] = [];
      if (source.displayName) result[type].push(source.displayName);
      return result;
    }, {});
    var groups = order.filter(function (type) {
      return grouped[type] && grouped[type].length;
    }).map(function (type) {
      return '<div class="prepared-material-group"><strong>' + esc(labels[type]) + '</strong><div>' +
        grouped[type].map(function (name) {
          return '<span>' + esc(name) + '</span>';
        }).join('') + '</div></div>';
    }).join('');
    return '<div class="prepared-material-detail"><header><span>분석 자료</span>' +
      '<strong>질문 생성에 사용한 자료</strong><p>맞춤 분석을 준비할 때 선택한 자료입니다.</p></header>' +
      '<section>' + (groups || '<p class="prepared-detail-empty">연결된 자료가 없습니다.</p>') +
      '</section></div>';
  }

  function renderPreparedQuestionItems() {
    var items = preparedQuestionSets.map(function (item) {
      return {
        kind: 'QUESTION_SET',
        questionSetId: item.questionSetId,
        mode: item.mode,
        targetWeaknessTag: item.targetWeaknessTag,
        targetWeaknessDisplayName: item.targetWeaknessDisplayName,
        displayTitle: item.displayTitle,
        mainCategory: item.mainCategory,
        subCategory: item.subCategory,
        careerLevel: item.careerLevel,
        recentOccurrences: item.recentOccurrences || [],
        questionCount: item.questionCount,
        createdAt: item.createdAt
      };
    }).concat(preparedAnalyses.map(function (item) {
      item.kind = 'ANALYSIS';
      return item;
    })).sort(function (left, right) {
      return new Date(right.createdAt || 0) - new Date(left.createdAt || 0);
    });
    if (preparedAnalysisSection) preparedAnalysisSection.remove();
    preparedAnalysisSection = null;
    if (!items.length || analysisCaseId) {
      mountSection('PREPARED_QUESTION', null);
      return;
    }
    preparedAnalysisSection = document.createElement('section');
    preparedAnalysisSection.className = 'prepared-analysis-section';
    preparedAnalysisSection.innerHTML =
      '<div class="active-session-heading"><div><h2>면접 질문이 준비된 항목</h2>' +
      '<p>생성된 질문을 확인하고 원하는 질문을 선택해 면접을 시작할 수 있어요.</p></div>' +
      '<span class="session-status-count is-prepared prepared-analysis-count">' + items.length + '개 준비됨</span></div>' +
      '<div class="prepared-analysis-list">' + pageItems(items, 'preparedAnalyses').map(function (item) {
        if (item.kind === 'QUESTION_SET') {
          var isWeakness = item.mode === 'WEAKNESS_REVIEW';
          var mode = isWeakness ? '약점 보완' : '기본 질문';
          var title = item.displayTitle || (isWeakness
            ? item.targetWeaknessDisplayName
            : [item.mainCategory, item.subCategory, item.careerLevel].filter(Boolean).join(' · '));
          var generated = item.createdAt ? new Date(item.createdAt).toLocaleString('ko-KR') : '';
          var recentHistory = isWeakness ? preparedWeaknessHistory({
            displayName: item.targetWeaknessDisplayName || title,
            recentOccurrences: item.recentOccurrences
          }) : '';
          return '<article class="prepared-analysis-card prepared-analysis-card--' +
            (isWeakness ? 'weakness' : 'basic') + '"' +
            (isWeakness ? ' data-prepared-expandable tabindex="0" aria-expanded="false"' : '') +
            '><div class="prepared-analysis-check" aria-hidden="true">' + modeIcon(item.mode) + '</div>' +
            '<div class="prepared-analysis-copy"><span class="session-mode-badge">' + mode + '</span><h3>' +
            esc(title) + '</h3><p>' + esc(generated) + ' · 질문 ' +
            esc(item.questionCount) + '개</p></div>' +
            '<div class="prepared-analysis-actions"><button type="button" class="btn btn--primary" ' +
            'data-open-question-set="' + item.questionSetId + '">질문 보기</button></div>' +
            recentHistory + '</article>';
        }
        var label = [item.mainCategory, item.subCategory].filter(Boolean).join(' · ') || '맞춤 면접';
        var created = item.createdAt ? new Date(item.createdAt).toLocaleString('ko-KR') : '';
        var career = preparedCareerLabel(item.careerLevel);
        return '<article class="prepared-analysis-card prepared-analysis-card--company-fit" ' +
          'data-prepared-expandable tabindex="0" aria-expanded="false">' +
          '<div class="prepared-analysis-check" aria-hidden="true">' + modeIcon('COMPANY_FIT') + '</div>' +
          '<div class="prepared-analysis-copy"><span class="session-mode-badge">맞춤 면접</span><h3>' + esc(label) +
          '<b class="prepared-career-label"> · ' + esc(career) + '</b></h3>' +
          '<p>' + esc(created) + '</p></div>' +
          '<div class="prepared-analysis-actions"><a class="btn btn--outline" href="' +
          window.JobPuzzleRoutes.path('/analysis-results/' + encodeURIComponent(item.analysisCaseId)) +
          '">분석 결과 보기</a>' +
          '<a class="btn btn--primary" href="' +
          window.JobPuzzleRoutes.path('/interview?analysisCaseId=' + encodeURIComponent(item.analysisCaseId)) +
          '">질문 보기</a></div>' + preparedMaterialDetails(item.sources) + '</article>';
      }).join('') + '</div>' + paginationHtml(items, 'preparedAnalyses');
    mountSection('PREPARED_QUESTION', preparedAnalysisSection);
    bindPagination(preparedAnalysisSection, 'preparedAnalyses', function () {
      renderPreparedQuestionItems();
    });
    preparedAnalysisSection.querySelectorAll('[data-open-question-set]').forEach(function (button) {
      button.addEventListener('click', function () {
        message('저장된 질문을 불러오고 있습니다.');
        api(window.JobPuzzleRoutes.path(
          '/question-sets/' + encodeURIComponent(button.dataset.openQuestionSet)
        ))
          .then(function (set) {
            questionSet = set;
            selectionRequiresExitWarning = false;
            renderSelection();
          })
          .catch(function (error) { message(error.message, true); });
      });
    });
    preparedAnalysisSection.querySelectorAll('[data-prepared-expandable]').forEach(function (card) {
      function toggleCard() {
        var willOpen = !card.classList.contains('is-expanded');
        preparedAnalysisSection.querySelectorAll('[data-prepared-expandable].is-expanded')
          .forEach(function (opened) {
            opened.classList.remove('is-expanded');
            opened.setAttribute('aria-expanded', 'false');
          });
        if (willOpen) {
          card.classList.add('is-expanded');
          card.setAttribute('aria-expanded', 'true');
        }
      }
      card.addEventListener('click', function (event) {
        if (event.target.closest('.prepared-analysis-actions, .prepared-question-history, .prepared-material-detail')) return;
        toggleCard();
      });
      card.addEventListener('keydown', function (event) {
        if (event.key !== 'Enter' && event.key !== ' ') return;
        if (event.target.closest('.prepared-analysis-actions, .prepared-question-history, .prepared-material-detail')) return;
        event.preventDefault();
        toggleCard();
      });
    });
  }

  function loadActiveAnalyses() {
    var stored;
    try { stored = JSON.parse(localStorage.getItem('jobpuzzle_analysis_cases') || '[]'); }
    catch (ignore) { stored = []; }
    if (!stored.length) return;
    Promise.all(stored.map(function (item) {
      return api(window.JobPuzzleRoutes.path('/analysis/cases/' + item.analysisCaseId + '/status'))
        .then(function (status) {
        item.status = status.analysisCaseStatus;
        item.latestFailureStage = status.latestFailureStage;
        item.userMessage = status.userMessage;
        item.questionSetAvailable = status.questionSetAvailable;
        item.jobPostingAnalysisStatus = status.jobPostingAnalysisStatus;
        item.candidateMaterialAnalysisStatus = status.candidateMaterialAnalysisStatus;
        item.guideContextStatus = status.guideContextStatus;
        item.customizedAnalysisStatus = status.customizedAnalysisStatus;
        return item;
        }).catch(function () { return null; });
    })).then(function (items) {
      var activeItems = items.filter(function (item) {
        return item && (item.status === 'INPUT_CONFIRMED' || item.status === 'ANALYZING');
      });
      var failedItems = items.filter(function (item) {
        return item && item.status === 'FAILED';
      });
      localStorage.setItem(
        'jobpuzzle_analysis_cases',
        JSON.stringify(activeItems.concat(failedItems))
      );
      renderActiveAnalyses(activeItems);
      renderAnalysisAttention(failedItems);
    });
  }

  function loadPreparedAnalysesFromServer() {
    api(window.JobPuzzleRoutes.path('/analysis-cases/completed')).then(function (cases) {
      return Promise.all((cases || []).map(function (item) {
        return api(window.JobPuzzleRoutes.path('/analysis/cases/' + item.analysisCaseId + '/status'))
          .then(function (status) {
            item.questionSetAvailable = status.questionSetAvailable;
            return item;
          })
          .catch(function () { return null; });
      }));
    }).then(function (items) {
      preparedAnalyses = items.filter(function (item) {
        return item && item.questionSetAvailable;
      });
      renderPreparedQuestionItems();
    }).catch(function () {});
  }

  function loadPreparedQuestionItems() {
    Promise.all([
      api(window.JobPuzzleRoutes.path('/question-sets/prepared')),
      api(window.JobPuzzleRoutes.path('/interview-weakness-tags/details')).catch(function () { return []; })
    ]).then(function (result) {
      preparedQuestionSets = result[0] || [];
      weaknessTagDetails = result[1] || [];
      renderPreparedQuestionItems();
    }).catch(function () {});
  }

  document.addEventListener('DOMContentLoaded', function () {
    root = document.getElementById('step-root');
    if (analysisCaseId) {
      load();
    } else if (resumeSessionId) {
      api(window.JobPuzzleRoutes.path('/interview-sessions/' + resumeSessionId))
        .then(resumeSession)
        .catch(function (error) { message(error.message, true); });
    } else if (requestedMode === 'weakness') {
      window.setTimeout(function () {
        if (window.InterviewPage) window.InterviewPage.openWeakness();
      }, 0);
    }
  });
  window.addEventListener('popstate', function () {
    if (!selectionModal) return;
    if (selectionRequiresExitWarning) {
      // 뒤로 가기는 이미 일어났으므로 선택 화면 주소를 되돌려 놓고 확인한다
      window.history.pushState({ interviewSelection: true }, '', '#question-selection');
      requestCloseSelection();
      return;
    }
    closeSelectionModal(true);
    questionSet = null;
    selectionRequiresExitWarning = false;
    if (analysisCaseId) {
      window.location.replace(window.JobPuzzleRoutes.path(
        '/analysis-results/' + encodeURIComponent(analysisCaseId)
      ));
    } else {
      returnHome();
    }
  });
  document.addEventListener('click', function (event) {
    var link = event.target.closest && event.target.closest('a[href]');
    if (!link) return;
    var href = link.href;
    if (selectionModal) {
      if (selectionRequiresExitWarning) {
        event.preventDefault();
        event.stopImmediatePropagation();
        ask({
          title: '다른 화면으로 갈까요?',
          body: '만든 질문은 면접 준비 화면의 "만들어 두고 아직 시작하지 않은 질문"에 남아 있어요.',
          confirmLabel: '이동하기',
          cancelLabel: '계속 고르기',
          onConfirm: function () {
            selectionRequiresExitWarning = false;
            closeSelectionModal(true);
            window.location.href = href;
          }
        });
        return;
      }
      closeSelectionModal(true);
      questionSet = null;
      selectionRequiresExitWarning = false;
    }
    if (!session || allowLiveNavigation || !hasUnsubmittedDraft()) return;
    event.preventDefault();
    event.stopImmediatePropagation();
    confirmDraftLoss({
      title: '다른 화면으로 갈까요?',
      extra: '면접은 나중에 이어서 할 수 있어요.',
      confirmLabel: '이동하기',
      onConfirm: function () {
        allowLiveNavigation = true;
        window.location.href = href;
      }
    });
  }, true);
  window.addEventListener('beforeunload', function (event) {
    var guardSelection = selectionModal && selectionRequiresExitWarning;
    var guardDraft = session && hasUnsubmittedDraft();
    if ((!guardSelection && !guardDraft) || allowLiveNavigation) return;
    event.preventDefault();
    event.returnValue = '';
  });
  window.InterviewLive = { startBasic: startBasic, startWeakness: startWeakness, openQuestionSet: openQuestionSet };
})();
