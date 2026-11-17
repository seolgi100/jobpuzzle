package com.example.jobpuzzle.ai.log;

// AI 실행 단계별 입력 계약과 재실행 기준을 구분한다.
public enum AiExecutionStage {
    JOB_POSTING_ANALYSIS,
    CANDIDATE_MATERIAL_ANALYSIS,
    CUSTOMIZED_SYNTHESIS,
    BASIC_QUESTION_GENERATION,
    // v1 약점 보완 (기존 AI 호출 기록 조회용으로만 남김)
    WEAKNESS_QUESTION_GENERATION,
    ANSWER_EVALUATION,
    WEAKNESS_REEVALUATION,
    FINAL_REPORT
}
