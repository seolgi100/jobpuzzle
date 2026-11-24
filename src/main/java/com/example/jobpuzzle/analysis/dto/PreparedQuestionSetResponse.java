package com.example.jobpuzzle.analysis.dto;

import com.example.jobpuzzle.interview.entity.QuestionSet;
import com.example.jobpuzzle.evaluation.entity.AnswerEvaluation;
import com.example.jobpuzzle.evaluation.service.WeaknessTagNormalizer;
import com.example.jobpuzzle.interview.entity.InterviewSessionMode;
import com.example.jobpuzzle.jobcategory.entity.JobCategoryCareerLevel;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Getter
@Builder
public class PreparedQuestionSetResponse {
    private Long questionSetId;
    private String mode;
    private String targetWeaknessTag;
    private String targetWeaknessDisplayName;
    private String displayTitle;
    private String mainCategory;
    private String subCategory;
    private String careerLevel;
    private int questionCount;
    private LocalDateTime createdAt;
    private List<Occurrence> recentOccurrences;

    public static PreparedQuestionSetResponse from(
            QuestionSet set,
            int questionCount,
            WeaknessTagNormalizer normalizer,
            List<AnswerEvaluation> basisEvaluations,
            Map<Long, Integer> basisScores
    ) {
        boolean weakness = set.getInterviewMode() == InterviewSessionMode.WEAKNESS_REVIEW;
        String weaknessName = weakness
                ? normalizer.displayName(set.getTargetWeaknessTag())
                : null;
        String mainCategory = set.getJobCategory() == null
                ? null : set.getJobCategory().getMainCategory();
        String subCategory = set.getJobCategory() == null
                ? null : set.getJobCategory().getSubCategory();
        String careerLevel = careerLevelLabel(set.getCareerLevel());
        String basicTitle = String.join(" · ", java.util.stream.Stream
                .of(mainCategory, subCategory, careerLevel)
                .filter(value -> value != null && !value.isBlank())
                .toList());
        return PreparedQuestionSetResponse.builder()
                .questionSetId(set.getQuestionSetId())
                .mode(set.getInterviewMode().name())
                .targetWeaknessTag(set.getTargetWeaknessTag())
                .targetWeaknessDisplayName(weaknessName)
                .displayTitle(weakness ? weaknessName : basicTitle)
                .mainCategory(mainCategory)
                .subCategory(subCategory)
                .careerLevel(careerLevel)
                .questionCount(questionCount)
                .createdAt(set.getCreatedAt())
                .recentOccurrences((basisEvaluations == null ? List.<AnswerEvaluation>of() : basisEvaluations)
                        .stream()
                        .limit(10)
                        .map(evaluation -> occurrence(
                                evaluation,
                                set.getTargetDimension(),
                                basisScores == null
                                        ? null : basisScores.get(evaluation.getEvaluationId())
                        ))
                        .filter(java.util.Objects::nonNull)
                        .toList())
                .build();
    }

    private static Occurrence occurrence(
            AnswerEvaluation evaluation,
            String targetDimension,
            Integer aggregateScore
    ) {
        if (evaluation == null || evaluation.getSessionQuestion() == null
                || evaluation.getSessionQuestion().getSession() == null) {
            return null;
        }
        var session = evaluation.getSessionQuestion().getSession();
        Integer score = aggregateScore == null
                ? dimensionScore(evaluation, targetDimension)
                : aggregateScore;
        List<String> diagnostics = evaluation.getWeaknessDiagnostics() == null
                ? List.of()
                : evaluation.getWeaknessDiagnostics()
                        .getOrDefault(targetDimension, List.of())
                        .stream()
                        .filter(value -> value != null && !value.isBlank())
                        .distinct()
                        .limit(4)
                        .toList();
        return Occurrence.builder()
                .sessionId(session.getSessionId())
                .evaluationId(evaluation.getEvaluationId())
                .occurredAt(evaluation.getCreatedAt())
                .mode(session.getMode() == null ? null : session.getMode().name())
                .score(score)
                .status(score != null && score >= evaluation.getPassThreshold()
                        ? "RESOLVED" : "UNRESOLVED")
                .resultLabel(modeLabel(session.getMode()) + " 리포트 보기")
                .diagnostics(diagnostics)
                .build();
    }

    private static Integer dimensionScore(
            AnswerEvaluation evaluation,
            String targetDimension
    ) {
        if (targetDimension != null && evaluation.getEvaluationDetail() != null) {
            AnswerEvaluation.DimensionEvaluation detail =
                    evaluation.getEvaluationDetail().get(targetDimension);
            if (detail != null && detail.getScore() != null) {
                return detail.getScore();
            }
        }
        return evaluation.getScore();
    }
    private static String careerLevelLabel(JobCategoryCareerLevel careerLevel) {
        if (careerLevel == null) return null;
        return switch (careerLevel) {
            case NEW -> "신입";
            case EXPERIENCED -> "경력";
            case ANY -> "경력 무관";
        };
    }

    private static String modeLabel(InterviewSessionMode mode) {
        if (mode == null) return null;
        return switch (mode) {
            case BASIC -> "기본 질문";
            case COMPANY_FIT -> "회사 맞춤";
            case WEAKNESS_REVIEW -> "약점 보완";
            case KNOWLEDGE -> "기초 지식";
            case MATERIAL -> "자료 기반";
            case PERSONALITY -> "인성";
        };
    }

    @Getter
    @Builder
    public static class Occurrence {
        private Long sessionId;
        private Long evaluationId;
        private LocalDateTime occurredAt;
        private String mode;
        private Integer score;
        private String status;
        private String resultLabel;
        private List<String> diagnostics;
    }
}
