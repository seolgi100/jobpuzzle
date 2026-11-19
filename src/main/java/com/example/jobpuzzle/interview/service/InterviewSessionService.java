package com.example.jobpuzzle.interview.service;

import com.example.jobpuzzle.analysis.entity.CandidateMaterialAnalysis;
import com.example.jobpuzzle.analysis.entity.JobPostingAnalysis;
import com.example.jobpuzzle.analysis.repository.CandidateMaterialAnalysisRepository;
import com.example.jobpuzzle.analysis.repository.JobPostingAnalysisRepository;
import com.example.jobpuzzle.evaluation.service.AnswerEvaluationV2Service;
import com.example.jobpuzzle.evaluation.entity.AnswerEvaluation;
import com.example.jobpuzzle.evaluation.repository.AnswerEvaluationRepository;
import com.example.jobpuzzle.evaluation.service.SessionScoreAggregationService;
import com.example.jobpuzzle.global.error.CustomException;
import com.example.jobpuzzle.global.error.ErrorCode;
import com.example.jobpuzzle.interview.dto.*;
import com.example.jobpuzzle.interview.entity.*;
import com.example.jobpuzzle.interview.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class InterviewSessionService {

    private static final int MAX_ADDITIONAL_ANSWER_RETRIES = 2;

    private static final List<InterviewSessionStatus> UNFINISHED_STATUSES =
            List.of(InterviewSessionStatus.CREATED, InterviewSessionStatus.IN_PROGRESS);

    private final QuestionSetRepository questionSetRepository;
    private final InterviewQuestionRepository interviewQuestionRepository;
    private final InterviewSessionRepository interviewSessionRepository;
    private final InterviewSessionQuestionRepository sessionQuestionRepository;
    private final InterviewMessageRepository interviewMessageRepository;
    private final JobPostingAnalysisRepository jobPostingAnalysisRepository;
    private final CandidateMaterialAnalysisRepository candidateMaterialAnalysisRepository;
    private final AnswerEvaluationV2Service answerEvaluationService;
    private final AnswerEvaluationRepository answerEvaluationRepository;
    private final SessionScoreAggregationService sessionScoreAggregationService;
    private final com.example.jobpuzzle.competency.service.CompetencyService competencyService;

    @Transactional(readOnly = true)
    public List<SessionResponse> getActiveSessions(Long userId) {
        return interviewSessionRepository
                .findByUser_UserIdAndStatusInAndDeletedAtIsNullOrderByCreatedAtDesc(
                        userId, UNFINISHED_STATUSES)
                .stream()
                .filter(session -> !isReviewReady(session))
                .sorted(latestActivityFirst())
                .map(this::toActiveSessionResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<SessionResponse> getReviewReadySessions(Long userId) {
        Map<Long, LocalDateTime> lastEvaluatedAt = new HashMap<>();
        List<InterviewSession> reviewSessions = interviewSessionRepository
                .findByUser_UserIdAndStatusInAndDeletedAtIsNullOrderByCreatedAtDesc(
                        userId, UNFINISHED_STATUSES)
                .stream()
                .filter(this::isReviewReady)
                .toList();
        reviewSessions.forEach(session -> lastEvaluatedAt.put(
                session.getSessionId(), lastSuccessfulEvaluationAt(session)));
        return reviewSessions.stream()
                .sorted(Comparator.comparing(
                        (InterviewSession session) -> lastEvaluatedAt.get(session.getSessionId()),
                        Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(InterviewSession::getCreatedAt,
                                Comparator.nullsLast(Comparator.reverseOrder())))
                .map(session -> toActiveSessionResponse(session)
                        .withReviewReady(true)
                        .withLastEvaluatedAt(lastEvaluatedAt.get(session.getSessionId())))
                .toList();
    }

    private Comparator<InterviewSession> latestActivityFirst() {
        return Comparator.comparing(
                        InterviewSession::getLastActivityAt,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(InterviewSession::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder()));
    }

    private LocalDateTime lastSuccessfulEvaluationAt(InterviewSession session) {
        return answerEvaluationRepository
                .findBySessionQuestion_Session_SessionIdOrderByEvaluationIdAsc(session.getSessionId())
                .stream()
                .map(AnswerEvaluation::getCreatedAt)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public List<SessionResponse> getCompletedSessions(Long userId) {
        return interviewSessionRepository
                .findByUser_UserIdAndStatusAndDeletedAtIsNullOrderByCompletedAtDesc(
                        userId, InterviewSessionStatus.COMPLETED)
                .stream()
                .map(session -> SessionResponse.from(
                        session,
                        Math.toIntExact(sessionQuestionRepository.countBySession_SessionId(
                                session.getSessionId())),
                        true
                ))
                .toList();
    }

    public SessionResponse createSession(Long userId, SessionCreateRequest request) {
        QuestionSet questionSet = questionSetRepository
                .findByQuestionSetIdAndUser_UserId(request.getQuestionSetId(), userId)
                .orElseThrow(() -> new CustomException(ErrorCode.QUESTION_SET_NOT_READY));

        if (!questionSet.isActive()) {
            throw new CustomException(ErrorCode.QUESTION_SET_NOT_READY);
        }
        if (interviewSessionRepository.existsByQuestionSet_QuestionSetId(
                questionSet.getQuestionSetId()
        )) {
            throw new CustomException(ErrorCode.INTERVIEW_SESSION_ALREADY_EXISTS);
        }

        List<Long> selectedIds = request.getSelectedQuestionIds().stream().distinct().toList();
        if (selectedIds.isEmpty() || selectedIds.size() != request.getSelectedQuestionIds().size()) {
            throw new CustomException(ErrorCode.INVALID_QUESTION_SELECTION);
        }

        List<InterviewQuestion> selectedQuestions =
                interviewQuestionRepository.findByQuestionSet_QuestionSetIdAndQuestionIdIn(
                        questionSet.getQuestionSetId(),
                        selectedIds
                );
        if (selectedQuestions.size() != selectedIds.size()
                || selectedQuestions.stream().anyMatch(q -> q.getReviewStatus() != InterviewQuestionReviewStatus.PASS)) {
            throw new CustomException(ErrorCode.INVALID_QUESTION_SELECTION);
        }
        selectedQuestions.sort(Comparator.comparingInt(InterviewQuestion::getDisplayOrder));

        // 분석 결과로 만든 묶음(기초 지식·자료 기반)은 그 분석 결과를 세션에 고정한다.
        InterviewSession session = interviewSessionRepository.save(
                questionSet.getSnapshot() != null
                        ? InterviewSession.create(
                                questionSet, jobPostingAnalysisOf(questionSet), candidateAnalysisOf(questionSet))
                        : InterviewSession.create(questionSet)
        );
        int order = 1;
        for (InterviewQuestion question : selectedQuestions) {
            InterviewSessionQuestion snapshot = sessionQuestionRepository.save(
                    InterviewSessionQuestion.snapshot(session, question, order++)
            );
            interviewMessageRepository.save(InterviewMessage.originalQuestion(snapshot));
        }
        return SessionResponse.from(session, selectedQuestions.size());
    }

    // 세션 생성 당시 스냅샷에 연결된 분석 결과를 고정해서 저장한다.
    private JobPostingAnalysis jobPostingAnalysisOf(QuestionSet questionSet) {
        return jobPostingAnalysisRepository
                .findBySnapshot_SnapshotId(questionSet.getSnapshot().getSnapshotId())
                .orElse(null);
    }

    private CandidateMaterialAnalysis candidateAnalysisOf(QuestionSet questionSet) {
        return candidateMaterialAnalysisRepository
                .findBySnapshot_SnapshotId(questionSet.getSnapshot().getSnapshotId())
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public SessionResponse getSession(Long userId, Long sessionId) {
        InterviewSession session = getOwnedSession(userId, sessionId);
        return SessionResponse.from(
                session,
                Math.toIntExact(sessionQuestionRepository.countBySession_SessionId(sessionId)),
                hasAnyAnswer(sessionId)
        );
    }

    @Transactional(readOnly = true)
    public List<SessionQuestionResponse> getSessionQuestions(Long userId, Long sessionId) {
        getOwnedSession(userId, sessionId);
        return sessionQuestionRepository.findBySession_SessionIdOrderByDisplayOrderAsc(sessionId)
                .stream()
                .map(this::toSessionQuestionResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<RemainingQuestionResponse> getRemainingQuestions(Long userId, Long sessionId) {
        InterviewSession session = getOwnedSession(userId, sessionId);
        if (!session.isEditable()) {
            return List.of();
        }
        Set<Long> selectedIds = sessionQuestionRepository
                .findBySession_SessionIdOrderByDisplayOrderAsc(sessionId)
                .stream()
                .map(question -> question.getQuestion().getQuestionId())
                .collect(Collectors.toSet());
        return interviewQuestionRepository
                .findByQuestionSet_QuestionSetIdOrderByDisplayOrderAsc(
                        session.getQuestionSet().getQuestionSetId())
                .stream()
                .filter(question -> question.getReviewStatus() == InterviewQuestionReviewStatus.PASS)
                .filter(question -> !selectedIds.contains(question.getQuestionId()))
                .map(RemainingQuestionResponse::from)
                .toList();
    }

    public List<SessionQuestionResponse> addQuestions(
            Long userId,
            Long sessionId,
            List<Long> questionIds
    ) {
        InterviewSession session = getOwnedSession(userId, sessionId);
        validateEditable(session);
        if (!session.getQuestionSet().isActive()) {
            throw new CustomException(ErrorCode.QUESTION_SET_NOT_READY);
        }

        List<Long> selectedIds = questionIds.stream().distinct().toList();
        if (selectedIds.isEmpty() || selectedIds.size() != questionIds.size()) {
            throw new CustomException(ErrorCode.INVALID_QUESTION_SELECTION);
        }

        List<InterviewSessionQuestion> existing =
                sessionQuestionRepository.findBySession_SessionIdOrderByDisplayOrderAsc(sessionId);
        Set<Long> existingIds = existing.stream()
                .map(question -> question.getQuestion().getQuestionId())
                .collect(Collectors.toSet());
        if (selectedIds.stream().anyMatch(existingIds::contains)) {
            throw new CustomException(ErrorCode.INVALID_QUESTION_SELECTION);
        }

        List<InterviewQuestion> additions =
                interviewQuestionRepository.findByQuestionSet_QuestionSetIdAndQuestionIdIn(
                        session.getQuestionSet().getQuestionSetId(),
                        selectedIds
                );
        if (additions.size() != selectedIds.size()
                || additions.stream().anyMatch(question ->
                question.getReviewStatus() != InterviewQuestionReviewStatus.PASS)) {
            throw new CustomException(ErrorCode.INVALID_QUESTION_SELECTION);
        }
        additions.sort(Comparator.comparingInt(InterviewQuestion::getDisplayOrder));

        int nextOrder = existing.stream()
                .mapToInt(InterviewSessionQuestion::getDisplayOrder)
                .max()
                .orElse(0) + 1;
        for (InterviewQuestion question : additions) {
            InterviewSessionQuestion snapshot = sessionQuestionRepository.save(
                    InterviewSessionQuestion.snapshot(session, question, nextOrder++)
            );
            interviewMessageRepository.save(InterviewMessage.originalQuestion(snapshot));
        }
        session.touch();
        return getSessionQuestions(userId, sessionId);
    }

    @Transactional(readOnly = true)
    public SessionQuestionResponse getNextQuestion(Long userId, Long sessionId) {
        getOwnedSession(userId, sessionId);
        return sessionQuestionRepository
                .findFirstBySession_SessionIdAndStatusInOrderByDisplayOrderAsc(
                        sessionId,
                        List.of(
                                InterviewSessionQuestionStatus.PENDING,
                                InterviewSessionQuestionStatus.IN_PROGRESS
                        )
                )
                .map(this::toSessionQuestionResponse)
                .orElse(null);
    }

    public AnswerSubmitResponse submitAnswer(
            Long userId,
            Long sessionQuestionId,
            AnswerSubmitRequest request
    ) {
        InterviewSessionQuestion sessionQuestion = sessionQuestionRepository
                .findBySessionQuestionIdAndSession_User_UserId(sessionQuestionId, userId)
                .orElseThrow(() -> new CustomException(ErrorCode.SESSION_QUESTION_NOT_FOUND));
        InterviewSession session = sessionQuestion.getSession();
        validateEditable(session);
        if (sessionQuestion.getStatus() == InterviewSessionQuestionStatus.COMPLETED
                || sessionQuestion.getStatus() == InterviewSessionQuestionStatus.SKIPPED) {
            throw new CustomException(ErrorCode.SESSION_QUESTION_NOT_FOUND);
        }

        InterviewMessage parentQuestion = resolveParentQuestion(userId, sessionQuestion, request);
        long previousAnswerAttempts = interviewMessageRepository
                .countByParentMessage_MessageIdAndSenderAndMessageTypeIn(
                        parentQuestion.getMessageId(),
                        InterviewMessageSender.USER,
                        List.of(
                                InterviewMessageType.ORIGINAL_ANSWER,
                                InterviewMessageType.FOLLOW_UP_ANSWER,
                                InterviewMessageType.REJECTED_ANSWER
                        )
                );
        boolean attemptsExhausted = previousAnswerAttempts >= MAX_ADDITIONAL_ANSWER_RETRIES;
        if (previousAnswerAttempts >= MAX_ADDITIONAL_ANSWER_RETRIES + 1L) {
            throw new CustomException(ErrorCode.ANSWER_SUBMISSION_LIMIT_EXCEEDED);
        }
        if (interviewMessageRepository.existsByParentMessage_MessageIdAndSenderAndMessageTypeIn(
                parentQuestion.getMessageId(),
                InterviewMessageSender.USER,
                List.of(
                        InterviewMessageType.ORIGINAL_ANSWER,
                        InterviewMessageType.FOLLOW_UP_ANSWER
                )
        )) {
            throw new CustomException(ErrorCode.ANSWER_ALREADY_CONFIRMED);
        }

        session.start();
        sessionQuestion.start();
        session.touch();
        InterviewMessage answer = interviewMessageRepository.save(InterviewMessage.answer(
                sessionQuestion,
                parentQuestion,
                request.getAnswerType(),
                request.getMessageText().trim()
        ));
        if (AnswerTextValidity.isUnusable(answer.getMessageText())) {
            answer.rejectAnswer();
            if (attemptsExhausted) {
                sessionQuestion.complete();
            }
            return AnswerSubmitResponse.builder()
                    .answerMessageId(answer.getMessageId())
                    .summary(attemptsExhausted
                            ? "답변을 평가할 수 없어 이 질문을 미평가로 종료합니다."
                            : "질문과 관련된 경험이나 생각을 다시 답변해 주세요.")
                    .evaluationFailed(attemptsExhausted)
                    .retryAnswerRequired(!attemptsExhausted)
                    .answerAttemptsExhausted(attemptsExhausted)
                    .remainingAnswerRetries((int) Math.max(
                            0,
                            MAX_ADDITIONAL_ANSWER_RETRIES - previousAnswerAttempts
                    ))
                    .retryAnswerMessage(attemptsExhausted
                            ? null
                            : "질문과 관련된 경험이나 생각을 다시 답변해 주세요.")
                    .sessionStatus(session.getStatus())
                    .nextQuestion(attemptsExhausted
                            ? getNextQuestion(userId, session.getSessionId())
                            : SessionQuestionResponse.from(sessionQuestion))
                    .build();
        }

        AnswerEvaluationV2Service.EvaluationOutcome outcome =
                answerEvaluationService.evaluate(answer);
        if (outcome.evaluationFailed()) {
            answer.markEvaluationFailed();
            if (attemptsExhausted) {
                sessionQuestion.complete();
            }
            return AnswerSubmitResponse.builder()
                    .answerMessageId(answer.getMessageId())
                    .summary(attemptsExhausted
                            ? "답변 제출 3회를 모두 사용해 이 질문을 미평가로 종료합니다."
                            : "AI 평가 응답을 처리하지 못했습니다. 입력한 답변을 유지한 채 다시 평가할 수 있습니다.")
                    .evaluationFailed(true)
                    .failureTraceId(outcome.failureTraceId())
                    .evaluationRetryRequired(!attemptsExhausted)
                    .answerAttemptsExhausted(attemptsExhausted)
                    .remainingAnswerRetries((int) Math.max(
                            0,
                            MAX_ADDITIONAL_ANSWER_RETRIES - previousAnswerAttempts
                    ))
                    .evaluationRetryMessage(attemptsExhausted
                            ? null
                            : "답변 내용은 그대로 유지됩니다. 잠시 후 다시 제출해 주세요.")
                    .sessionStatus(session.getStatus())
                    .nextQuestion(attemptsExhausted
                            ? getNextQuestion(userId, session.getSessionId())
                            : SessionQuestionResponse.from(sessionQuestion))
                    .build();
        }
        if (outcome.followUpMessage() == null) {
            sessionQuestion.complete();
        }

        SessionQuestionResponse nextQuestion = outcome.followUpMessage() == null
                ? getNextQuestion(userId, session.getSessionId())
                : SessionQuestionResponse.from(sessionQuestion);
        return AnswerSubmitResponse.builder()
                .answerMessageId(answer.getMessageId())
                .evaluationId(outcome.evaluation() == null ? null : outcome.evaluation().getEvaluationId())
                .score(outcome.evaluation() == null ? null : outcome.evaluation().getScore())
                .summary(outcome.evaluation() == null
                        ? "AI 평가에 실패했습니다. 답변은 저장되었고 점수 계산에서는 제외됩니다."
                        : outcome.evaluation().getSummary())
                .evaluationFailed(false)
                .failureTraceId(outcome.failureTraceId())
                .followUpQuestionMessageId(
                        outcome.followUpMessage() == null ? null : outcome.followUpMessage().getMessageId()
                )
                .followUpQuestion(
                        outcome.followUpMessage() == null ? null : outcome.followUpMessage().getMessageText()
                )
                .sessionStatus(session.getStatus())
                .nextQuestion(nextQuestion)
                .build();
    }

    public SessionResponse completeSession(Long userId, Long sessionId) {
        InterviewSession session = getOwnedSession(userId, sessionId);
        validateEditable(session);

        boolean hasSuccessfulEvaluation = answerEvaluationRepository
                .existsBySessionQuestion_Session_SessionId(sessionId);
        if (!hasSuccessfulEvaluation) {
            throw new CustomException(ErrorCode.SESSION_CANNOT_BE_COMPLETED);
        }

        List<InterviewSessionQuestion> sessionQuestions =
                sessionQuestionRepository.findBySession_SessionIdOrderByDisplayOrderAsc(sessionId);
        sessionQuestions.forEach(question -> {
            if (hasOriginalAnswer(question.getSessionQuestionId())) {
                question.complete();
            } else {
                question.skip();
            }
        });
        session.complete();

        // 면접 기능 추가: 정상 완료된 QuestionSet은 미선택 질문까지 더 이상 재사용하지 않는다.
        session.getQuestionSet().archive();
        return SessionResponse.from(
                session,
                Math.toIntExact(sessionQuestionRepository.countBySession_SessionId(sessionId))
        );
    }

    // 면접 중 건너뛰기: 첫 답변을 내지 않은 질문만 SKIPPED로 빼고, 평가 비용은 들지 않는다.
    public SessionQuestionResponse skipQuestion(
            Long userId,
            Long sessionQuestionId
    ) {
        InterviewSessionQuestion question = sessionQuestionRepository
                .findBySessionQuestionIdAndSession_User_UserId(sessionQuestionId, userId)
                .orElseThrow(() -> new CustomException(ErrorCode.SESSION_QUESTION_NOT_FOUND));
        validateEditable(question.getSession());
        if (question.getStatus() == InterviewSessionQuestionStatus.COMPLETED
                || question.getStatus() == InterviewSessionQuestionStatus.SKIPPED) {
            throw new CustomException(ErrorCode.SESSION_QUESTION_NOT_FOUND);
        }
        if (hasOriginalAnswer(sessionQuestionId)) {
            throw new CustomException(ErrorCode.ANSWER_ALREADY_CONFIRMED);
        }
        question.skip();
        question.getSession().touch();
        return toSessionQuestionResponse(question);
    }

    public SessionQuestionResponse finishCurrentQuestion(
            Long userId,
            Long sessionQuestionId
    ) {
        InterviewSessionQuestion question = sessionQuestionRepository
                .findBySessionQuestionIdAndSession_User_UserId(sessionQuestionId, userId)
                .orElseThrow(() -> new CustomException(ErrorCode.SESSION_QUESTION_NOT_FOUND));
        validateEditable(question.getSession());
        if (!hasSubmittedAnswer(sessionQuestionId)) {
            throw new CustomException(ErrorCode.SESSION_CANNOT_BE_COMPLETED);
        }
        question.complete();
        question.getSession().touch();
        return toSessionQuestionResponse(question);
    }

    public SessionResponse cancelSession(Long userId, Long sessionId) {
        InterviewSession session = getOwnedSession(userId, sessionId);
        validateEditable(session);
        boolean hasAnswer = interviewMessageRepository
                .existsBySessionQuestion_Session_SessionIdAndMessageTypeIn(
                        sessionId,
                        List.of(
                                InterviewMessageType.ORIGINAL_ANSWER,
                                InterviewMessageType.FOLLOW_UP_ANSWER
                        )
                );
        if (hasAnswer) {
            throw new CustomException(ErrorCode.SESSION_CANNOT_BE_CANCELED);
        }
        session.cancel();
        return SessionResponse.from(
                session,
                Math.toIntExact(sessionQuestionRepository.countBySession_SessionId(sessionId))
        );
    }

    private InterviewMessage resolveParentQuestion(
            Long userId,
            InterviewSessionQuestion sessionQuestion,
            AnswerSubmitRequest request
    ) {
        List<InterviewMessage> messages = interviewMessageRepository
                .findBySessionQuestion_SessionQuestionIdOrderByMessageIdAsc(
                        sessionQuestion.getSessionQuestionId()
                );
        if (request.getAnswerType() == AnswerType.ORIGINAL_ANSWER) {
            return messages.stream()
                    .filter(message -> message.getMessageType() == InterviewMessageType.ORIGINAL_QUESTION)
                    .findFirst()
                    .orElseThrow(() -> new CustomException(ErrorCode.ANSWER_MESSAGE_NOT_FOUND));
        }
        if (request.getParentQuestionMessageId() == null) {
            throw new CustomException(ErrorCode.INVALID_REQUEST);
        }
        InterviewMessage parent = interviewMessageRepository
                .findByMessageIdAndSessionQuestion_Session_User_UserId(
                        request.getParentQuestionMessageId(),
                        userId
                )
                .orElseThrow(() -> new CustomException(ErrorCode.ANSWER_MESSAGE_NOT_FOUND));
        if (!Objects.equals(
                parent.getSessionQuestion().getSessionQuestionId(),
                sessionQuestion.getSessionQuestionId()
        ) || parent.getMessageType() != InterviewMessageType.FOLLOW_UP_QUESTION) {
            throw new CustomException(ErrorCode.INVALID_REQUEST);
        }
        return parent;
    }

    private boolean hasOriginalAnswer(Long sessionQuestionId) {
        return interviewMessageRepository
                .findBySessionQuestion_SessionQuestionIdAndMessageType(
                        sessionQuestionId,
                        InterviewMessageType.ORIGINAL_ANSWER
                )
                .isPresent();
    }

    private boolean hasSubmittedAnswer(Long sessionQuestionId) {
        return interviewMessageRepository
                .existsBySessionQuestion_SessionQuestionIdAndMessageTypeIn(
                        sessionQuestionId,
                        List.of(
                                InterviewMessageType.ORIGINAL_ANSWER,
                                InterviewMessageType.FOLLOW_UP_ANSWER,
                                InterviewMessageType.REJECTED_ANSWER,
                                InterviewMessageType.EVALUATION_FAILED_ANSWER
                        )
                );
    }

    private boolean hasAnyAnswer(Long sessionId) {
        return interviewMessageRepository
                .existsBySessionQuestion_Session_SessionIdAndMessageTypeIn(
                        sessionId,
                        List.of(
                                InterviewMessageType.ORIGINAL_ANSWER,
                                InterviewMessageType.FOLLOW_UP_ANSWER
                        )
                );
    }

    private SessionResponse toActiveSessionResponse(InterviewSession session) {
        List<InterviewSessionQuestion> sessionQuestions =
                sessionQuestionRepository.findBySession_SessionIdOrderByDisplayOrderAsc(
                        session.getSessionId()
                );
        int completedQuestionCount = Math.toIntExact(sessionQuestions.stream()
                .filter(question -> question.getStatus() == InterviewSessionQuestionStatus.COMPLETED)
                .count());
        InterviewSessionQuestion currentQuestion = sessionQuestions.stream()
                .filter(question -> question.getStatus() == InterviewSessionQuestionStatus.PENDING
                        || question.getStatus() == InterviewSessionQuestionStatus.IN_PROGRESS)
                .findFirst()
                .orElse(sessionQuestions.isEmpty() ? null : sessionQuestions.get(sessionQuestions.size() - 1));
        int currentQuestionOrder = currentQuestion == null ? 0 : currentQuestion.getDisplayOrder();
        int currentQaDepth = currentQuestion == null ? 0 : Math.toIntExact(
                interviewMessageRepository
                        .findBySessionQuestion_SessionQuestionIdOrderByMessageIdAsc(
                                currentQuestion.getSessionQuestionId()
                        )
                        .stream()
                        .filter(message -> message.getMessageType() == InterviewMessageType.ORIGINAL_ANSWER
                                || message.getMessageType() == InterviewMessageType.FOLLOW_UP_ANSWER)
                        .count()
        );
        return SessionResponse.from(
                session,
                sessionQuestions.size(),
                hasAnyAnswer(session.getSessionId()),
                completedQuestionCount,
                currentQuestionOrder,
                currentQaDepth
        );
    }

    private boolean isReviewReady(InterviewSession session) {
        List<InterviewSessionQuestion> questions =
                sessionQuestionRepository.findBySession_SessionIdOrderByDisplayOrderAsc(
                        session.getSessionId());
        if (questions.isEmpty() || !hasAnyAnswer(session.getSessionId())) {
            return false;
        }
        return questions.stream().noneMatch(question ->
                question.getStatus() == InterviewSessionQuestionStatus.PENDING
                        || question.getStatus() == InterviewSessionQuestionStatus.IN_PROGRESS);
    }

    private SessionQuestionResponse toSessionQuestionResponse(
            InterviewSessionQuestion question
    ) {
        List<InterviewMessage> messages = interviewMessageRepository
                .findBySessionQuestion_SessionQuestionIdOrderByMessageIdAsc(
                        question.getSessionQuestionId()
                );
        InterviewMessage pendingFollowUp = messages.isEmpty()
                ? null
                : messages.get(messages.size() - 1);
        boolean hasPendingFollowUp = pendingFollowUp != null
                && pendingFollowUp.getMessageType() == InterviewMessageType.FOLLOW_UP_QUESTION;
        boolean answerSubmitted = messages.stream()
                .anyMatch(message -> message.getMessageType() == InterviewMessageType.ORIGINAL_ANSWER);
        int followUpCount = Math.toIntExact(messages.stream()
                .filter(message -> message.getMessageType() == InterviewMessageType.FOLLOW_UP_QUESTION)
                .count());
        return SessionQuestionResponse.from(
                question,
                answerSubmitted,
                followUpCount,
                hasPendingFollowUp ? pendingFollowUp.getMessageId() : null,
                hasPendingFollowUp ? pendingFollowUp.getMessageText() : null,
                messages,
                competencyTagNames(question)
        );
    }

    // 태그 코드를 그 직무의 태그 이름으로. 관리자가 지운 태그는 코드 그대로 보여 준다.
    // ponytail: 질문마다 직무 태그를 다시 읽음, 질문 수(5~10개)가 늘면 세션 단위로 한 번만 읽기
    private List<String> competencyTagNames(InterviewSessionQuestion question) {
        List<String> codes = question.getQuestion().getCompetencyTags();
        if (codes == null || codes.isEmpty()) return List.of();
        var job = question.getSession().getJobCategory();
        Map<String, String> names = competencyService.availableTags(job.getMainCategory(), job.getSubCategory());
        return codes.stream().map(code -> names.getOrDefault(code, code)).toList();
    }

    private InterviewSession getOwnedSession(Long userId, Long sessionId) {
        return interviewSessionRepository
                .findBySessionIdAndUser_UserIdAndDeletedAtIsNull(sessionId, userId)
                .orElseThrow(() -> new CustomException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND));
    }

    private void validateEditable(InterviewSession session) {
        if (!session.isEditable()) {
            throw new CustomException(ErrorCode.SESSION_NOT_EDITABLE);
        }
    }
}
