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
    return '<dt>질문 출처</dt><dd>' + mode + ' · ' +
      (question.originScore == null ? '미평가' : question.originScore + '점') +
      (diagnostics ? ' · ' + diagnostics : '') + '</dd>';
  }

  // 면접실 화면(design-test/interview-live.html)에서 쓰는 도형
  var SPRITE = '<svg width="0" height="0" style="position:absolute" aria-hidden="true"><defs>' +
    '<symbol id="pc" viewBox="34 6 160 160"><path d="M46,40 H82 C86,40 88,37 86,33 C82,26 84,12 100,12 C116,12 118,26 114,33 C112,37 114,40 118,40 H154 Q160,40 160,46 V82 C160,86 163,88 167,86 C174,82 188,84 188,100 C188,116 174,118 167,114 C163,112 160,114 160,118 V154 Q160,160 154,160 H118 C114,160 112,157 114,153 C118,146 116,132 100,132 C84,132 82,146 86,153 C88,157 86,160 82,160 H46 Q40,160 40,154 V118 C40,114 43,112 47,114 C54,118 68,116 68,100 C68,84 54,82 47,86 C43,88 40,86 40,82 V46 Q40,40 46,40 Z"/></symbol>' +
    '<symbol id="arrow" viewBox="0 0 16 16"><path d="M2 8h11.5M9 3.5 13.5 8 9 12.5" fill="none" stroke="currentColor" stroke-width="1.5"/></symbol>' +
    '<symbol id="arrow-left" viewBox="0 0 16 16"><path d="M14 8H2.5M7 3.5 2.5 8 7 12.5" fill="none" stroke="currentColor" stroke-width="1.5"/></symbol>' +
    '<symbol id="chev" viewBox="0 0 14 14"><path d="M2.5 5 7 9.5 11.5 5" fill="none" stroke="currentColor" stroke-width="1.5"/></symbol>' +
    '<symbol id="mic" viewBox="0 0 18 18"><g fill="none" stroke="currentColor" stroke-width="1.4"><rect x="6.2" y="1.8" width="5.6" height="9.4" rx="2.8"/><path d="M3.5 8.6a5.5 5.5 0 0 0 11 0M9 14.2v2.4M6.4 16.6h5.2"/></g></symbol>' +
    '</defs></svg>';
  function icon(id, cls) { return '<svg' + (cls ? ' class="' + cls + '"' : '') + ' aria-hidden="true"><use href="#' + id + '"/></svg>'; }
  function voiceLabel(text) { return icon('mic') + '<span>' + text + '</span>'; }

  // 머리줄의 면접 이름: 공고 이름(분석 결과) · 모드 · 회차
  var roomName = '';
  function loadRoomName() {
    if (!session || !session.analysisCaseId || roomName) return;
    api(window.JobPuzzleRoutes.path('/analysis-cases/completed')).then(function (cases) {
      var found = (cases || []).filter(function (c) { return c.analysisCaseId === session.analysisCaseId; })[0];
      roomName = found && found.postingName ? found.postingName.replace(/\.[a-z0-9]{2,5}$/i, '') : '';
      var slot = document.querySelector('[data-room-name]');
      if (slot && roomName) slot.textContent = roomName;
    }).catch(function () { /* 이름은 보조 정보 */ });
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
          var error = new Error(body.message || '요청을 처리하지 못했습니다.');
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
    document.body.classList.remove('is-room');
    var visual = generationMode
      ? '<canvas class="fl-state__puzzle" data-job-puzzle-scene aria-hidden="true"></canvas>'
      : error ? '' : '<span class="fl-state__spinner" aria-hidden="true"></span>';
    root.innerHTML = '<div class="fl-state' + (error ? ' fl-state--error' : '') + '"' +
      (error ? ' role="alert"' : ' role="status" aria-live="polite"') + '>' + visual +
      '<p class="fl-state__text">' + esc(text) + '</p>' +
      (error ? '<a class="fl-btn fl-btn--navy" href="' + window.JobPuzzleRoutes.path('/interview') + '">면접 준비로 돌아가기</a>' : '') +
      '</div>';
  }

  var MODE_LABEL = {
    KNOWLEDGE: '기초 지식 면접', MATERIAL: '자료 기반 면접', PERSONALITY: '인성 면접',
    BASIC: '기본 질문', COMPANY_FIT: '맞춤 면접', WEAKNESS_REVIEW: '약점 보완'
  };

  function modeLabel(mode) {
    return MODE_LABEL[mode] || '면접';
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
      status.textContent = '이 브라우저에서는 음성 입력을 쓸 수 없습니다. 키보드로 답해 주십시오.';
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
      button.innerHTML = voiceLabel('음성 입력 멈추기');
      status.textContent = '듣고 있습니다. 편하게 말해 주십시오.';
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
        : '말한 내용을 글자로 옮겼습니다. 계속 말하거나 멈출 수 있습니다.';
    };
    speechRecognition.onerror = function (event) {
      var copy = event.error === 'not-allowed'
        ? '마이크 권한이 필요합니다. 주소창 옆 마이크 권한을 허용해 주십시오.'
        : event.error === 'no-speech'
          ? '목소리가 들리지 않았습니다. 다시 해 주십시오.'
          : '음성 인식이 멈췄습니다. 다시 해 주십시오.';
      status.textContent = copy;
    };
    speechRecognition.onend = function () {
      speechListening = false;
      button.classList.remove('is-listening');
      button.setAttribute('aria-pressed', 'false');
      button.innerHTML = voiceLabel('음성으로 답하기');
      if (!status.textContent || status.textContent.indexOf('인식 중:') === 0) {
        status.textContent = '음성 입력을 마쳤습니다. 옮겨진 문장을 확인해 주십시오.';
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
        status.textContent = '음성 인식을 바로 다시 시작할 수 없습니다. 잠시 후 해 주십시오.';
      }
    });
  }

  // 분석 결과로 볼 면접 고르기 (평가 명세 1장): 기초 지식은 분석만 끝나면, 자료 기반은 분석이 "만들 수 있음"일 때만
  function load() {
    message('분석 결과를 확인하고 있습니다.');
    Promise.all([
      api(window.JobPuzzleRoutes.path('/analysis/cases/' + analysisCaseId + '/status')),
      api(window.JobPuzzleRoutes.path('/tickets/me')).catch(function () { return null; })
    ]).then(function (values) {
      var status = values[0];
      if (status.analysisCaseStatus !== 'COMPLETED') {
        throw new Error('분석이 아직 끝나지 않았습니다. 분석 화면에서 상태를 확인해 주십시오.');
      }
      renderModeChooser(status.canGenerateQuestions === true, values[1]);
    }).catch(function (error) {
      message(error.message, true);
    });
  }

  function renderModeChooser(materialAvailable, tickets) {
    if (window.InterviewHome) window.InterviewHome.hide();
    var costs = tickets ? tickets.costs : null;
    function row(mode, title, note, cost, enabled) {
      return '<li class="ih-quick__row' + (enabled ? '' : ' is-disabled') + '">' +
        '<div class="ih-quick__text"><h3>' + esc(title) + '</h3><p>' + esc(note) + '</p></div>' +
        (costs ? '<span class="ih-fare">티켓 <b>' + esc(cost) + '</b>장</span>' : '<span></span>') +
        '<button type="button" class="ih-btn ih-btn--line" data-choose-mode="' + mode + '"' + (enabled ? '' : ' disabled') +
        '>고르기</button></li>';
    }
    root.innerHTML = '<div class="lv-choose">' +
      '<a class="lv-back" href="' + window.JobPuzzleRoutes.path('/interview') + '"><span aria-hidden="true">←</span> 면접 준비로 돌아가기</a>' +
      '<div class="ih-quick ih-box"><h2 class="ih-quick__title">어떤 면접으로 연습하시겠습니까?</h2><ul>' +
      row('KNOWLEDGE', '기초 지식 면접', '공고가 요구하는 기술과 지식을 얼마나 정확히 아는지 묻습니다.',
        costs && costs.knowledge, true) +
      row('MATERIAL', '자료 기반 면접', materialAvailable
          ? '이력서·자기소개서·포트폴리오의 경험을 공고 요구사항과 연결해 파고듭니다.'
          : '자료에서 공고와 이어지는 근거를 찾지 못해 만들 수 없습니다. 자료를 보완한 뒤 다시 분석해 주십시오.',
        costs && costs.material, materialAvailable) +
      '</ul></div></div>';
    root.querySelectorAll('[data-choose-mode]').forEach(function (button) {
      button.addEventListener('click', function () { confirmGenerate(button.getAttribute('data-choose-mode')); });
    });
  }

  function confirmGenerate(mode) {
    var open = function () { generate(mode); };
    if (!window.TicketConfirm) { open(); return; }
    window.TicketConfirm.open({
      usage: mode,
      what: mode === 'KNOWLEDGE'
        ? '공고 분석 결과로 기초 지식 질문을 만들고 바로 면접을 시작합니다.'
        : '내 자료와 공고 분석 결과로 경험 질문을 만들고 바로 면접을 시작합니다.',
      note: '질문을 만들지 못하면 티켓은 돌려드립니다.',
      confirmLabel: '질문 만들기',
      onConfirm: open
    });
  }

  function generate(mode) {
    message(mode === 'KNOWLEDGE' ? '공고에 맞는 기초 지식 질문을 만들고 있습니다.' : '내 자료로 경험 질문을 만들고 있습니다.', false, mode);
    api(window.JobPuzzleRoutes.path('/question-sets/' + mode.toLowerCase()), {
      method: 'POST',
      json: { analysisCaseId: Number(analysisCaseId) }
    }).then(function (set) {
      questionSet = set;
      createSession();
    }).catch(function (error) { handleGenerationError(error, mode); });
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
        what: '질문을 만들려면 티켓이 더 필요합니다. 충전한 뒤 다시 시도해 주십시오.'
      });
      return;
    }
    message(error.message, true);
  }

  function openQuestionSet(questionSetId) {
    message('만들어 둔 질문을 불러오고 있습니다.');
    api(window.JobPuzzleRoutes.path('/question-sets/' + encodeURIComponent(questionSetId)))
      .then(function (set) {
        questionSet = set;
        createSession();
      })
      .catch(function (error) { message(error.message, true); });
  }

  // 인성 면접: AI 없이 희망 직무의 관리자 등록 질문에서 고른다 (직무는 서버가 회원 정보에서 읽음)
  function startPersonality() {
    message('희망 직무의 인성 질문을 고르고 있습니다.', false, 'PERSONALITY');
    api(window.JobPuzzleRoutes.path('/question-sets/personality'), { method: 'POST' }).then(function (set) {
      questionSet = set;
      createSession();
    }).catch(function (error) { handleGenerationError(error, 'PERSONALITY'); });
  }

  // 질문을 고르지 않고 만든 질문을 모두 넣어 시작한다. 원치 않는 질문은 면접 중에 건너뛴다.
  function createSession() {
    var selected = (questionSet.questions || []).filter(function (question) {
      return !question.reviewStatus || question.reviewStatus === 'PASS';
    }).map(function (question) { return Number(question.questionId); });
    if (!selected.length) {
      message('면접에 쓸 수 있는 질문이 없습니다.', true);
      return;
    }
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
        '/interview-report?sessionId=' + encodeURIComponent(active.sessionId)
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
    // 면접실은 화면 맨 위부터 시작한다
    window.requestAnimationFrame(function () { window.scrollTo(0, 0); });
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
    var used = usedAttempts(question);
    var remaining = Math.max(0, MAX_ANSWER_ATTEMPTS - used);

    // 건너뛰기는 첫 답을 내기 전만. 답이 하나도 없으면 결과를 낼 수 없어 마지막 남은 질문은 건너뛸 수 없다.
    // 면접 자체를 끝내거나 취소하는 버튼은 머리줄에 있다.
    var subActions = [];
    if (!question.answerSubmitted && (hasAnotherPendingQuestion || hasAnyAnswer)) {
      subActions.push('<button type="button" id="skip-current-question">건너뛰기</button>');
    }
    if (question.answerSubmitted) {
      subActions.push('<button type="button" id="finish-live-session">이 질문 마치기</button>');
    }
    if (hasAnyAnswer) subActions.push('<button type="button" id="view-current-result">지금까지 결과</button>');
    if (session.analysisCaseId) {
      subActions.push('<a href="' + window.JobPuzzleRoutes.path('/analysis-results/' + encodeURIComponent(session.analysisCaseId)) + '">분석 결과</a>');
    }

    // 아직 답하지 않은 추가 질문은 질문 아래에만 보여준다(답변 과정과 두 번 나오지 않게)
    var all = question.conversation || [];
    var lastFollowUp = -1;
    all.forEach(function (item, index) { if (item.messageType === 'FOLLOW_UP_QUESTION') lastFollowUp = index; });
    var conversation = all.filter(function (item, index) {
      if (item.messageType === 'ORIGINAL_QUESTION') return false;
      return !(followUpMessageId && index === lastFollowUp);
    });
    var talk = conversation.map(function (item) {
      var label = item.messageType === 'ORIGINAL_ANSWER' ? '첫 답변'
        : item.messageType === 'REJECTED_ANSWER' ? '다시 답해 달라고 한 답변'
        : item.messageType === 'EVALUATION_FAILED_ANSWER' ? '평가가 멈춘 답변'
        : item.messageType === 'FOLLOW_UP_QUESTION' ? 'AI 추가 질문'
        : '추가 답변';
      var note = item.messageType === 'REJECTED_ANSWER' ? '평가에 넣지 않았습니다'
        : item.messageType === 'EVALUATION_FAILED_ANSWER' ? '다시 내면 평가합니다' : '';
      var cls = item.sender === 'USER' ? (note ? 'is-retry' : '') : 'is-ai';
      return '<li' + (cls ? ' class="' + cls + '"' : '') + '><div class="who">' + label + (note ? '<small>' + note + '</small>' : '') + '</div>' +
        '<p>' + esc(item.messageText) + '</p></li>';
    });
    if (followUpMessageId) {
      talk.push('<li class="is-ai"><div class="who">AI 추가 질문</div><p>' + esc(question.pendingFollowUpQuestion || '') + '</p></li>');
    }
    talk.push('<li class="is-now"><div class="who">' + (followUpMessageId ? '추가 답변' : question.answerSubmitted ? '다시 쓰는 답변' : '첫 답변') +
      '</div><p>지금 쓰는 중</p></li>');

    // 진행 조각: 끝냄 = 채움, 건너뜀 = 점선, 지금 = 델프트 블루, 남음 = 선만
    var pieces = questions.map(function (item, index) {
      var cls = index === currentIndex ? 'piece is-point'
        : item.status === 'SKIPPED' ? 'piece is-empty'
        : item.status === 'COMPLETED' ? 'piece' : 'piece is-todo';
      return icon('pc', cls);
    }).join('');

    var notice = question.notice || pendingNotice;
    pendingNotice = null;
    question.notice = null;

    document.body.classList.add('is-room');
    root.innerHTML = SPRITE + '<div class="r2 room">' +
      '<header class="room-bar"><div class="wrap">' +
      '<a class="site-header__brand" href="' + window.JobPuzzleRoutes.path('/') + '" aria-label="JobPuzzle 처음으로"><span class="site-header__mark" aria-hidden="true"></span><span>JobPuzzle</span></a>' +
      '<p class="session"><b data-room-name>' + esc(roomName || modeLabel(session.mode)) + '</b> · ' + esc(modeLabel(session.mode)) +
      (session.round > 1 ? ' · ' + session.round + '회차' : '') + '</p>' +
      '<button class="btn btn-ghost end" type="button" id="end-live-session">' + (hasAnyAnswer ? '면접 끝내기' : '면접 취소') + '</button>' +
      '</div></header>' +
      '<div class="wrap">' +
      '<div class="progress"><div class="pieces" role="img" aria-label="질문 ' + questions.length + '개 중 ' + (currentIndex + 1) + '번째 질문, ' + finished + '개 마침">' + pieces + '</div>' +
      '<p class="count">질문 <b class="num">' + (currentIndex + 1) + '</b>/ ' + questions.length + '</p>' +
      '<button type="button" class="crumb exit" id="leave-live-interview">' + icon('arrow-left') + '면접 준비로 나가기</button></div>' +
      '<div class="stage"><section class="question" aria-labelledby="q-title">' +
      '<p class="no">질문 ' + (currentIndex + 1) + '</p><h1 id="q-title">' + esc(question.questionText) + '</h1>' +
      (followUpMessageId
        ? '<div class="follow" id="live-follow-up"><div class="who">AI 추가 질문</div><p>' + esc(question.pendingFollowUpQuestion || '') + '</p>' +
          '<small>답변을 조금 더 확인해야 할 때만 이어집니다. 여기서 이 질문을 마쳐도 됩니다.</small></div>'
        : '') +
      '<div class="answer"><label for="live-answer">' + (followUpMessageId ? 'AI 추가 질문에 답하기' : '답변 쓰기') + '</label>' +
      (notice ? '<p class="notice" role="alert">' + esc(notice) + '</p>' : '') +
      '<div class="paper sheet" id="answer-sheet">' +
      '<textarea id="live-answer" aria-describedby="lv-answer-error lv-attempts" placeholder="상황, 본인 역할, 행동, 결과를 중심으로 답해 주십시오"></textarea>' +
      '<div class="tools"><button type="button" id="voice-input-toggle" class="voice" aria-pressed="false">' + voiceLabel('음성으로 답하기') + '</button>' +
      '<p class="state" id="lv-attempts" aria-live="polite">' + (used ? '남은 기회 <b class="num">' + remaining + '</b>번' : '') +
      '<span class="saved" id="lv-saved"></span></p>' +
      '<button type="button" id="submit-live-answer" class="btn btn-primary submit">' + (followUpMessageId ? '추가 답변 제출' : '답변 제출') + icon('arrow', 'arrow') + '</button></div>' +
      '<div class="busy" role="status"><span class="dot" aria-hidden="true"></span><span><b>평가하는 중</b> · AI가 답변을 평가하고 있습니다. 잠시만 기다려 주십시오.</span></div>' +
      '</div>' +
      '<p class="error" id="lv-answer-error" role="alert"></p>' +
      '<p class="voice-status" id="voice-input-status" aria-live="polite"></p></div>' +
      (subActions.length ? '<div class="quiet">' + subActions.join('') + '</div>' : '') +
      '</section>' +
      '<aside class="side" aria-label="이 질문의 답변 과정과 힌트">' +
      '<section><h2 class="h3">답변 과정</h2><ol class="talk">' + talk.join('') + '</ol></section>' +
      '<details class="hint"><summary>힌트 보기' + icon('chev') + '</summary><dl>' +
      '<dt>이 질문의 의도</dt><dd>' + esc(koreanizeEvaluationTerms(question.intent || '답변의 구체적인 근거와 본인 역할을 확인합니다.')) + '</dd>' +
      (question.competencyTags && question.competencyTags.length
        ? '<dt>확인하는 역량</dt><dd>' + esc(question.competencyTags.join(' · ')) + '</dd>'
        : '<dt>집중 평가 기준</dt><dd>' + esc(koreanizeEvaluationTerms(focusLabels(question.evaluationFocus))) + '</dd>') +
      weaknessOriginHtml(question) + '</dl></details>' +
      '</aside></div></div></div>';
    loadRoomName();

    document.getElementById('submit-live-answer').addEventListener('click', submitAnswer);
    document.getElementById('end-live-session').addEventListener('click', endOrCancel);
    var finish = document.getElementById('finish-live-session');
    if (finish) finish.addEventListener('click', finishOrCancel);
    var skipQuestion = document.getElementById('skip-current-question');
    if (skipQuestion) skipQuestion.addEventListener('click', skipCurrentQuestion);
    var viewResult = document.getElementById('view-current-result');
    if (viewResult) viewResult.addEventListener('click', viewCurrentResult);
    document.getElementById('leave-live-interview').addEventListener('click', leaveLiveInterview);
    var answer = document.getElementById('live-answer');
    var saved = readDraft();
    if (saved) {
      answer.value = saved;
      document.getElementById('lv-saved').textContent = '임시 저장한 답을 불러왔습니다';
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
      if (label) label.textContent = '이 브라우저에서는 임시 저장이 안 됩니다';
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
      body: '작성 중인 답변은 저장되지 않습니다.' + (opts.extra ? ' ' + opts.extra : ''),
      confirmLabel: opts.confirmLabel,
      cancelLabel: '계속 쓰기',
      onConfirm: opts.onConfirm
    });
  }

  function leaveLiveInterview() {
    confirmDraftLoss({
      title: '면접 준비로 나가시겠습니까?',
      extra: '면접은 나중에 이어서 할 수 있습니다.',
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
      title: '이 질문을 건너뛰시겠습니까?',
      body: '건너뛴 질문은 다시 나오지 않고 평가에서도 빠집니다.' +
        (hasDraftText() ? ' 쓰던 답은 지워집니다.' : ''),
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
      showAnswerError('답을 써야 제출할 수 있습니다.');
      textarea.focus();
      return;
    }
    var button = document.getElementById('submit-live-answer');
    var sheet = document.getElementById('answer-sheet');
    button.disabled = true;
    sheet.classList.add('is-busy');
    textarea.readOnly = true;
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
        pendingNotice = (result.summary || '이 질문은 답을 3번 모두 써서 평가 없이 마쳤습니다.') +
          ' 다음 질문으로 넘어왔습니다.';
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
          '평가가 잠시 멈췄습니다. 쓴 답변은 그대로 두었으니 잠시 후 다시 제출해 주십시오.';
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
          result.retryAnswerMessage || result.summary || '질문에 맞는 경험이나 생각으로 다시 답해 주십시오.';
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
          '평가가 잠시 멈췄습니다. 답변은 저장됐으니 잠시 후 다시 제출해 주십시오.');
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
      button.innerHTML = '다시 제출' + icon('arrow', 'arrow');
      textarea.readOnly = false;
      sheet.classList.remove('is-busy');
      notify('error', error.message);
    });
  }

  function renderFinish() {
    if (!session) return;
    stopSpeechInput();
    allowLiveNavigation = true;
    window.location.href = window.JobPuzzleRoutes.path('/interview-report?sessionId=' +
      encodeURIComponent(session.sessionId) + '&interim=true');
  }

  function finishOrCancel() {
    if (!session) return;
    var hasAnyAnswer = questions.some(function (item) { return item.answerSubmitted; });
    if (!hasAnyAnswer) {
      ask({
        title: '면접을 취소하시겠습니까?',
        body: '아직 낸 답변이 없어서 기록은 남지 않습니다. 질문은 다시 만들어야 합니다.',
        confirmLabel: '면접 취소',
        cancelLabel: '계속 하기',
        onConfirm: cancelSessionNow
      });
      return;
    }
    var currentQuestion = questions[currentIndex];
    if (!currentQuestion || !currentQuestion.answerSubmitted) return;
    ask({
      title: '이 질문을 마치시겠습니까?',
      body: '지금까지 낸 답으로 평가하고 다음 질문으로 넘어갑니다.' +
        (hasDraftText() ? ' 쓰던 답은 지워집니다.' : ''),
      confirmLabel: '이 질문 마치기',
      cancelLabel: '계속 쓰기',
      onConfirm: function () { finishCurrentQuestion(currentQuestion); }
    });
  }

  // 머리줄 버튼: 낸 답이 없으면 면접 취소, 있으면 지금까지 답한 질문으로 면접을 끝내고 리포트로 간다
  function endOrCancel() {
    if (!session) return;
    var hasAnyAnswer = questions.some(function (item) { return item.answerSubmitted; });
    if (!hasAnyAnswer) { finishOrCancel(); return; }
    ask({
      title: '면접을 끝내시겠습니까?',
      body: '지금까지 답한 질문으로 리포트를 만듭니다. 답하지 않은 질문은 점수에서 빠지고, 끝낸 면접은 다시 이어서 할 수 없습니다.',
      confirmLabel: '면접 끝내기',
      cancelLabel: '계속하기',
      onConfirm: function () {
        stopSpeechInput();
        api(window.JobPuzzleRoutes.path('/interview-sessions/' + session.sessionId + '/complete'), { method: 'POST' })
          .then(function () {
            clearDraft();
            allowLiveNavigation = true;
            localStorage.removeItem('jobpuzzle_interview_session_id');
            window.location.href = window.JobPuzzleRoutes.path('/interview-report?sessionId=' + encodeURIComponent(session.sessionId));
          })
          .catch(function (error) { notify('error', error.message); });
      }
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
    message('면접을 취소하고 있습니다.');
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
      title: '지금까지 결과를 보시겠습니까?',
      extra: '면접은 결과 화면에서 이어서 할 수 있습니다.',
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
        scrollLiveQuestionIntoView();
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
      title: '다른 화면으로 가시겠습니까?',
      extra: '면접은 나중에 이어서 할 수 있습니다.',
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
  window.InterviewLive = { startPersonality: startPersonality, openQuestionSet: openQuestionSet };
})();
