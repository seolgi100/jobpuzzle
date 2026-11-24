package com.example.jobpuzzle.analysis.service;

import com.example.jobpuzzle.ai.log.*;
import com.example.jobpuzzle.analysis.dto.*;
import com.example.jobpuzzle.analysis.entity.*;
import com.example.jobpuzzle.analysis.repository.*;
import com.example.jobpuzzle.global.error.CustomException;
import com.example.jobpuzzle.global.error.ErrorCode;
import com.example.jobpuzzle.guide.entity.GuideContextInputReferenceType;
import com.example.jobpuzzle.guide.entity.GuideContextPurpose;
import com.example.jobpuzzle.guide.repository.GuideContextResultRepository;
import com.example.jobpuzzle.simulation.SimulationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.OptionalDouble;

@Service
@RequiredArgsConstructor
public class AnalysisStatusQueryService {

    private final AnalysisCaseRepository cases;
    private final AnalysisInputSnapshotRepository snapshots;
    private final JobPostingAnalysisRepository jobs;
    private final CandidateMaterialAnalysisRepository candidates;
    private final GuideContextResultRepository guides;
    private final ReadinessResultRepository readiness;
    private final AiCallLogRepository logs;
    private final SimulationService simulationService;

    @Transactional(readOnly = true)
    public AnalysisStatusResponse getStatus(Long userId, Long caseId) {
        AnalysisCase analysisCase = cases.findByAnalysisCaseIdAndUser_UserId(caseId, userId)
                .orElseThrow(() -> new CustomException(ErrorCode.ANALYSIS_CASE_NOT_FOUND));
        OptionalDouble simulated = simulationService.analysisProgress(caseId);
        if (simulated.isPresent()) return simulatedStatus(caseId, simulated.getAsDouble());
        AnalysisInputSnapshot snapshot = snapshots
                .findByAnalysisCase_AnalysisCaseIdAndUser_UserId(caseId, userId).orElse(null);
        if (snapshot == null) return emptyStatus(caseId, analysisCase);

        Long snapshotId = snapshot.getSnapshotId();
        JobPostingAnalysis job = jobs.findBySnapshot_SnapshotId(snapshotId).orElse(null);
        CandidateMaterialAnalysis candidate = candidates.findBySnapshot_SnapshotId(snapshotId).orElse(null);
        ReadinessResult readinessResult = readiness.findBySnapshot_SnapshotId(snapshotId).orElse(null);
        AiCallLog customizedLog = logs
                .findFirstByExecutionStageAndInputReferenceTypeAndInputReferenceIdOrderByAiCallLogIdDesc(
                        AiExecutionStage.CUSTOMIZED_SYNTHESIS,
                        AiInputReferenceType.ANALYSIS_SNAPSHOT,
                        String.valueOf(snapshotId))
                .orElse(null);
        AiCallLog latest = logs
                .findFirstByInputReferenceTypeAndInputReferenceIdOrderByAiCallLogIdDesc(
                        AiInputReferenceType.ANALYSIS_SNAPSHOT, String.valueOf(snapshotId))
                .orElse(null);
        AiCallLog failure = latest != null && latest.getStatus() == AiCallLogStatus.FAILED ? latest : null;

        return AnalysisStatusResponse.builder()
                .analysisCaseId(caseId)
                .snapshotId(snapshotId)
                .analysisCaseStatus(analysisCase.getStatus().name())
                .jobPostingAnalysisStatus(stage(job == null ? null : job.getAiCallLog()))
                .candidateMaterialAnalysisStatus(stage(candidate == null ? null : candidate.getAiCallLog()))
                .guideContextStatus(guides.findByPurposeAndInputReferenceTypeAndInputReferenceId(
                                GuideContextPurpose.CUSTOMIZED_SYNTHESIS,
                                GuideContextInputReferenceType.ANALYSIS_SNAPSHOT,
                                String.valueOf(snapshotId)).isPresent()
                        ? AnalysisStageStatus.SUCCEEDED : AnalysisStageStatus.NOT_STARTED)
                // 결과 row가 없어도 실제 JSON-05 실행 로그로 RUNNING/FAILED를 표시한다.
                .customizedAnalysisStatus(stage(customizedLog))
                .readinessStatus(readinessResult == null ? null : readinessResult.getStatus().name())
                .canGenerateQuestions(readinessResult == null ? null : readinessResult.isCanGenerateQuestions())
                .latestFailureStage(failure == null ? null : failure.getExecutionStage().name())
                .latestFailureType(failure == null ? null : failure.getErrorType().name())
                // 내부 errorMessage는 API에 싣지 않는다.
                .errorCode(failure == null ? null : publicErrorCode(failure.getErrorType()))
                .retryable(failure == null ? null : retryable(failure.getErrorType()))
                .userMessage(failure == null ? null : userMessage(failure.getErrorType()))
                .attemptCount(failure == null ? null : failure.getRetryCount() + 1)
                .maxAttempts(failure == null ? null : maxAttempts(failure.getErrorType()))
                .retryAfterSeconds(null)
                .traceId(failure == null ? null : "analysis-" + caseId + "-" + failure.getAiCallLogId())
                .build();
    }

