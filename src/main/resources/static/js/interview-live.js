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
  var speechRecognition = null;
  var speechListening = false;
  var selectionModal = null;
  var selectionReturnFocus = null;
  var selectionHistoryActive = false;
  var selectionRequiresExitWarning = false;
  var allowLiveNavigation = false;

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
          return esc(koreanizeEvaluationTerms(weaknessLabel(item)));
        }).join(' · ')
      : '';
    return '<b>질문 출처</b><span>' + mode + ' · ' +
      (question.originScore == null ? '미평가' : question.originScore + '점') +
      (diagnostics ? ' · ' + diagnostics : '') + '</span>';
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
          var error = new Error(body.message || '요청을 처리하지 못했어요.');
          error.status = response.status;
          throw error;
        }
        return body.data;
      });
    });
  }

  // 생성·불러오기·오류 상태. 생성 중에는 퍼즐 조각이 맞춰지는 장면(puzzle-scene.js)을 보여준다.
  function message(text, error, generationMode) {
    if (window.InterviewHome) window.InterviewHome.hide();
    var visual = generationMode
      ? '<canvas class="fl-state__puzzle" data-job-puzzle-scene aria-hidden="true"></canvas>'
      : error ? '' : '<span class="fl-state__spinner" aria-hidden="true"></span>';
    root.innerHTML = '<div class="fl-state' + (error ? ' fl-state--error' : '') + '"' +
      (error ? ' role="alert"' : ' role="status" aria-live="polite"') + '>' + visual +
      '<p class="fl-state__text">' + esc(text) + '</p>' +
      (error ? '<a class="fl-btn fl-btn--navy" href="' + window.JobPuzzleRoutes.path('/interview') + '">면접 준비로 돌아가기</a>' : '') +
      '</div>';
  }

  function modeLabel(mode) {
    if (mode === 'BASIC') return '기본 질문';
    if (mode === 'COMPANY_FIT') return '맞춤 면접';
    return '약점 보완';
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
      status.textContent = '이 브라우저에서는 음성 입력을 쓸 수 없어요. 키보드로 답해 주세요.';
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
      status.textContent = '듣고 있어요. 편하게 말해 주세요.';
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
        : '말한 내용을 글자로 옮겼어요. 계속 말하거나 멈출 수 있어요.';
    };
    speechRecognition.onerror = function (event) {
      var copy = event.error === 'not-allowed'
        ? '마이크 권한이 필요해요. 주소창 옆 마이크 권한을 허용해 주세요.'
        : event.error === 'no-speech'
          ? '목소리가 들리지 않았어요. 다시 해 주세요.'
          : '음성 인식이 멈췄어요. 다시 해 주세요.';
      status.textContent = copy;
    };
    speechRecognition.onend = function () {
      speechListening = false;
      button.classList.remove('is-listening');
      button.setAttribute('aria-pressed', 'false');
      button.innerHTML = '<i aria-hidden="true"></i>음성으로 답하기';
      if (!status.textContent || status.textContent.indexOf('인식 중:') === 0) {
        status.textContent = '음성 입력을 마쳤어요. 옮겨진 문장을 확인해 주세요.';
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
        status.textContent = '음성 인식을 바로 다시 시작할 수 없어요. 잠시 후 해 주세요.';
      }
    });
  }

  function load() {
    message('분석 결과와 질문을 확인하고 있어요.');
    api(window.JobPuzzleRoutes.path('/analysis/cases/' + analysisCaseId + '/status')).then(function (status) {
      if (status.analysisCaseStatus !== 'COMPLETED') {
        throw new Error('분석이 아직 끝나지 않았어요. 분석 화면에서 상태를 확인해 주세요.');
      }
      if (!status.canGenerateQuestions || !status.questionSetAvailable) {
        throw new Error('이 분석 결과로는 맞춤 질문을 만들 수 없어요.');
      }
      return api(window.JobPuzzleRoutes.path('/analysis/cases/' + analysisCaseId + '/question-set'));
    }).then(function (set) {
      if (!set.canGenerateQuestions || !set.questions || !set.questions.length) {
        throw new Error('고를 수 있는 맞춤 질문이 없어요.');
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
    message('만들어 둔 질문을 불러오고 있어요.');
    api(window.JobPuzzleRoutes.path('/question-sets/' + encodeURIComponent(questionSetId)))
      .then(function (set) {
        questionSet = set;
        selectionRequiresExitWarning = false;
        renderSelection();
      })
      .catch(function (error) { message(error.message, true); });
  }

  function startBasic() {
    message('희망 직무에 맞는 기본 질문을 만들고 있어요.', false, 'BASIC');
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
    message('고른 약점을 확인하는 질문을 만들고 있어요.', false, 'WEAKNESS_REVIEW');
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
      var id = 'qs-' + question.questionId;
      return '<li class="qs-item"><div class="qs-row">' +
        '<input type="checkbox" id="' + id + '" name="question" value="' + question.questionId + '" checked>' +
        '<label for="' + id + '" class="qs-text"><span class="qs-no" aria-hidden="true">' + (index + 1) + '</span>' +
        esc(question.question) + '</label>' +
        '<button type="button" class="qs-hintbtn" aria-expanded="false" aria-controls="' + id + '-hint">힌트 보기</button></div>' +
        '<div class="qs-hint" id="' + id + '-hint" hidden><b>이 질문의 의도</b><span>' +
        esc(koreanizeEvaluationTerms(question.intent || '답변의 구체적인 근거와 본인 역할을 확인해요.')) +
        '</span><b>집중 평가 기준</b><span>' + esc(koreanizeEvaluationTerms(focusLabels(question.evaluationFocus))) + '</span>' +
        weaknessOriginHtml(question) + '</div></li>';
    }).join('');
    if (window.InterviewPage && window.InterviewPage.showModes) window.InterviewPage.showModes();
    closeSelectionModal(true);
    selectionReturnFocus = document.activeElement;
    selectionModal = document.createElement('div');
    selectionModal.className = 'qs-layer';
    selectionModal.innerHTML =
      '<div class="qs-scrim" data-close-selection></div>' +
      '<section class="qs-box" role="dialog" aria-modal="true" aria-labelledby="qs-title" tabindex="-1">' +
      '<header class="qs-head"><div><div class="qs-chips"><span class="qs-mode">' + esc(modeLabel(questionSet.mode)) + '</span>' +
      (questionSet.mode === 'WEAKNESS_REVIEW'
        ? '<span class="qs-tag">#' + esc(questionSet.targetWeaknessDisplayName || weaknessLabel(questionSet.targetWeaknessTag) || '고른 약점') + '</span>'
        : '') + '</div>' +
      '<h2 id="qs-title">연습할 질문을 골라주세요</h2>' +
      '<p>고른 순서가 아니라 아래 순서대로 면접이 진행돼요.</p></div>' +
      '<button type="button" class="qs-x" data-close-selection aria-label="질문 고르기 닫기">×</button></header>' +
      '<ol class="qs-list">' + rows + '</ol>' +
      '<footer class="qs-foot"><span class="qs-count" id="qs-count" aria-live="polite"></span>' +
      '<button type="button" id="create-live-session" class="fl-btn fl-btn--navy">고른 질문으로 시작</button></footer></section>';
    document.body.appendChild(selectionModal);
    document.body.classList.add('ih-modal-open');
    window.history.pushState({ interviewSelection: true }, '', '#question-selection');
    selectionHistoryActive = true;
    selectionModal.querySelectorAll('[data-close-selection]').forEach(function (button) {
      button.addEventListener('click', requestCloseSelection);
    });
    selectionModal.querySelectorAll('.qs-hintbtn').forEach(function (button) {
      button.addEventListener('click', function () {
        var panel = document.getElementById(button.getAttribute('aria-controls'));
        var expanded = button.getAttribute('aria-expanded') === 'true';
        button.setAttribute('aria-expanded', String(!expanded));
        button.textContent = expanded ? '힌트 보기' : '힌트 숨기기';
        panel.hidden = expanded;
      });
    });
    var start = document.getElementById('create-live-session');
    function updateCount() {
      var count = selectionModal.querySelectorAll('input[name="question"]:checked').length;
      document.getElementById('qs-count').textContent = count
        ? count + '개 골랐어요'
        : '질문을 하나 이상 골라야 해요';
      start.disabled = count === 0;
    }
    selectionModal.addEventListener('change', updateCount);
    selectionModal.addEventListener('keydown', trapSelectionFocus);
    start.addEventListener('click', createSession);
    updateCount();
    var firstBox = selectionModal.querySelector('input[name="question"]');
    (firstBox || selectionModal.querySelector('.qs-box')).focus();
  }

  // 질문 고르기 창: Tab은 창 안에서만 돌고 Esc로 닫는다
  function trapSelectionFocus(event) {
    if (event.key === 'Escape') {
      if (document.querySelector('.tk-layer')) return; // 위에 확인 창이 떠 있으면 그 창이 처리한다
      event.preventDefault();
      requestCloseSelection();
      return;
    }
    if (event.key !== 'Tab') return;
    var items = Array.prototype.slice.call(selectionModal.querySelectorAll('button:not([disabled]), input'))
      .filter(function (el) { return el.offsetParent !== null; });
    if (!items.length) return;
    var first = items[0];
    var last = items[items.length - 1];
    if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
    else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
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
    document.body.classList.remove('ih-modal-open');
    if (selectionReturnFocus && document.contains(selectionReturnFocus)) selectionReturnFocus.focus();
    selectionReturnFocus = null;
    if (fromHistory) selectionHistoryActive = false;
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
    message('면접을 준비하고 있어요.');
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
          '평가가 잠시 멈췄어요. 답변은 저장됐으니 잠시 후 다시 제출해 주세요.');
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
