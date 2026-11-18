package com.example.jobpuzzle.evaluation.dto;

import java.util.List;

/**
 * 평가 명세 v2 4-3 사용자 종합 평가 (AI 없음). 최종 보고서가 minReports개 미만이면 available=false이고 목록은 비어 있다.
 * 태그마다 best(그 태그 질문 중 점수가 가장 높은 답변의 잘한 점)와 worst(가장 낮은 답변의 보완할 점)를 함께 준다 (수정사항 6번).
 */
public record UserSummaryResponse(
        boolean available,
        int reportCount,
        int minReports,
        int minQuestionsPerTag,
        String preferredJob,
        List<Tag> strengths,
        List<Tag> weaknesses
) {
    public record Tag(String code, String name, int average, int questionCount, Example best, Example worst) {
    }

    /** text: best는 잘한 점, worst는 보완할 점. */
    public record Example(Long sessionId, String question, int score, String text) {
    }
}
