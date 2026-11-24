package com.example.jobpuzzle.interview.entity;

public enum QuestionSetGenerationSource {
    AI,
    FALLBACK_TEMPLATE,
    // 인성 면접: 관리자가 등록한 직무별 인성 질문에서 고름 (AI 호출 없음)
    ADMIN_POOL
}
