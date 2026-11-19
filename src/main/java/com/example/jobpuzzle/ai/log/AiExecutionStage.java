package com.example.jobpuzzle.ai.log;

// AI 실행 단계별 입력 계약과 재실행 기준을 구분한다.
public enum AiExecutionStage {
    JOB_POSTING_ANALYSIS,
    CANDIDATE_MATERIAL_ANALYSIS,
    CUSTOMIZED_SYNTHESIS,
    // 평가 명세 v2: 기초 지식·자료 기반 면접 질문 생성 (JSON-12)
    QUESTION_GENERATION,
    ANSWER_EVALUATION
}
