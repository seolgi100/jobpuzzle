package com.example.jobpuzzle.evaluation.service;

import com.example.jobpuzzle.evaluation.dto.SessionReportV2Response;
import com.example.jobpuzzle.evaluation.dto.SessionScoreSummary;
import com.example.jobpuzzle.global.error.CustomException;
import com.example.jobpuzzle.global.error.ErrorCode;
import com.example.jobpuzzle.interview.entity.InterviewSession;
import com.example.jobpuzzle.interview.repository.InterviewSessionQuestionRepository;
import com.example.jobpuzzle.interview.repository.InterviewSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SessionScoreAggregationService {

    private final InterviewSessionRepository interviewSessionRepository;
    private final InterviewSessionQuestionRepository sessionQuestionRepository;
    private final SessionReportV2Service sessionReportV2Service;

    public SessionScoreSummary aggregate(Long userId, Long sessionId) {
        InterviewSession session = interviewSessionRepository
                .findBySessionIdAndUser_UserIdAndDeletedAtIsNull(sessionId, userId)
                .orElseThrow(() -> new CustomException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND));
        return v2Summary(session);
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

}