    private AnalysisStatusResponse emptyStatus(Long caseId, AnalysisCase analysisCase) {
        return AnalysisStatusResponse.builder()
                .analysisCaseId(caseId)
                .analysisCaseStatus(analysisCase.getStatus().name())
                .jobPostingAnalysisStatus(AnalysisStageStatus.NOT_STARTED)
                .candidateMaterialAnalysisStatus(AnalysisStageStatus.NOT_STARTED)
                .guideContextStatus(AnalysisStageStatus.NOT_STARTED)
                .customizedAnalysisStatus(AnalysisStageStatus.NOT_STARTED)
                .build();
    }

    // 시뮬레이션 대기 중: 지난 시간만큼 4단계를 차례로 완료로 넘기고, 다음 단계를 진행 중으로 보여 준다
    static AnalysisStatusResponse simulatedStatus(Long caseId, double progress) {
        int done = Math.min(3, (int) (progress * 4));
        AnalysisStageStatus[] stages = new AnalysisStageStatus[4];
        for (int i = 0; i < 4; i++) {
            stages[i] = i < done ? AnalysisStageStatus.SUCCEEDED
                    : i == done ? AnalysisStageStatus.RUNNING : AnalysisStageStatus.NOT_STARTED;
        }
        return AnalysisStatusResponse.builder()
                .analysisCaseId(caseId)
                .analysisCaseStatus(AnalysisCaseStatus.ANALYZING.name())
                .jobPostingAnalysisStatus(stages[0])
                .candidateMaterialAnalysisStatus(stages[1])
                .guideContextStatus(stages[2])
                .customizedAnalysisStatus(stages[3])
                .simulated(true)
                .build();
    }

    private AnalysisStageStatus stage(AiCallLog log) {
        if (log == null) return AnalysisStageStatus.NOT_STARTED;
        return switch (log.getStatus()) {
            case PENDING, RUNNING -> AnalysisStageStatus.RUNNING;
            case SUCCEEDED -> AnalysisStageStatus.SUCCEEDED;
            case FAILED -> AnalysisStageStatus.FAILED;
        };
    }

    private boolean retryable(AiCallLogErrorType type) {
        return type == AiCallLogErrorType.TIMEOUT || type == AiCallLogErrorType.RATE_LIMIT;
    }

    private int maxAttempts(AiCallLogErrorType type) {
        return retryable(type) ? 3 : 1;
    }

    private String publicErrorCode(AiCallLogErrorType type) {
        return switch (type) {
            case TIMEOUT, RATE_LIMIT -> "ANALYSIS_PROVIDER_TEMPORARILY_UNAVAILABLE";
            case INPUT_LIMIT_EXCEEDED -> "ANALYSIS_INPUT_INVALID";
            case REQUIREMENTS_NOT_FOUND -> "ANALYSIS_JOB_POSTING_REQUIREMENTS_NOT_FOUND";
            case SOURCE_REFERENCE_INVALID, RESPONSE_VALIDATION_FAILED ->
                    "ANALYSIS_RESULT_GENERATION_FAILED";
            default -> "ANALYSIS_EXECUTION_FAILED";
        };
    }

    private String userMessage(AiCallLogErrorType type) {
        return switch (type) {
            case TIMEOUT, RATE_LIMIT -> "분석 서비스 응답이 지연되고 있어요.";
            case INPUT_LIMIT_EXCEEDED -> "분석할 자료의 내용을 확인해 주세요.";
            case REQUIREMENTS_NOT_FOUND -> "공고에서 자격요건이나 우대사항을 찾지 못했어요. 공고 원문에 자격요건이 포함되어 있는지 확인해 주세요.";
            default -> "분석 결과를 생성하지 못했어요.";
        };
    }
}
