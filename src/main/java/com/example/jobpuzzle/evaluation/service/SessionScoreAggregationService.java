package com.example.jobpuzzle.evaluation.service;

import com.example.jobpuzzle.evaluation.dto.SessionReportV2Response;
import com.example.jobpuzzle.evaluation.dto.SessionScoreSummary;
import com.example.jobpuzzle.evaluation.entity.AnswerEvaluation;
import com.example.jobpuzzle.evaluation.repository.AnswerEvaluationRepository;
import com.example.jobpuzzle.global.error.CustomException;
import com.example.jobpuzzle.global.error.ErrorCode;
import com.example.jobpuzzle.interview.entity.InterviewSession;
import com.example.jobpuzzle.interview.entity.InterviewSessionMode;
import com.example.jobpuzzle.interview.entity.InterviewSessionQuestion;
import com.example.jobpuzzle.interview.entity.InterviewSessionQuestionStatus;
import com.example.jobpuzzle.interview.entity.InterviewMessageType;
import com.example.jobpuzzle.interview.repository.InterviewMessageRepository;
import com.example.jobpuzzle.interview.repository.InterviewSessionQuestionRepository;
import com.example.jobpuzzle.interview.repository.InterviewSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SessionScoreAggregationService {

    private final InterviewSessionRepository interviewSessionRepository;
    private final InterviewSessionQuestionRepository sessionQuestionRepository;
    private final AnswerEvaluationRepository answerEvaluationRepository;
    private final InterviewMessageRepository interviewMessageRepository;
    private final SessionReportV2Service sessionReportV2Service;

    public SessionScoreSummary aggregate(Long userId, Long sessionId) {
        InterviewSession session = interviewSessionRepository
                .findBySessionIdAndUser_UserIdAndDeletedAtIsNull(sessionId, userId)
                .orElseThrow(() -> new CustomException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND));
        if (session.getMode().isV2()) {
            return v2Summary(session);
        }
        List<InterviewSessionQuestion> questions =
                sessionQuestionRepository.findBySession_SessionIdOrderByDisplayOrderAsc(sessionId);

        List<SessionScoreSummary.QuestionScore> questionScores = new ArrayList<>();
        for (InterviewSessionQuestion question : questions) {
            List<AnswerEvaluation> evaluations =
                    answerEvaluationRepository.findBySessionQuestion_SessionQuestionIdOrderByEvaluationIdAsc(
                            question.getSessionQuestionId()
                    );
            if (!evaluations.isEmpty()) {
                questionScores.add(questionScore(question, evaluations));
            }
        }

        Map<String, Integer> categoryScores = aggregateCategories(questionScores);
        Integer overallScore = session.getMode() == InterviewSessionMode.WEAKNESS_REVIEW
                ? null
                : roundedAverage(questionScores.stream()
                        .map(SessionScoreSummary.QuestionScore::getFinalScore)
                        .filter(Objects::nonNull)
                        .toList());
        int total = questions.size();
        int submitted = (int) questions.stream()
                .filter(question -> interviewMessageRepository
                        .findBySessionQuestion_SessionQuestionIdAndMessageType(
                                question.getSessionQuestionId(),
                                InterviewMessageType.ORIGINAL_ANSWER
                        )
                        .isPresent())
                .count();
        int evaluated = questionScores.size();
        int evaluationFailed = (int) questions.stream()
                .filter(this::hasFailedAnswerEvaluation)
                .count();
        int skipped = (int) questions.stream()
                .filter(question -> question.getStatus() == InterviewSessionQuestionStatus.SKIPPED)
                .count();

        return SessionScoreSummary.builder()
                .sessionId(sessionId)
                .mode(session.getMode())
                .overallScore(overallScore)
                .categoryScores(categoryScores)
                .totalQuestionCount(total)
                .submittedQuestionCount(submitted)
                .evaluatedQuestionCount(evaluated)
                .evaluationFailedQuestionCount(evaluationFailed)
                .skippedQuestionCount(skipped)
                .completionRate(total == 0 ? 0 : (int) Math.round(submitted * 100.0 / total))
                .questionScores(questionScores)
                .build();
    }

    // v2 평가는 항목 점수가 1~5점이라 v1 평균으로는 100점이 나오지 않는다. 보고서와 같은 계산(ScoreCalculator)을 쓴다.
    private SessionScoreSummary v2Summary(InterviewSession session) {
        SessionReportV2Response report = sessionReportV2Service.build(session);
        Map<String, Integer> categoryScores = new LinkedHashMap<>();
        report.criteria().stream().filter(c -> c.score() != null).forEach(c -> categoryScores.put(c.code(), c.score()));
        return SessionScoreSummary.builder()
                .sessionId(session.getSessionId())
                .mode(session.getMode())
                .overallScore(report.totalScore())
                .categoryScores(categoryScores)
                .totalQuestionCount(report.tally().total())
                .submittedQuestionCount(report.tally().answered())
                .evaluatedQuestionCount(report.tally().evaluated())
                .evaluationFailedQuestionCount((int) report.questions().stream()
                        .filter(q -> "FAILED".equals(q.status())).count())
                .skippedQuestionCount(report.tally().skipped())
                .completionRate(report.tally().completionRate())
                .questionScores(report.questions().stream().filter(q -> q.score() != null)
                        .map(q -> SessionScoreSummary.QuestionScore.builder()
                                .sessionQuestionId(q.sessionQuestionId())
                                .dimensionScores(Map.of())
                                .dimensionEvaluationCounts(Map.of())
                                .finalScore(q.score())
                                .weaknessTags(List.of())
                                .build())
                        .toList())
                .build();
    }

    private boolean hasFailedAnswerEvaluation(InterviewSessionQuestion question) {
        return interviewMessageRepository
                .findBySessionQuestion_SessionQuestionIdOrderByMessageIdAsc(
                        question.getSessionQuestionId()
                )
                .stream()
                .filter(message -> message.getMessageType() == InterviewMessageType.ORIGINAL_ANSWER
                        || message.getMessageType() == InterviewMessageType.FOLLOW_UP_ANSWER)
                .anyMatch(message -> answerEvaluationRepository
                        .findByAnswerMessage_MessageId(message.getMessageId())
                        .isEmpty());
    }

    private SessionScoreSummary.QuestionScore questionScore(
            InterviewSessionQuestion question,
            List<AnswerEvaluation> evaluations
    ) {
        List<Map<String, Integer>> evaluationScores = evaluations.stream()
                .map(evaluation -> {
                    Map<String, Integer> scores = new LinkedHashMap<>();
                    evaluation.getEvaluationDetail().forEach((dimension, detail) -> {
                        if (detail.getScore() != null) {
                            scores.put(dimension, detail.getScore());
                        }
                    });
                    return scores;
                })
                .toList();
        ScoreAggregationCalculator.Result dimensionResult =
                ScoreAggregationCalculator.aggregate(evaluationScores);
        Map<String, Integer> dimensionScores = dimensionResult.scores();
        List<String> weaknessTags = evaluations.stream()
                .flatMap(evaluation -> evaluation.getWeaknessTags() == null
                        ? java.util.stream.Stream.<String>empty()
                        : evaluation.getWeaknessTags().stream())
                .distinct()
                .toList();
        return SessionScoreSummary.QuestionScore.builder()
                .sessionQuestionId(question.getSessionQuestionId())
                .dimensionScores(dimensionScores)
                .dimensionEvaluationCounts(dimensionResult.counts())
                .finalScore(roundedAverage(new ArrayList<>(dimensionScores.values())))
                .weaknessTags(weaknessTags)
                .build();
    }

    private Map<String, Integer> aggregateCategories(
            List<SessionScoreSummary.QuestionScore> questionScores
    ) {
        Map<String, List<Integer>> values = new LinkedHashMap<>();
        for (SessionScoreSummary.QuestionScore question : questionScores) {
            question.getDimensionScores().forEach((dimension, score) ->
                    values.computeIfAbsent(dimension, ignored -> new ArrayList<>()).add(score));
        }
        return values.entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey,
                entry -> roundedAverage(entry.getValue()),
                (left, right) -> left,
                LinkedHashMap::new
        ));
    }

    private Integer roundedAverage(List<Integer> values) {
        if (values.isEmpty()) {
            return null;
        }
        return (int) Math.round(values.stream().mapToInt(Integer::intValue).average().orElse(0));
    }
}
