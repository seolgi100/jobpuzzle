package com.example.jobpuzzle.competency.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 모든 직무 공통 역량 (평가 명세 4-1). 명세로 고정된 목록이라 관리자 편집 대상이 아니다. */
@Getter
@RequiredArgsConstructor
public enum CommonCompetencyTag {
    COMMUNICATION("의사소통"),
    REASONING("논리력"),
    PROBLEM_SOLVING("문제 해결"),
    COLLABORATION("협업"),
    REQUIREMENT_ANALYSIS("요구 파악"),
    LEARNING_ADAPTABILITY("학습·적응");

    private final String label;

    public static boolean contains(String code) {
        for (CommonCompetencyTag tag : values()) {
            if (tag.name().equals(code)) return true;
        }
        return false;
    }
}
