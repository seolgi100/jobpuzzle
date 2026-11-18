package com.example.jobpuzzle.evaluation.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 평가 명세 v2 6장 최종 보고서. AI를 부르지 않고 저장된 답변 평가로만 만든다.
 * 점수는 모두 100점 척도(서버 계산), 질문 안 항목 점수(items)만 AI가 준 1~5점 그대로.
 */
public record SessionReportV2Response(
        Long sessionId,
        String mode,
        String modeLabel,
        int round,
        Long previousSessionId,
        boolean completed,
        LocalDateTime completedAt,
        String title,
        Long analysisCaseId,
        Integer totalScore,
        Integer previousTotalScore,
        List<Criterion> criteria,
        Tally tally,
        List<Question> questions
) {
    public record Criterion(String code, String label, Integer score) {
    }

    public record Tally(int total, int answered, int evaluated, int skipped, int completionRate) {
    }

    /**
     * status: EVALUATED(점수 있음) · FAILED(답했지만 평가 실패) · SKIPPED(답하지 않음).
     * competencyTags는 화면용 이름, competencyTagCodes는 같은 순서의 코드(종합 평가 집계용).
     */
    public record Question(Long sessionQuestionId, int order, String question, String intent, List<String> competencyTags,
                           List<String> competencyTagCodes,
                           String status, Integer score, List<Message> conversation, List<Item> items,
                           String strengths, String improvements, String recommendedAnswer, Comparison comparison) {
    }

    /** type: ORIGINAL_ANSWER · FOLLOW_UP_QUESTION · FOLLOW_UP_ANSWER */
    public record Message(String type, String text) {
    }

    public record Item(String code, String label, Integer score, String reason) {
    }

    public record Comparison(String improved, String toImprove) {
    }
}
