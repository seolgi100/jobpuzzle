package com.example.jobpuzzle.analysis.dto;
import lombok.Builder; import lombok.Getter;
@Getter @Builder public class AnalysisStatusResponse {
    private Long analysisCaseId;
    private Long snapshotId;
    private String analysisCaseStatus;
    private AnalysisStageStatus jobPostingAnalysisStatus;
    private AnalysisStageStatus candidateMaterialAnalysisStatus;
    private AnalysisStageStatus guideContextStatus;
    private AnalysisStageStatus customizedAnalysisStatus;
    private String readinessStatus;
    private Boolean canGenerateQuestions;
    private String latestFailureStage;
    private String latestFailureType;
    private String errorCode;
    private Boolean retryable;
    private String userMessage;
    private Integer attemptCount;
    private Integer maxAttempts;
    private Integer retryAfterSeconds;
    private String traceId;
    // 시뮬레이션 계정이 저장된 결과를 기다리는 중 (화면은 더 자주 조회해 단계를 차례로 보여 줌)
    private boolean simulated;
}
