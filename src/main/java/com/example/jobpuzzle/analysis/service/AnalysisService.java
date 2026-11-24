package com.example.jobpuzzle.analysis.service;

import com.example.jobpuzzle.ai.log.AiExecutionStage;
import com.example.jobpuzzle.analysis.dto.AnalysisInputSnapshotContext;
import com.example.jobpuzzle.analysis.dto.AnalysisInputSnapshotContextSource;
import com.example.jobpuzzle.analysis.entity.AnalysisCase;
import com.example.jobpuzzle.ticket.entity.TicketUsage;
import com.example.jobpuzzle.ticket.service.TicketService;
import com.example.jobpuzzle.analysis.entity.AnalysisCaseStatus;
import com.example.jobpuzzle.analysis.rag.service.RequirementRetrievalService;
import com.example.jobpuzzle.analysis.rag.service.AnalysisVectorIndexService;
import com.example.jobpuzzle.analysis.repository.AnalysisCaseRepository;
import com.example.jobpuzzle.analysis.repository.AnalysisInputSnapshotRepository;
import com.example.jobpuzzle.document.entity.UserDocumentType;
import com.example.jobpuzzle.global.error.CustomException;
import com.example.jobpuzzle.global.error.ErrorCode;
import com.example.jobpuzzle.guide.service.GuideContextService;
import com.example.jobpuzzle.simulation.SimulationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

// JSON-03 컨텍스트를 분리해 JSON-01·02 단계 실행만 조정한다.
@Service
@RequiredArgsConstructor
public class AnalysisService {

    private static final int CANDIDATE_RETRIEVAL_TOP_K = 3;

    private static final Set<UserDocumentType> CANDIDATE_DOCUMENT_TYPES = Set.of(
            UserDocumentType.RESUME,
            UserDocumentType.COVER_LETTER,
            UserDocumentType.PORTFOLIO,
            UserDocumentType.EXPERIENCE_NOTE
    );

    private final AnalysisCaseRepository analysisCaseRepository;
    private final AnalysisInputSnapshotRepository analysisInputSnapshotRepository;
    private final AnalysisCaseService analysisCaseService;
    private final AnalysisCaseStatusTransitionService statusTransitionService;
    private final InitialAnalysisStageExecutor initialAnalysisStageExecutor;
    private final CandidateMaterialPartitionOrchestrator candidateMaterialPartitionOrchestrator;
    private final GuideContextService guideContextService;
    private final RequirementRetrievalService requirementRetrievalService;
    private final AnalysisVectorIndexService vectorIndexService;
    private final CustomizedSynthesisStageExecutor customizedSynthesisStageExecutor;
    private final TicketService ticketService;
    private final SimulationService simulationService;

