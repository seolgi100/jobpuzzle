// analysis.js — 지원 분석 진행 화면(시작 전 · 진행 중 · 실패). 완료되면 분석 결과 화면으로 넘어간다.
(function () {
    'use strict';

    var root = document.getElementById('analysis-root');
    var caseId = root && root.dataset.analysisCaseId;
    var U = window.JPUI, route = window.JobPuzzleRoutes.path;
    var state = {
        disposed: false, loading: false, running: false, pollTimer: null, retryTimer: null,
        statusSequence: 0, lastProgressSignature: '', automaticRetries: 0, maximumAutomaticRetries: 2
    };
    // [상태 필드, 이름, 설명]
    var stages = [
        ['jobPostingAnalysisStatus', '채용공고 분석', '필수·우대 요구사항을 뽑아요'],
        ['candidateMaterialAnalysisStatus', '지원자 자료 분석', '이력서·자기소개서에서 근거가 될 경험을 찾아요'],
        ['guideContextStatus', '직무 가이드 적용', '희망 직무에서 중요하게 보는 기준을 더해요'],
        ['customizedAnalysisStatus', '맞춤 결과 생성', '요구사항별 연결 결과와 예상 질문을 만들어요']
    ];
    var errorText = {
        ANALYSIS_002: '분석 작업을 찾을 수 없거나 접근 권한이 없어요.',
        ANALYSIS_009: '분석을 시작할 준비가 아직 끝나지 않았어요.',
        ANALYSIS_010: '분석 결과의 무결성을 확인하지 못했어요.',
        ANALYSIS_011: '분석 자료의 색인을 준비하지 못했어요.',
        AI_001: '분석 결과를 처리하지 못했어요. 잠시 후 다시 시도해 주세요.',
        AI_002: '지금은 분석을 준비할 수 없어요. 관리자에게 문의해 주세요.'
    };
    var CHECK = '<svg viewBox="0 0 24 24"><path d="M20 6L9 17l-5-5"/></svg>';
    var INFO = '<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="9"/><path d="M12 8h.01M11 12h1v5h1"/></svg>';
    var WARN = '<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="9"/><path d="M12 8v5M12 16h.01"/></svg>';
    var SEARCH = '<svg viewBox="0 0 24 24"><circle cx="11" cy="11" r="7"/><path d="M21 21l-4.3-4.3"/></svg>';

    function showAcceptedNotice() {
        var notice = sessionStorage.getItem('jobpuzzle_analysis_notice');
        if (!notice) return;
        sessionStorage.removeItem('jobpuzzle_analysis_notice');
        if (window.showToast) window.showToast('success', notice + ' 이 화면에서 바로 분석을 시작할 수 있어요.');
    }

    function api(path, options) {
        options = options || {};
        return fetch(path, { method: options.method || 'GET', credentials: 'same-origin' }).then(function (response) {
            return response.json().catch(function () { return {}; }).then(function (body) {
                if (!response.ok || !body.success) {
                    var error = new Error(body.message || '요청을 처리하지 못했어요.');
                    error.code = body.code; error.status = response.status;
                    throw error;
                }
                return body.data;
            });
        });
    }
    function analysisApi(suffix, options) { return api(route('/analysis/cases/' + caseId + suffix), options); }

    function friendlyError(error) {
        if (typeof error === 'string') return error;
        if (error && error.code && errorText[error.code]) return errorText[error.code];
        if (error && error.status === 401) return '로그인 후 이용해 주세요.';
        if (error && error.status === 403) return '이 분석을 볼 권한이 없어요.';
        return '요청을 처리하지 못했어요. 잠시 후 다시 시도해 주세요.';
    }

    function done(status) { return stages.filter(function (s) { return status[s[0]] === 'SUCCEEDED'; }).length; }
    function stageName(key) { var s = stages.filter(function (x) { return x[0] === key; })[0]; return s ? s[1] : '분석'; }

    function stagesHtml(status, recovering) {
        return stages.map(function (s, i) {
            var v = status[s[0]] || 'NOT_STARTED';
            if (recovering && v === 'FAILED') v = 'RUNNING';
            var st = { SUCCEEDED: 'done', RUNNING: 'run', FAILED: 'fail' }[v] || '';
            var label = { done: '완료', run: recovering ? '다시 연결 중' : '분석 중', fail: '실패', '': '대기' }[st];
            var pill = { done: 'pill--done', run: 'pill--active', fail: 'pill--danger', '': 'pill--none' }[st];
            return '<li class="an-stage' + (st ? ' is-' + st : '') + '"><span class="an-stage__dot">' + (st === 'done' ? CHECK : st === 'fail' ? '!' : i + 1) + '</span>' +
                '<div><p class="an-stage__name">' + s[1] + '</p><p class="an-stage__desc">' + s[2] + '</p></div><span class="pill ' + pill + '">' + label + '</span></li>';
        }).join('');
    }

    // kind: ready | running | preparing | recovery | failed | error
    function render(kind, status, opts) {
        if (state.disposed) return;
        opts = opts || {};
        status = status || {};
        var n = done(status), running = stages.some(function (s) { return status[s[0]] === 'RUNNING'; });
        root.setAttribute('aria-busy', String(kind === 'running' || kind === 'preparing' || kind === 'recovery'));
        var title = {
            ready: '분석을 시작할 준비가 됐어요', running: '지원 자료를 차근차근 읽고 있어요', preparing: '분석에 필요한 자료를 준비하고 있어요',
            recovery: '분석 연결을 다시 확인하고 있어요', error: '분석 상태를 불러오지 못했어요',
            failed: status.latestFailureStage ? stageName(status.latestFailureStage) + ' 단계에서 멈췄어요' : '분석이 중간에 멈췄어요'
        }[kind];
        var sub = {
            ready: '시작 버튼을 누르면 4단계로 분석이 진행돼요. 보통 1~2분 걸려요.',
            running: '4단계 중 ' + Math.min(n + 1, 4) + '단계 진행 중',
            preparing: '지원 자료의 색인을 확인한 뒤 AI 분석을 시작해요.',
            recovery: '일시적인 응답 지연이 있어서 ' + (opts.delay || 0) + '초 뒤에 이어서 진행해요.',
            failed: '4단계 중 ' + n + '단계까지 완료했어요',
            error: friendlyError(opts.error)
        }[kind];
        var icon = kind === 'failed' || kind === 'error'
            ? '<span class="an-status__icon an-status__icon--fail">' + WARN + '</span>'
            : '<span class="an-status__icon">' + SEARCH + '</span>';
        var width = (n + (running || kind === 'running' ? .5 : 0)) / stages.length * 100;
        var html = '<div class="an-status">' + icon + '<div><h2>' + title + '</h2><p>' + U.esc(sub) + '</p></div></div>';
        if (kind !== 'error') {
            html += '<div class="an-bar' + (kind === 'failed' ? ' an-bar--fail' : '') + '"><i style="width:' + width + '%"></i></div>' +
                '<ol class="an-stages">' + stagesHtml(status, kind === 'recovery') + '</ol>';
        }
        if (kind === 'ready') {
            html += '<div class="an-note">' + INFO + '<p>아직 AI 분석은 실행되지 않았어요. 오른쪽에서 고른 자료를 확인한 뒤 시작해주세요.</p></div>' +
                '<div class="an-actions"><button type="button" class="dbtn dbtn--navy" data-run' + (state.running ? ' disabled' : '') + '>분석 시작</button>' +
                '<a href="' + route('/interview') + '" class="dbtn dbtn--ghost">자료 다시 고르기</a></div>';
        } else if (kind === 'running' || kind === 'preparing' || kind === 'recovery') {
            html += '<div class="an-note">' + INFO + '<p>이 화면을 벗어나도 분석은 서버에서 계속돼요. 다른 메뉴를 쓰다가 대시보드에서 다시 들어올 수 있어요.</p></div>' +
                '<div class="an-actions"><a href="' + route('/dashboard') + '" class="dbtn dbtn--ghost">대시보드로 가기</a></div>';
        } else if (kind === 'failed') {
            var message = opts.error ? friendlyError(opts.error) : status.userMessage;
            html += '<div class="an-note an-note--fail">' + WARN + '<p>' + U.esc(message || '일시적인 오류로 분석이 멈췄어요.') + ' 고른 자료와 앞 단계 결과는 남아 있어서, 같은 자료로 이어서 다시 실행할 수 있어요.</p></div>' +
                '<div class="an-actions"><button type="button" class="dbtn dbtn--navy" data-run data-retry' + (state.running ? ' disabled' : '') + '>분석 다시 시도</button>' +
                '<a href="' + route('/interview') + '" class="dbtn dbtn--ghost">면접 준비로 돌아가기</a></div>';
        }
        root.innerHTML = html;
    }

    function renderStatus(status, error) {
        var s = status.analysisCaseStatus;
        if (s === 'FAILED') return render('failed', status, { error: error });
        if (s === 'ANALYZING') return render(status.clientPreparing ? 'preparing' : 'running', status);
        return render('ready', status);
    }

    // ── 오른쪽: 분석에 쓰는 자료 ──
    function renderSources(c) {
        var career = { NEW: '신입', EXPERIENCED: '경력', ANY: '경력 무관' }[c.careerLevel] || '';
        var rows = [['면접 방식', '<span class="mbadge mbadge--custom">맞춤 면접</span>'],
            ['희망 직무', U.esc([c.mainCategory, c.subCategory].filter(Boolean).join(' › ') + (career ? ' · ' + career : ''))]];
        (c.sources || []).forEach(function (src) {
            rows.push([window.DocumentFlow ? window.DocumentFlow.CATEGORY_LABEL[src.documentType] : src.documentType, U.esc(src.displayName)]);
        });
        document.getElementById('analysis-sources').innerHTML = rows.map(function (r) { return '<div><dt>' + r[0] + '</dt><dd>' + r[1] + '</dd></div>'; }).join('');
    }

    function schedulePoll() {
        clearTimeout(state.pollTimer);
        if (!state.disposed) state.pollTimer = setTimeout(loadStatus, 3500);
    }

    function canRetryAutomatically(status) {
        var serverHasAttempts = status.attemptCount == null || status.maxAttempts == null || status.attemptCount < status.maxAttempts;
        return status.analysisCaseStatus === 'FAILED' && status.retryable === true && serverHasAttempts
            && state.automaticRetries < state.maximumAutomaticRetries;
    }

    function scheduleAutomaticRetry(status) {
        if (state.retryTimer || state.running || state.disposed) return;
        var delay = Number(status.retryAfterSeconds) || Math.min(3 * Math.pow(2, state.automaticRetries), 12);
        state.automaticRetries++;
        render('recovery', status, { delay: delay });
        state.retryTimer = setTimeout(function () { state.retryTimer = null; runAnalysis(true); }, delay * 1000);
    }

    function handleStatus(status) {
        if (status.analysisCaseStatus === 'COMPLETED') {
            clearTimeout(state.pollTimer); clearTimeout(state.retryTimer);
            window.location.replace(route('/analysis-results/' + encodeURIComponent(caseId)));
            return;
        }
        if (status.analysisCaseStatus === 'ANALYZING') {
            var signature = [status.analysisCaseStatus].concat(stages.map(function (s) { return status[s[0]] || 'NOT_STARTED'; })).join('|');
            if (state.lastProgressSignature === signature && root.childElementCount) { schedulePoll(); return; }
            state.lastProgressSignature = signature;
        } else {
            state.lastProgressSignature = '';
        }
        if (canRetryAutomatically(status)) { scheduleAutomaticRetry(status); return; }
        renderStatus(status);
        if (status.analysisCaseStatus === 'ANALYZING') schedulePoll();
    }

    function loadStatus() {
        if (state.loading || state.disposed) return;
        var sequence = ++state.statusSequence;
        state.loading = true;
        analysisApi('/status').then(function (status) {
            state.loading = false;
            if (state.disposed || sequence !== state.statusSequence) return;
            handleStatus(status);
        }).catch(function (error) {
            state.loading = false;
            if (sequence !== state.statusSequence) return;
            render('error', {}, { error: error });
        });
    }

    function runAnalysis(reuseExistingIndex) {
        if (state.running) return;
        state.running = true;
        render('preparing', { analysisCaseStatus: 'ANALYZING', clientPreparing: true });
        var ensureIndexed = reuseExistingIndex ? Promise.resolve()
            : api(route('/analysis-cases/' + caseId + '/index'), { method: 'POST' });
        ensureIndexed
            .then(function () { var run = analysisApi('/run', { method: 'POST' }); schedulePoll(); return run; })
            .then(function () { state.running = false; loadStatus(); })
            .catch(function (error) {
                state.running = false;
                clearTimeout(state.pollTimer);
                state.statusSequence++;   // 먼저 보낸 상태 응답이 실패 화면을 덮지 않게
                analysisApi('/status').then(function (status) {
                    if (reuseExistingIndex === true && canRetryAutomatically(status)) { scheduleAutomaticRetry(status); return; }
                    renderStatus(status, reuseExistingIndex === true ? null : error);
                }).catch(function () {
                    render('failed', { analysisCaseStatus: 'FAILED' }, { error: reuseExistingIndex === true ? null : error });
                });
            });
    }

    if (!root || !caseId || !/^\d+$/.test(caseId) || Number(caseId) <= 0) {
        if (root) root.innerHTML = '<div class="an-status"><div><h2>잘못된 분석 주소예요</h2><p>면접 준비에서 다시 시작해 주세요.</p></div></div>';
        return;
    }
    root.addEventListener('click', function (e) {
        var b = e.target.closest('[data-run]');
        if (!b || b.disabled) return;
        var retry = b.hasAttribute('data-retry');
        if (retry && !window.confirm('고른 자료와 앞 단계 결과는 그대로 둬요.\n\n다시 실행하면 AI 요청이 다시 발생할 수 있어요. 계속할까요?')) return;
        runAnalysis(retry);
    });
    window.addEventListener('pagehide', function () {
        state.disposed = true; clearTimeout(state.pollTimer); clearTimeout(state.retryTimer);
    }, { once: true });
    showAcceptedNotice();
    api(route('/analysis-cases/' + caseId)).then(renderSources).catch(function () {
        document.getElementById('analysis-sources').innerHTML = '<div><dt>자료</dt><dd>불러오지 못했어요</dd></div>';
    });
    loadStatus();
})();
