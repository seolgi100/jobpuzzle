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


  function modeClass(mode) {
    return mode === 'COMPANY_FIT' ? 'is-fit' : mode === 'WEAKNESS_REVIEW' ? 'is-weak' : 'is-basic';
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
      saveDraft();
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
        throw new Error('면접에 쓸 맞춤 질문이 없어요.');
      }
      questionSet = set;
      createSession();
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

  // 질문 생성이 막혔을 때 면접 준비 홈으로 돌아간다
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
        createSession();
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
      createSession();
    }).catch(function (error) { handleGenerationError(error, 'BASIC'); });
  }

  function startWeakness(tag) {
    message('고른 약점을 확인하는 질문을 만들고 있어요.', false, 'WEAKNESS_REVIEW');
    api(window.JobPuzzleRoutes.path('/question-sets/weakness'), {
      method: 'POST',
      json: { targetWeaknessTag: tag }
    }).then(function (set) {
      questionSet = set;
      createSession();
    }).catch(function (error) { handleGenerationError(error, 'WEAKNESS_REVIEW'); });
  }

  // 질문을 고르지 않고 만든 질문을 모두 넣어 시작한다. 원치 않는 질문은 면접 중에 건너뛴다.
  function createSession() {
    var selected = (questionSet.questions || []).filter(function (question) {
      return !question.reviewStatus || question.reviewStatus === 'PASS';
    }).map(function (question) { return Number(question.questionId); });
    if (!selected.length) {
      message('면접에 쓸 수 있는 질문이 없어요.', true);
      return;
    }
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
    var used = usedAttempts(question);
    var remaining = Math.max(0, MAX_ANSWER_ATTEMPTS - used);

    // 건너뛰기는 첫 답을 내기 전만. 답이 하나도 없으면 결과를 낼 수 없어 마지막 남은 질문은 건너뛸 수 없다.
    var subActions = [];
    if (!question.answerSubmitted && (hasAnotherPendingQuestion || hasAnyAnswer)) {
      subActions.push('<button type="button" id="skip-current-question" class="lv-text">이 질문 건너뛰기</button>');
    }
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
      (hasAnyAnswer ? '<button type="button" id="view-current-result" class="lv-link">지금까지 결과</button>' : '') +
      (session.analysisCaseId ? '<a class="lv-link" href="' +
        window.JobPuzzleRoutes.path('/analysis-results/' + encodeURIComponent(session.analysisCaseId)) + '">분석 결과</a>' : '') +
      '<span class="lv-mode ' + modeClass(session.mode) + '">' + esc(modeLabel(session.mode)) + '</span></span></div>' +
      '<div class="lv-progress"><span>질문 <b>' + (currentIndex + 1) + '</b> / ' + questions.length + '</span>' +
      '<div class="lv-progress__bar" role="progressbar" aria-label="답변을 마친 질문" aria-valuemin="0" aria-valuemax="' +
      questions.length + '" aria-valuenow="' + finished + '"><i style="width:' + percent + '%"></i></div>' +
      '<span>' + percent + '%</span></div>' +
      '<section class="lv-q" aria-labelledby="lv-q-text"><span class="lv-q__label">질문 ' + (currentIndex + 1) + '</span>' +
      '<p class="lv-q__text" id="lv-q-text">' + esc(question.questionText) + '</p>' +
      '<button type="button" class="lv-hintbtn" id="lv-hint-toggle" aria-expanded="false" aria-controls="lv-hint">힌트 보기</button>' +
      '<div class="lv-hint" id="lv-hint" hidden><b>이 질문의 의도</b><span>' +
      esc(koreanizeEvaluationTerms(question.intent || '답변의 구체적인 근거와 본인 역할을 확인해요.')) +
      '</span><b>집중 평가 기준</b><span>' + esc(koreanizeEvaluationTerms(focusLabels(question.evaluationFocus))) + '</span>' +
      weaknessOriginHtml(question) + '</div></section>' +
      conversationHtml +
      (followUpMessageId
        ? '<div class="lv-follow" id="live-follow-up"><b>AI 추가 질문</b><p>' + esc(question.pendingFollowUpQuestion || '') + '</p>' +
          '<small>답변을 조금 더 확인해야 할 때만 이어져요. 여기서 이 질문을 마쳐도 돼요.</small></div>'
        : '') +
      '<div class="lv-answer">' +
      '<label for="live-answer">' + (followUpMessageId ? 'AI 추가 질문에 답하기' : '답변 쓰기') + '</label>' +
      (notice ? '<p class="lv-notice" role="alert">' + esc(notice) + '</p>' : '') +
      '<textarea id="live-answer" aria-describedby="lv-answer-error lv-attempts" placeholder="상황, 본인 역할, 행동, 결과를 중심으로 답해 주세요"></textarea>' +
      '<p class="lv-error" id="lv-answer-error" role="alert"></p>' +
      '<p class="lv-attempts" id="lv-attempts">' + (used ? '남은 기회 <b>' + remaining + '번</b> · ' : '') +
      '<span id="lv-saved"></span></p>' +
      '<div class="lv-voice"><button type="button" id="voice-input-toggle" class="lv-voice__btn" aria-pressed="false">' +
      '<i aria-hidden="true"></i>음성으로 답하기</button>' +
      '<p id="voice-input-status" aria-live="polite"></p></div>' +
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
    var viewResult = document.getElementById('view-current-result');
    if (viewResult) viewResult.addEventListener('click', viewCurrentResult);
    var hintToggle = document.getElementById('lv-hint-toggle');
    hintToggle.addEventListener('click', function () {
      var open = hintToggle.getAttribute('aria-expanded') !== 'true';
      hintToggle.setAttribute('aria-expanded', String(open));
      hintToggle.textContent = open ? '힌트 숨기기' : '힌트 보기';
      document.getElementById('lv-hint').hidden = !open;
    });
    document.getElementById('leave-live-interview').addEventListener('click', leaveLiveInterview);
    var answer = document.getElementById('live-answer');
    var saved = readDraft();
    if (saved) {
      answer.value = saved;
      document.getElementById('lv-saved').textContent = '임시 저장한 답을 불러왔어요';
    }
    answer.addEventListener('input', function () {
      saveDraft();
      showAnswerError('');
    });
    setupSpeechInput();
  }

  // 쓰던 답은 질문(추가 질문)마다 이 기기에 임시 저장해 새로고침·이탈에도 남긴다
  var draftStorageOk = true;

  function draftKey() {
    var question = questions[currentIndex];
    return question
      ? 'jobpuzzle_answer_draft_' + question.sessionQuestionId + '_' + (followUpMessageId || 'q')
      : null;
  }

  function readDraft() {
    try { return draftKey() ? localStorage.getItem(draftKey()) || '' : ''; } catch (e) { return ''; }
  }

  function saveDraft() {
    var textarea = document.getElementById('live-answer');
    var key = draftKey();
    if (!textarea || !key) return;
    try {
      if (textarea.value.trim()) localStorage.setItem(key, textarea.value);
      else localStorage.removeItem(key);
      draftStorageOk = true;
      var savedLabel = document.getElementById('lv-saved');
      if (savedLabel) savedLabel.textContent = textarea.value.trim() ? '임시 저장됨' : '';
    } catch (e) {
      draftStorageOk = false;
      var label = document.getElementById('lv-saved');
      if (label) label.textContent = '이 브라우저에서는 임시 저장이 안 돼요';
    }
  }

  function clearDraft() {
    try { if (draftKey()) localStorage.removeItem(draftKey()); } catch (e) { /* 저장소를 못 쓰면 지울 것도 없다 */ }
  }

  function showAnswerError(text) {
    var error = document.getElementById('lv-answer-error');
    var textarea = document.getElementById('live-answer');
    if (!error || !textarea) return;
    error.textContent = text;
    if (text) textarea.setAttribute('aria-invalid', 'true');
    else textarea.removeAttribute('aria-invalid');
  }

  function hasDraftText() {
    var textarea = document.getElementById('live-answer');
    return Boolean(textarea && textarea.value.trim());
  }

  // 임시 저장이 되면 쓰던 답은 남으니 확인 없이 진행하고, 저장이 안 되는 브라우저에서만 묻는다
  function hasUnsubmittedDraft() {
    return hasDraftText() && !draftStorageOk;
  }

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
    ask({
      title: '이 질문을 건너뛸까요?',
      body: '건너뛴 질문은 다시 나오지 않고 평가에서도 빠져요.' +
        (hasDraftText() ? ' 쓰던 답은 지워져요.' : ''),
      confirmLabel: '건너뛰기',
      cancelLabel: '계속 답하기',
      onConfirm: function () { skipQuestion(question); }
    });
  }

  function skipQuestion(question) {
    stopSpeechInput();
    api(window.JobPuzzleRoutes.path('/interview-session-questions/' +
      question.sessionQuestionId + '/skip'), { method: 'POST' }).then(function () {
      clearDraft();
      question.status = 'SKIPPED';
      followUpMessageId = null;
      moveToNextPendingQuestion();
    }).catch(function (error) {
      notify('error', error.message);
    });
  }

  function submitAnswer() {
    stopSpeechInput();
    var textarea = document.getElementById('live-answer');
    var text = textarea.value.trim();
    if (!text) {
      showAnswerError('답을 써야 제출할 수 있어요.');
      textarea.focus();
      return;
    }
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
      clearDraft();
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
          saveDraft();
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
        body: '아직 낸 답변이 없어서 기록은 남지 않아요. 질문은 다시 만들어야 해요.',
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
        (hasDraftText() ? ' 쓰던 답은 지워져요.' : ''),
      confirmLabel: '이 질문 마치기',
      cancelLabel: '계속 쓰기',
      onConfirm: function () { finishCurrentQuestion(currentQuestion); }
    });
  }

  function finishCurrentQuestion(currentQuestion) {
    api(window.JobPuzzleRoutes.path(
      '/interview-session-questions/' + currentQuestion.sessionQuestionId + '/finish-current'
    ), { method: 'POST' }).then(function () {
      clearDraft();
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
  document.addEventListener('click', function (event) {
    var link = event.target.closest && event.target.closest('a[href]');
    if (!link) return;
    var href = link.href;
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
    if (!session || !hasUnsubmittedDraft() || allowLiveNavigation) return;
    event.preventDefault();
    event.returnValue = '';
  });
  window.InterviewLive = { startBasic: startBasic, startWeakness: startWeakness, openQuestionSet: openQuestionSet };
})();
