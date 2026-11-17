package com.example.jobpuzzle.analysis.service;

import com.example.jobpuzzle.ai.dto.InterviewQuestionGenerationResult;
import com.example.jobpuzzle.evaluation.entity.AnswerEvaluation;
import com.example.jobpuzzle.interview.entity.InterviewQuestion;
import com.example.jobpuzzle.interview.entity.QuestionSet;
import com.example.jobpuzzle.interview.repository.InterviewQuestionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 검증이 끝난 AI 질문 결과만 InterviewQuestion Entity로 저장한다.
 */
@Component
@RequiredArgsConstructor
public class InterviewQuestionGenerationResultWriter {

    private final InterviewQuestionRepository interviewQuestionRepository;

    public List<InterviewQuestion> saveBasic(
            QuestionSet questionSet,
            InterviewQuestionGenerationResult result
    ) {
        return save(questionSet, result, Map.of());
    }

    private List<InterviewQuestion> save(
            QuestionSet questionSet,
            InterviewQuestionGenerationResult result,
            Map<Long, AnswerEvaluation> originById
    ) {
        int[] order = {1};
        List<InterviewQuestion> questions = result.getQuestions().stream()
                .map(value -> InterviewQuestion.create(
                        questionSet,
                        value.getQuestionId(),
                        value.getQuestionType(),
                        value.getQuestion(),
                        value.getIntent(),
                        value.getEvaluationFocus(),
                        value.getOriginEvaluationId() == null
                                ? null
                                : originById.get(value.getOriginEvaluationId()),
                        order[0]++
                ))
                .toList();
        return interviewQuestionRepository.saveAll(questions);
    }
}
