package com.example.jobpuzzle.interview.dto;

import com.example.jobpuzzle.interview.entity.InterviewSession;
import com.example.jobpuzzle.interview.entity.InterviewSessionMode;
import com.example.jobpuzzle.interview.entity.InterviewSessionStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class SessionResponse {
    private Long sessionId;
    private InterviewSessionMode mode;
    private InterviewSessionStatus status;
    private Long questionSetId;
    private Long analysisCaseId;
    private Long jobCategoryId;
    private String careerLevel;
    private int questionCount;
    private boolean answerSubmitted;
    private int completedQuestionCount;
    private int currentQuestionOrder;
    private int currentQaDepth;
    private boolean reviewReady;
    private Long guideId;
    private String guideVersion;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime canceledAt;
    private LocalDateTime lastActivityAt;
    private LocalDateTime lastEvaluatedAt;
    // v2 다회차
    private int round;
    private Long previousSessionId;

    public static SessionResponse from(InterviewSession session, int questionCount) {
        return from(session, questionCount, false);
    }

    public static SessionResponse from(
            InterviewSession session,
            int questionCount,
            boolean answerSubmitted
    ) {
        return from(session, questionCount, answerSubmitted, 0, 1, 0);
    }

    public static SessionResponse from(
            InterviewSession session,
            int questionCount,
            boolean answerSubmitted,
            int completedQuestionCount,
            int currentQuestionOrder,
            int currentQaDepth
    ) {
        return SessionResponse.builder()
                .sessionId(session.getSessionId())
                .mode(session.getMode())
                .status(session.getStatus())
                .questionSetId(session.getQuestionSet().getQuestionSetId())
                .analysisCaseId(resolveAnalysisCaseId(session))
                .jobCategoryId(session.getJobCategory().getJobCategoryId())
                .careerLevel(session.getCareerLevel().name())
                .questionCount(questionCount)
                .answerSubmitted(answerSubmitted)
                .completedQuestionCount(completedQuestionCount)
                .currentQuestionOrder(currentQuestionOrder)
                .currentQaDepth(currentQaDepth)
                .reviewReady(false)
                .guideId(session.getGuideDocument() == null ? null : session.getGuideDocument().getGuideId())
                .guideVersion(session.getGuideVersion())
                .createdAt(session.getCreatedAt())
                .startedAt(session.getStartedAt())
                .completedAt(session.getCompletedAt())
                .canceledAt(session.getCanceledAt())
                .lastActivityAt(session.getLastActivityAt())
                .lastEvaluatedAt(null)
                .round(session.getRoundNumber())
                .previousSessionId(session.getPreviousSession() == null ? null : session.getPreviousSession().getSessionId())
                .build();
    }

    private static Long resolveAnalysisCaseId(InterviewSession session) {
        if (session.getQuestionSet().getSnapshot() == null
                || session.getQuestionSet().getSnapshot().getAnalysisCase() == null) {
            return null;
        }
        return session.getQuestionSet().getSnapshot().getAnalysisCase().getAnalysisCaseId();
    }

    public SessionResponse withReviewReady(boolean value) {
        this.reviewReady = value;
        return this;
    }

    public SessionResponse withLastEvaluatedAt(LocalDateTime value) {
        this.lastEvaluatedAt = value;
        return this;
    }
}
