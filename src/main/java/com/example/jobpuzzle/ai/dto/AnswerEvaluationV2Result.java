package com.example.jobpuzzle.ai.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.List;

/**
 * 평가 명세 v2 5장 AI 평가 결과 (답변 1개). followUp은 수정사항 10번으로 추가한 칸.
 * score는 4.5 같은 값이 정수로 조용히 잘리지 않게 BigDecimal로 받고 검증기에서 정수인지 본다.
 */
public record AnswerEvaluationV2Result(
        Integer evaluationVersion,
        String mode,
        @JsonProperty("isEvasive") Boolean isEvasive,
        List<Score> scores,
        String strengths,
        String improvements,
        String recommendedAnswer,
        Comparison comparison,
        FollowUp followUp
) {
    public record Score(String criterion, BigDecimal score, String reason) {
    }

    public record Comparison(String improved, String toImprove) {
    }

    public record FollowUp(String question, String reason) {
    }
}
