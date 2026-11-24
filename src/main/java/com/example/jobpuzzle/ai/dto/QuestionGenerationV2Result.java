package com.example.jobpuzzle.ai.dto;

import java.util.List;

/** JSON-12 기초 지식·자료 기반 면접 질문 생성 결과. relatedRequirementId는 자료 기반에서만 쓴다 (없으면 null). */
public record QuestionGenerationV2Result(List<Question> questions) {

    public record Question(String question, String intent, List<String> competencyTags, String relatedRequirementId) {
    }
}
