package com.example.jobpuzzle.analysis.service;

import com.example.jobpuzzle.global.error.CustomException;
import com.example.jobpuzzle.global.error.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;

/**
 * 분석 실행 요청을 접수만 하고, 실제 분석은 전용 스레드 풀에서 실행한다.
 *
 * 분석은 AI 호출 때문에 수십 초~수 분 걸리므로, HTTP 요청 스레드가 끝날 때까지 기다리지 않게 한다.
 * 진행 상황과 실패는 이미 DB(분석 상태, AI 호출 기록)에 남으므로 화면은 /status로 확인한다.
 */
@Slf4j
@Component
public class AnalysisRunDispatcher {

    // resultCaseId: 보통 요청한 analysisCaseId. dispatchedAsync가 false면(동시 요청·시뮬레이션 재사용) 이미 끝난 결과다.
    public record Result(Long resultCaseId, boolean dispatchedAsync) {
    }

    private final AnalysisService analysisService;
    private final TaskExecutor analysisExecutor;

    public AnalysisRunDispatcher(AnalysisService analysisService,
                                  @Qualifier("analysisExecutor") TaskExecutor analysisExecutor) {
        this.analysisService = analysisService;
        this.analysisExecutor = analysisExecutor;
    }

    public Result dispatch(Long userId, Long analysisCaseId) {
        AnalysisService.StartOutcome outcome = analysisService.startCustomizedAnalysis(userId, analysisCaseId);
        if (outcome.resolved()) {
            return new Result(outcome.resolvedCaseId(), false);
        }
        try {
            analysisExecutor.execute(() -> runInBackground(userId, analysisCaseId, outcome.ticketUseId()));
        } catch (TaskRejectedException rejected) {
            // 대기열이 가득 차 실행하지 못하면 ANALYZING으로 남지 않게 실패로 확정하고 티켓을 돌려준다.
            analysisService.abortPendingAnalysis(userId, analysisCaseId, outcome.ticketUseId());
            throw new CustomException(ErrorCode.ANALYSIS_QUEUE_FULL);
        }
        return new Result(analysisCaseId, true);
    }

    private void runInBackground(Long userId, Long analysisCaseId, Long ticketUseId) {
        try {
            analysisService.continueCustomizedAnalysis(userId, analysisCaseId, ticketUseId);
        } catch (RuntimeException exception) {
            // 실패 상태와 티켓 환불은 continueCustomizedAnalysis가 이미 처리했다. 받을 HTTP 요청이 없으므로 로그만 남긴다.
            log.warn("background analysis failed. analysisCaseId={}, error={}", analysisCaseId, exception.toString());
        }
    }
}