    // JSON-03을 한 번 조회해 독립적인 JSON-01·02 실행 단계에 전달한다.
    public void runInitialAnalysis(Long userId, Long analysisCaseId) {
        AnalysisCase analysisCase = findOwnedCase(userId, analysisCaseId);
        validateRunnableStatus(analysisCase);
        AnalysisInputSnapshotContext context = analysisCaseService.getAnalysisContext(userId, analysisCaseId);

        List<AnalysisInputSnapshotContextSource> jobPostingSources = context.getSources().stream()
                .filter(source -> source.getDocumentType() == UserDocumentType.JOB_POSTING)
                .toList();
        List<AnalysisInputSnapshotContextSource> companyInfoSources = context.getSources().stream()
                .filter(source -> source.getDocumentType() == UserDocumentType.COMPANY_INFO)
                .toList();
        List<AnalysisInputSnapshotContextSource> candidateSources = context.getSources().stream()
                .filter(source -> CANDIDATE_DOCUMENT_TYPES.contains(source.getDocumentType()))
                .toList();

        if (jobPostingSources.size() != 1) {
            throw new CustomException(ErrorCode.ANALYSIS_CASE_JOB_POSTING_REQUIRED);
        }
        if (candidateSources.isEmpty()) {
            throw new CustomException(ErrorCode.ANALYSIS_CASE_USER_MATERIAL_REQUIRED);
        }

        // ponytail: JSON-01과 JSON-02는 서로 입력이 독립적이라 병렬로 돌려도 안전하다 (둘 다 비-@Transactional).
        // 응답 내용에는 영향 없이 소요 시간만 줄인다.
        CompletableFuture<Void> jobPostingAnalysis = CompletableFuture.runAsync(() -> initialAnalysisStageExecutor.execute(
                AiExecutionStage.JOB_POSTING_ANALYSIS,
                context,
                jobPostingSources,
                companyInfoSources
        ));
        CompletableFuture<Void> candidateMaterialAnalysis = CompletableFuture.runAsync(
                () -> candidateMaterialPartitionOrchestrator.execute(context, candidateSources));
        try {
            CompletableFuture.allOf(jobPostingAnalysis, candidateMaterialAnalysis).join();
        } catch (CompletionException exception) {
            if (exception.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw exception;
        }
    }

    // 요청 스레드에서 끝낼 수 있는 부분만 동기로 처리한다: 티켓 차감, 실행 소유권 획득,
    // 시뮬레이션 재사용, AI를 부르기 전에 미리 검사할 수 있는 조건(vector 색인·가이드).
    // resolved()가 true면 그 자리에서 끝난 결과(동시 요청 또는 시뮬레이션 재사용)이므로 이어서 실행할 필요가 없다.
    public StartOutcome startCustomizedAnalysis(Long userId, Long analysisCaseId) {
        // 상태를 ANALYZING으로 바꾸기 전에 잔액부터 확인한다. v2: 분석은 1장, 자료 기반 질문은 면접 생성 때 따로 차감한다.
        int ticketCost = ticketService.cost(TicketUsage.ANALYSIS);
        ticketService.requireBalance(userId, ticketCost);
        // 상태 잠금으로 새 실행 소유권을 얻지 못한 동시 요청은 기존 실행을 그대로 둔다(티켓도 차감하지 않음).
        if (!statusTransitionService.startOrRestart(userId, analysisCaseId)) {
            return StartOutcome.resolved(analysisCaseId);
        }
        Long ticketUseId = ticketService.use(userId, TicketUsage.ANALYSIS, ticketCost);
        try {
            // 시뮬레이션 계정: 같은 입력으로 끝난 분석이 있으면 AI 없이 잠시 기다린 뒤 그 결과를 보여 준다 (티켓은 평소처럼 차감)
            var stored = simulationService.reusableCase(userId, analysisCaseId);
            if (stored.isPresent()) {
                simulationService.pauseAnalysis(analysisCaseId);
                return StartOutcome.resolved(simulationService.takeOver(userId, analysisCaseId, stored.get().getAnalysisCaseId()));
            }
            vectorIndexService.requireReady(userId, analysisCaseId);
            guideContextService.requireAvailableGuide(userId, analysisCaseId);
            return StartOutcome.pending(ticketUseId);
        } catch (RuntimeException exception) {
            statusTransitionService.failIfAnalyzing(userId, analysisCaseId);
            if (ticketUseId != null) {
                ticketService.refund(ticketUseId);
            }
            throw exception;
        }
    }

    // [백그라운드] JSON-01·02와 JSON-04 뒤에 snapshot 고정 retrieval을 준비한 다음 JSON-05를 실행한다.
    // startCustomizedAnalysis가 pending을 반환한 뒤에만 호출한다.
    public void continueCustomizedAnalysis(Long userId, Long analysisCaseId, Long ticketUseId) {
        try {
            runInitialAnalysis(userId, analysisCaseId);

            Long snapshotId = analysisInputSnapshotRepository
                    .findByAnalysisCase_AnalysisCaseIdAndUser_UserId(analysisCaseId, userId)
                    .orElseThrow(() -> new CustomException(ErrorCode.SNAPSHOT_NOT_FOUND))
                    .getSnapshotId();
            guideContextService.getOrCreateCustomizedSynthesisGuideContext(userId, snapshotId);
            requirementRetrievalService.getOrCreateCandidateRetrievals(userId, snapshotId, CANDIDATE_RETRIEVAL_TOP_K);
            customizedSynthesisStageExecutor.execute(snapshotId);
        } catch (RuntimeException exception) {
            // 단계 실패를 독립 트랜잭션으로 FAILED에 반영한 뒤 원래 오류를 호출자에게 전달한다.
            statusTransitionService.failIfAnalyzing(userId, analysisCaseId);
            // 분석(AI) 실패는 차감한 티켓을 돌려준다.
            if (ticketUseId != null) {
                ticketService.refund(ticketUseId);
            }
            throw exception;
        }
    }

    // 대기열이 꽉 차 백그라운드 실행을 접수하지 못했을 때 되돌린다 (AnalysisRunDispatcher에서만 쓴다).
    public void abortPendingAnalysis(Long userId, Long analysisCaseId, Long ticketUseId) {
        statusTransitionService.failIfAnalyzing(userId, analysisCaseId);
        if (ticketUseId != null) {
            ticketService.refund(ticketUseId);
        }
    }

    // 비동기 디스패치 없이 동기로 끝까지 실행한다 (배치·테스트 등에서 재사용). 결과가 담긴 분석 번호를 돌려준다.
    // 보통은 analysisCaseId 그대로, 시뮬레이션 계정이면 다시 보여 준 저장 결과의 번호.
    public Long runCustomizedAnalysis(Long userId, Long analysisCaseId) {
        StartOutcome outcome = startCustomizedAnalysis(userId, analysisCaseId);
        if (outcome.resolved()) {
            return outcome.resolvedCaseId();
        }
        continueCustomizedAnalysis(userId, analysisCaseId, outcome.ticketUseId());
        return analysisCaseId;
    }

    public record StartOutcome(boolean resolved, Long resolvedCaseId, Long ticketUseId) {
        static StartOutcome resolved(Long caseId) {
            return new StartOutcome(true, caseId, null);
        }

        static StartOutcome pending(Long ticketUseId) {
            return new StartOutcome(false, null, ticketUseId);
        }
    }

    private AnalysisCase findOwnedCase(Long userId, Long analysisCaseId) {
        return analysisCaseRepository.findByAnalysisCaseIdAndUser_UserId(analysisCaseId, userId)
                .orElseThrow(() -> new CustomException(ErrorCode.ANALYSIS_CASE_NOT_FOUND));
    }

    private void validateRunnableStatus(AnalysisCase analysisCase) {
        if (analysisCase.getStatus() != AnalysisCaseStatus.INPUT_CONFIRMED
                && analysisCase.getStatus() != AnalysisCaseStatus.ANALYZING) {
            throw new CustomException(ErrorCode.ANALYSIS_CASE_NOT_READY);
        }
    }
}
