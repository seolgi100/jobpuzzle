package com.example.jobpuzzle.ai.validation;

import com.example.jobpuzzle.ai.dto.InterviewQuestionGenerationResult;
import com.example.jobpuzzle.global.error.CustomException;
import com.example.jobpuzzle.global.error.ErrorCode;
import com.example.jobpuzzle.interview.entity.InterviewQuestionReviewStatus;
import com.example.jobpuzzle.interview.entity.InterviewQuestionType;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Provider가 바뀌어도 지켜야 할 JSON-09/11의 서버 측 최종 검증 규칙.
 */
@Component
public class InterviewQuestionGenerationResponseValidator {

    private static final List<InterviewQuestionType> BASIC_TYPES = List.of(
            InterviewQuestionType.SELF_INTRO,
            InterviewQuestionType.MOTIVATION,
            InterviewQuestionType.STRENGTH_WEAKNESS,
            InterviewQuestionType.FAILURE_CONFLICT,
            InterviewQuestionType.JOB_GENERAL
    );

    public void validateBasic(InterviewQuestionGenerationResult result) {
        List<InterviewQuestionGenerationResult.Question> questions = requiredQuestions(result);
        if (questions.size() != BASIC_TYPES.size()) {
            invalid();
        }
        for (int index = 0; index < BASIC_TYPES.size(); index++) {
            InterviewQuestionGenerationResult.Question question = questions.get(index);
            validateCommon(question);
            if (question.getQuestionType() != BASIC_TYPES.get(index)
                    || question.getOriginEvaluationId() != null
                    || question.getTargetWeaknessTag() != null
                    || question.getTargetDimension() != null) {
                invalid();
            }
        }
    }

    private List<InterviewQuestionGenerationResult.Question> requiredQuestions(
            InterviewQuestionGenerationResult result
    ) {
        if (result == null || result.getQuestions() == null) {
            invalid();
        }
        return result.getQuestions();
    }

    private void validateCommon(InterviewQuestionGenerationResult.Question question) {
        if (question == null
                || blank(question.getQuestionId())
                || question.getQuestionType() == null
                || blank(question.getQuestion())
                || blank(question.getIntent())
                || question.getEvaluationFocus() == null
                || question.getEvaluationFocus().isEmpty()
                || question.getEvaluationFocus().stream().anyMatch(java.util.Objects::isNull)
                || question.getReviewStatus() != InterviewQuestionReviewStatus.PASS) {
            invalid();
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private void invalid() {
        throw new CustomException(ErrorCode.AI_RESPONSE_INVALID);
    }
}
