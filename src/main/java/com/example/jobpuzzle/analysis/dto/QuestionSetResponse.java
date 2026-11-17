package com.example.jobpuzzle.analysis.dto;

import com.example.jobpuzzle.interview.entity.InterviewQuestion;
import com.example.jobpuzzle.interview.entity.QuestionSet;
import lombok.Builder;
import lombok.Getter;

import java.util.List;
import java.util.Map;

@Getter
@Builder
public class QuestionSetResponse {
    private Long questionSetId;
    private String mode;
    private Long snapshotId;
    private Long jobCategoryId;
    private String careerLevel;
    private String generationSource;
    private String targetWeaknessTag;
    private String targetWeaknessDisplayName;
    private String targetDimension;
    private String status;
    private List<Question> questions;

    public static QuestionSetResponse from(QuestionSet set, List<InterviewQuestion> questions) {
        return from(set, questions, Map.of(), null);
    }

    public static QuestionSetResponse from(
            QuestionSet set,
            List<InterviewQuestion> questions,
            Map<Long, Integer> originScores
    ) {
        return from(set, questions, originScores, null);
    }

    public static QuestionSetResponse from(
            QuestionSet set,
            List<InterviewQuestion> questions,
            Map<Long, Integer> originScores,
            String targetWeaknessDisplayName
    ) {
        return QuestionSetResponse.builder()
                .questionSetId(set.getQuestionSetId())
                .mode(set.getInterviewMode().name())
                .snapshotId(set.getSnapshot() == null ? null : set.getSnapshot().getSnapshotId())
                .jobCategoryId(set.getJobCategory().getJobCategoryId())
                .careerLevel(set.getCareerLevel().name())
                .generationSource(set.getGenerationSource().name())
                .targetWeaknessTag(set.getTargetWeaknessTag())
                .targetWeaknessDisplayName(targetWeaknessDisplayName)
                .targetDimension(set.getTargetDimension())
                .status(set.getStatus().name())
                .questions(questions.stream()
                        .map(question -> Question.from(question, originScores))
                        .toList())
                .build();
    }

    @Getter
    @Builder
    public static class Question {
        private Long questionId;
        private String questionType;
        private String question;
        private String intent;
        private Object evaluationFocus;
        // v2 질문의 역량 태그 코드 (v1 질문은 빈 목록)
        private List<String> competencyTags;
        private int displayOrder;
        private Long originSessionId;
        private String originMode;
        private Integer originScore;
        private Object originDiagnostics;

        private static Question from(
                InterviewQuestion value,
                Map<Long, Integer> originScores
        ) {
            Long originEvaluationId = value.getOriginEvaluation() == null
                    ? null
                    : value.getOriginEvaluation().getEvaluationId();
            return Question.builder()
                    .questionId(value.getQuestionId())
                    .questionType(value.getQuestionType().name())
                    .question(value.getQuestion())
                    .intent(value.getIntent())
                    .evaluationFocus(value.getEvaluationFocus())
                    .competencyTags(value.getCompetencyTags() == null ? List.of() : value.getCompetencyTags())
                    .displayOrder(value.getDisplayOrder())
                    .originSessionId(value.getOriginEvaluation() == null
                            ? null
                            : value.getOriginEvaluation().getSessionQuestion().getSession().getSessionId())
                    .originMode(value.getOriginEvaluation() == null
                            ? null
                            : value.getOriginEvaluation().getEvaluationMode().name())
                    .originScore(originEvaluationId == null
                            ? null
                            : originScores.getOrDefault(
                                    originEvaluationId,
                                    value.getOriginEvaluation().getScore()))
                    .originDiagnostics(value.getOriginEvaluation() == null
                            ? List.of()
                            : value.getOriginEvaluation().getWeaknessTags())
                    .build();
        }
    }
}
