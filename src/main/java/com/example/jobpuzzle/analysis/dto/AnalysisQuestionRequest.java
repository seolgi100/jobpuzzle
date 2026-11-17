package com.example.jobpuzzle.analysis.dto;

import jakarta.validation.constraints.NotNull;

/** 기초 지식·자료 기반 면접 질문 생성: 사용할 분석 결과(분석 작업) 선택. */
public record AnalysisQuestionRequest(@NotNull(message = "사용할 분석 결과를 선택해주세요.") Long analysisCaseId) {
}
