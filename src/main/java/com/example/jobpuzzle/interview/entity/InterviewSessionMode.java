package com.example.jobpuzzle.interview.entity;

/**
 * 면접 모드. v2(평가 명세 v2): KNOWLEDGE 기초 지식, MATERIAL 자료 기반, PERSONALITY 인성.
 * BASIC·COMPANY_FIT·WEAKNESS_REVIEW는 v1 데이터 조회용으로만 남긴다 (기존 데이터 삭제 전까지).
 */
public enum InterviewSessionMode {
    BASIC, COMPANY_FIT, WEAKNESS_REVIEW,
    KNOWLEDGE, MATERIAL, PERSONALITY;

    public boolean isV2() {
        return this == KNOWLEDGE || this == MATERIAL || this == PERSONALITY;
    }
}
