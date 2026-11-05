package com.example.jobpuzzle.interview.service;

import com.example.jobpuzzle.analysis.dto.CompanyFitQuestionSetResponse;
import com.example.jobpuzzle.analysis.entity.CandidateMaterialAnalysis;
import com.example.jobpuzzle.analysis.entity.JobPostingAnalysis;
import com.example.jobpuzzle.analysis.repository.CandidateMaterialAnalysisRepository;
import com.example.jobpuzzle.analysis.repository.JobPostingAnalysisRepository;
import com.example.jobpuzzle.analysis.service.CompanyFitQuestionSetQueryService;
import com.example.jobpuzzle.evaluation.service.AnswerEvaluationService;
import com.example.jobpuzzle.evaluation.entity.AnswerEvaluation;
import com.example.jobpuzzle.evaluation.repository.AnswerEvaluationRepository;
import com.example.jobpuzzle.evaluation.entity.WeaknessTagResolveStatus;
import com.example.jobpuzzle.evaluation.repository.WeaknessTagStatusRepository;
import com.example.jobpuzzle.evaluation.repository.WeaknessTagLogRepository;
import com.example.jobpuzzle.evaluation.repository.WeaknessRemediationAttemptRepository;
import com.example.jobpuzzle.evaluation.service.SessionScoreAggregationService;
import com.example.jobpuzzle.evaluation.service.WeaknessTagNormalizer;
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
    private final CompanyFitQuestionSetQueryService companyFitQuestionSetQueryService;
    private final JobPostingAnalysisRepository jobPostingAnalysisRepository;
    private final CandidateMaterialAnalysisRepository candidateMaterialAnalysisRepository;
    private final AnswerEvaluationService answerEvaluationService;
    private final AnswerEvaluationRepository answerEvaluationRepository;
    private final WeaknessTagStatusRepository weaknessTagStatusRepository;
    private final WeaknessTagLogRepository weaknessTagLogRepository;
    private final WeaknessRemediationAttemptRepository weaknessRemediationAttemptRepository;
    private final WeaknessTagNormalizer weaknessTagNormalizer;
    private final SessionScoreAggregationService sessionScoreAggregationService;

    @Transactional(readOnly = true)
    public InterviewModeAvailabilityResponse getAvailableModes(Long userId) {
        boolean companyFitAvailable = questionSetRepository
                .findByUser_UserIdAndInterviewModeAndStatus(
                        userId,
                        InterviewSessionMode.COMPANY_FIT,
                        QuestionSetStatus.ACTIVE
                )
                .stream()
                .anyMatch(questionSet -> !interviewSessionRepository
                        .existsByQuestionSet_QuestionSetId(questionSet.getQuestionSetId()));
        boolean weaknessAvailable = !getUnresolvedWeaknessTags(userId).isEmpty();
        return InterviewModeAvailabilityResponse.builder()
                .modes(List.of(
                        InterviewModeAvailabilityResponse.ModeAvailability.builder()
                                .mode(InterviewSessionMode.BASIC)
                                .available(true)
                                .reason(null)
                                .build(),
                        InterviewModeAvailabilityResponse.ModeAvailability.builder()
                                .mode(InterviewSessionMode.COMPANY_FIT)
                                .available(companyFitAvailable)
                                .reason(companyFitAvailable ? null : "완료된 회사 맞춤 질문 세트가 필요합니다.")
                                .build(),
                        InterviewModeAvailabilityResponse.ModeAvailability.builder()
                                .mode(InterviewSessionMode.WEAKNESS_REVIEW)
                                .available(weaknessAvailable)
                                .reason(weaknessAvailable ? null : "미해결 약점 태그가 필요합니다.")
                                .build()
                ))
                .build();
    }

    @Transactional(readOnly = true)
    public List<String> getUnresolvedWeaknessTags(Long userId) {
        var allLogs = weaknessTagLogRepository.findByUser_UserIdOrderByTagLogIdDesc(userId);
        return weaknessTagStatusRepository
                .findByUser_UserIdAndStatus(userId, WeaknessTagResolveStatus.UNRESOLVED)
                .stream()
                .filter(weakness -> allLogs.stream()
                        .filter(log -> Objects.equals(log.getTag(), weakness.getTag()))
                        .anyMatch(log -> isCompletedSession(log.getSession())))
                .sorted(Comparator.comparing(
                        weakness -> weakness.getLastOccurredAt(),
                        Comparator.nullsLast(Comparator.reverseOrder())
                ))
                .map(weakness -> canonicalStatusTag(weakness.getTag(), allLogs))
                .distinct()
                .toList();
    }

    @Transactional(readOnly = true)
    // 해결(RESOLVED)된 약점 태그를 이름만 정리해 돌려준다. 다시 약점으로 잡히면 미해결 목록으로 옮겨 간다.
    public List<WeaknessTagResponse> getResolvedWeaknessTags(Long userId) {
        return weaknessTagStatusRepository
                .findByUser_UserIdAndStatus(userId, WeaknessTagResolveStatus.RESOLVED)
                .stream()
                .map(status -> weaknessTagNormalizer.canonicalTag(status.getTag()))
                .distinct()
                .map(tag -> WeaknessTagResponse.builder()
                        .tag(tag)
                        .displayName(weaknessTagNormalizer.displayName(tag))
                        .description(weaknessTagNormalizer.displayName(tag) + " 기준을 넘겨 해결했어요.")
                        .recentOccurrences(List.of())
                        .build())
                .toList();
    }

    public List<WeaknessTagResponse> getUnresolvedWeaknessTagDetails(Long userId) {
        var allLogs = weaknessTagLogRepository.findByUser_UserIdOrderByTagLogIdDesc(userId);
        var statuses = weaknessTagStatusRepository
                .findByUser_UserIdAndStatus(userId, WeaknessTagResolveStatus.UNRESOLVED)
                .stream()
                .filter(weakness -> allLogs.stream()
                        .filter(log -> Objects.equals(log.getTag(), weakness.getTag()))
                        .anyMatch(log -> isCompletedSession(log.getSession())))
                .collect(Collectors.groupingBy(
                        weakness -> canonicalStatusTag(weakness.getTag(), allLogs)
                ));
        var allAttempts = weaknessRemediationAttemptRepository
                .findByOriginTagLog_User_UserIdOrderByAttemptIdDesc(userId);
        Map<Long, com.example.jobpuzzle.evaluation.dto.SessionScoreSummary> scoreCache = new HashMap<>();
        return statuses.entrySet().stream()
                .map(entry -> {
                    String canonicalTag = entry.getKey();
                    var dimensionLogs = allLogs.stream()
                            .filter(log -> logBelongsToDimension(log, canonicalTag))
                            .filter(log -> isCompletedSession(log.getSession()))
                            .toList();
                    Map<Long, List<com.example.jobpuzzle.evaluation.entity.WeaknessTagLog>> logsBySession =
                            dimensionLogs.stream()
                                    .filter(log -> log.getSession() != null)
                                    .collect(Collectors.groupingBy(
                                            log -> log.getSession().getSessionId(),
                                            LinkedHashMap::new,
                                            Collectors.toList()
                                    ));
                    List<WeaknessTagResponse.Occurrence> occurrences = logsBySession.values().stream()
                            .map(originLogs -> buildWeaknessOccurrence(
                                    userId, canonicalTag, originLogs, allAttempts, scoreCache))
                            .filter(occurrence -> occurrence.getScore() == null
                                    || occurrence.getScore() < 70)
                            .sorted(Comparator.comparing(
                                    WeaknessTagResponse.Occurrence::getOccurredAt,
                                    Comparator.nullsLast(Comparator.reverseOrder())))
                            .limit(10)
                            .toList();
                    int unresolvedCount = (int) occurrences.stream()
                            .filter(occurrence -> !"RESOLVED".equals(occurrence.getStatus()))
                            .count();
                    return WeaknessTagResponse.builder()
                            .tag(canonicalTag)
                            .displayName(weaknessTagNormalizer.displayName(canonicalTag))
                            .description(weaknessTagNormalizer.displayName(canonicalTag)
                                    + "이 반복해서 확인되었습니다. 최근 평가를 바탕으로 집중 연습합니다.")
                            .occurrenceCount(occurrences.size())
                            .unresolvedCount(unresolvedCount)
                            .recentAttemptCount(occurrences.stream()
                                    .mapToInt(occurrence -> occurrence.getAttempts().size())
                                    .sum())
                            .recentOccurrences(occurrences)
                            .build();
                })
                .filter(item -> item.getUnresolvedCount() > 0)
                .sorted(Comparator.comparing(
                        item -> item.getRecentOccurrences().isEmpty()
                                ? null : item.getRecentOccurrences().get(0).getOccurredAt(),
                        Comparator.nullsLast(Comparator.reverseOrder())
                ))
                .toList();
    }

    private String canonicalStatusTag(
            String storedTag,
            List<com.example.jobpuzzle.evaluation.entity.WeaknessTagLog> allLogs
    ) {
        return allLogs.stream()
                .filter(log -> Objects.equals(log.getTag(), storedTag))
                .map(com.example.jobpuzzle.evaluation.entity.WeaknessTagLog::getEvaluation)
                .filter(Objects::nonNull)
                .map(this::singleFailedDimension)
                .filter(Objects::nonNull)
                .findFirst()
                .map(dimension -> dimension + "_weak")
                .orElseGet(() -> weaknessTagNormalizer.canonicalTag(storedTag));
    }

    private boolean logBelongsToDimension(
            com.example.jobpuzzle.evaluation.entity.WeaknessTagLog log,
            String canonicalTag
    ) {
        String targetDimension = weaknessTagNormalizer.dimension(canonicalTag);
        AnswerEvaluation evaluation = log.getEvaluation();
        if (evaluation != null && evaluation.getEvaluationDetail() != null) {
            AnswerEvaluation.DimensionEvaluation detail =
                    evaluation.getEvaluationDetail().get(targetDimension);
            if (detail != null && detail.getScore() != null
                    && detail.getScore() < evaluation.getPassThreshold()) {
                return true;
            }
        }
        return weaknessTagNormalizer.sameDimension(log.getTag(), canonicalTag);
    }

    private String singleFailedDimension(AnswerEvaluation evaluation) {
        if (evaluation.getEvaluationDetail() == null) {
            return null;
        }
        List<String> failed = evaluation.getEvaluationDetail().entrySet().stream()
                .filter(entry -> entry.getValue() != null
                        && entry.getValue().getScore() != null
                        && entry.getValue().getScore() < evaluation.getPassThreshold())
                .map(Map.Entry::getKey)
                .toList();
        return failed.size() == 1 ? failed.get(0) : null;
    }

    private WeaknessTagResponse.Occurrence buildWeaknessOccurrence(
            Long userId,
            String canonicalTag,
            List<com.example.jobpuzzle.evaluation.entity.WeaknessTagLog> originLogs,
            List<com.example.jobpuzzle.evaluation.entity.WeaknessRemediationAttempt> allAttempts,
            Map<Long, com.example.jobpuzzle.evaluation.dto.SessionScoreSummary> scoreCache
    ) {
        var representative = originLogs.get(0);
        Set<Long> originLogIds = originLogs.stream()
                .map(com.example.jobpuzzle.evaluation.entity.WeaknessTagLog::getTagLogId)
                .collect(Collectors.toSet());
        Map<Long, List<com.example.jobpuzzle.evaluation.entity.WeaknessRemediationAttempt>> attemptsBySession =
                allAttempts.stream()
                        .filter(attempt -> originLogIds.contains(attempt.getOriginTagLog().getTagLogId()))
                        .filter(attempt -> isCompletedSession(attempt.getSession()))
                        .collect(Collectors.groupingBy(
                                attempt -> attempt.getSession().getSessionId(),
                                LinkedHashMap::new,
                                Collectors.toList()
                        ));
        List<WeaknessTagResponse.Attempt> attempts = attemptsBySession.values().stream()
                .map(sessionAttempts -> buildWeaknessAttempt(
                        userId, canonicalTag, sessionAttempts, scoreCache))
                .sorted(Comparator.comparing(
                        WeaknessTagResponse.Attempt::getOccurredAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        String status = attempts.isEmpty()
                ? "PENDING"
                : attempts.get(attempts.size() - 1).getStatus();
        Long sessionId = representative.getSession().getSessionId();
        return WeaknessTagResponse.Occurrence.builder()
                .sessionId(sessionId)
                .evaluationId(representative.getEvaluation() == null
                        ? null : representative.getEvaluation().getEvaluationId())
                .mode(representative.getSession().getMode() == null
                        ? null : representative.getSession().getMode().name())
                .score(dimensionScore(userId, sessionId, canonicalTag, scoreCache,
                        representative.getEvaluation() == null ? null : representative.getEvaluation().getScore()))
                .occurredAt(completedAtOf(representative.getSession()))
                .status(status)
                .resultLabel(resultLabel(representative.getSession()))
                .diagnostics(diagnosticsForLogs(originLogs, canonicalTag))
                .attempts(attempts)
                .build();
    }

    private WeaknessTagResponse.Attempt buildWeaknessAttempt(
            Long userId,
            String canonicalTag,
            List<com.example.jobpuzzle.evaluation.entity.WeaknessRemediationAttempt> sessionAttempts,
            Map<Long, com.example.jobpuzzle.evaluation.dto.SessionScoreSummary> scoreCache
    ) {
        var representative = sessionAttempts.get(0);
        Long sessionId = representative.getSession().getSessionId();
        Integer score = dimensionScore(userId, sessionId, canonicalTag, scoreCache,
                sessionAttempts.stream()
                        .map(attempt -> attempt.getEvaluation().getScore())
                        .filter(Objects::nonNull)
                        .mapToInt(Integer::intValue)
                        .average()
                        .stream()
                        .mapToObj(value -> (int) Math.round(value))
                        .findFirst()
                        .orElse(null));
        return WeaknessTagResponse.Attempt.builder()
                .sessionId(sessionId)
                .evaluationId(representative.getEvaluation().getEvaluationId())
                .score(score)
                .occurredAt(completedAtOf(representative.getSession()))
                .status(score != null && score >= 70 ? "RESOLVED" : "UNRESOLVED")
                .resultLabel(resultLabel(representative.getSession()))
                .diagnostics(diagnosticsForEvaluations(sessionAttempts.stream()
                        .map(com.example.jobpuzzle.evaluation.entity.WeaknessRemediationAttempt::getEvaluation)
                        .toList(), canonicalTag))
                .build();
    }

    private Integer dimensionScore(
            Long userId,
            Long sessionId,
            String canonicalTag,
            Map<Long, com.example.jobpuzzle.evaluation.dto.SessionScoreSummary> scoreCache,
            Integer fallback
    ) {
        try {
            var summary = scoreCache.computeIfAbsent(
                    sessionId,
                    ignored -> sessionScoreAggregationService.aggregate(userId, sessionId));
            return summary.getCategoryScores().getOrDefault(
                    weaknessTagNormalizer.dimension(canonicalTag), fallback);
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private List<String> diagnosticsForLogs(
            List<com.example.jobpuzzle.evaluation.entity.WeaknessTagLog> logs,
            String canonicalTag
    ) {
        List<AnswerEvaluation> evaluations = logs.stream()
                .map(com.example.jobpuzzle.evaluation.entity.WeaknessTagLog::getEvaluation)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        List<String> diagnostics = diagnosticsForEvaluations(evaluations, canonicalTag);
        if (!diagnostics.isEmpty()) {
            return diagnostics;
        }
        // 구형 데이터는 관점별 진단 컬럼이 없으므로 기존 태그를 표시용으로만 해석한다.
        return logs.stream()
                .map(com.example.jobpuzzle.evaluation.entity.WeaknessTagLog::getTag)
                .map(weaknessTagNormalizer::diagnosticDisplayName)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
    }

    private List<String> diagnosticsForEvaluations(
            List<AnswerEvaluation> evaluations,
            String canonicalTag
    ) {
        String dimension = weaknessTagNormalizer.dimension(canonicalTag);
        return evaluations.stream()
                .flatMap(evaluation -> {
                    Map<String, List<String>> values = evaluation.getWeaknessDiagnostics();
                    if (values == null) {
                        return java.util.stream.Stream.<String>empty();
                    }
                    return values.getOrDefault(dimension, List.of()).stream();
                })
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
    }

    private boolean isCompletedSession(InterviewSession session) {
        return session != null && session.getStatus() == InterviewSessionStatus.COMPLETED;
    }

    private LocalDateTime completedAtOf(InterviewSession session) {
        if (session == null) return null;
        return session.getCompletedAt() == null ? session.getCreatedAt() : session.getCompletedAt();
    }

    private String resultLabel(InterviewSession session) {
        if (session == null) {
            return "결과 보기";
        }
        String mode = session.getMode() == InterviewSessionMode.WEAKNESS_REVIEW
                ? "약점 보완" : "맞춤 면접";
        return session.getStatus() == InterviewSessionStatus.COMPLETED
                ? mode + " 리포트 보기"
                : mode + " 중간 결과 보기";
    }

    @Transactional(readOnly = true)
    public SessionResponse getActiveSession(Long userId) {
        return interviewSessionRepository
                .findByUser_UserIdAndStatusInAndDeletedAtIsNullOrderByCreatedAtDesc(
                        userId, UNFINISHED_STATUSES)
                .stream()
                .filter(session -> !isReviewReady(session))
                .sorted(latestActivityFirst())
                .findFirst()
                .map(session -> SessionResponse.from(
                        session,
                        Math.toIntExact(sessionQuestionRepository.countBySession_SessionId(session.getSessionId())),
                        hasAnyAnswer(session.getSessionId())
                ))
                .orElse(null);
    }

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

        if (questionSet.getInterviewMode() == InterviewSessionMode.COMPANY_FIT) {
            validateCompanyFitSelection(userId, questionSet, selectedIds);
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

        InterviewSession session = interviewSessionRepository.save(
                questionSet.getInterviewMode() == InterviewSessionMode.COMPANY_FIT
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

    // COMPANY_FIT 세션 생성 당시 스냅샷에 연결된 분석 결과를 고정해서 저장한다.
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

    public SessionResponse startSession(Long userId, Long sessionId) {
        InterviewSession session = getOwnedSession(userId, sessionId);
        validateEditable(session);
        session.start();
        return SessionResponse.from(
                session,
                Math.toIntExact(sessionQuestionRepository.countBySession_SessionId(sessionId))
        );
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

        AnswerEvaluationService.EvaluationOutcome outcome =
                answerEvaluationService.evaluateAnswer(answer);
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
        if (outcome.retryAnswerRequired()) {
            answer.rejectAnswer();
            if (attemptsExhausted) {
                sessionQuestion.complete();
            }
            SessionQuestionResponse nextQuestion = attemptsExhausted
                    ? getNextQuestion(userId, session.getSessionId())
                    : SessionQuestionResponse.from(sessionQuestion);
            return AnswerSubmitResponse.builder()
                    .answerMessageId(answer.getMessageId())
                    .summary(attemptsExhausted
                            ? "답변을 평가할 수 없어 이 질문을 미평가로 종료합니다."
                            : outcome.retryAnswerMessage())
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
                    .nextQuestion(nextQuestion)
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
        resolveWeaknessIfEligible(session, sessionQuestions);

        // 면접 기능 추가: 정상 완료된 QuestionSet은 미선택 질문까지 더 이상 재사용하지 않는다.
        session.getQuestionSet().archive();
        return SessionResponse.from(
                session,
                Math.toIntExact(sessionQuestionRepository.countBySession_SessionId(sessionId))
        );
    }

    public SessionResponse finishSelectedQuestions(Long userId, Long sessionId) {
        InterviewSession session = getOwnedSession(userId, sessionId);
        validateEditable(session);
        List<InterviewSessionQuestion> questions =
                sessionQuestionRepository.findBySession_SessionIdOrderByDisplayOrderAsc(sessionId);
        questions.forEach(question -> {
            if (hasOriginalAnswer(question.getSessionQuestionId())) {
                question.complete();
            } else {
                question.defer();
            }
        });
        session.touch();
        return SessionResponse.from(session, questions.size(), hasAnyAnswer(sessionId));
    }

    public SessionQuestionResponse deferCurrentQuestion(
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
        question.defer();
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

    private void resolveWeaknessIfEligible(
            InterviewSession session,
            List<InterviewSessionQuestion> sessionQuestions
    ) {
        if (session.getMode() != InterviewSessionMode.WEAKNESS_REVIEW
                || session.getTargetWeaknessTag() == null
                || session.getTargetDimension() == null) {
            return;
        }
        List<com.example.jobpuzzle.evaluation.entity.WeaknessTagLog> origins =
                weaknessTagLogRepository.findByUser_UserIdOrderByTagLogIdDesc(
                                session.getUser().getUserId())
                        .stream()
                        .filter(origin -> weaknessTagNormalizer.sameDimension(
                                origin.getTag(), session.getTargetWeaknessTag()))
                        .toList();
        boolean allOriginsResolved = !origins.isEmpty() && origins.stream().allMatch(origin ->
                weaknessRemediationAttemptRepository
                        .findFirstByOriginTagLog_TagLogIdOrderByAttemptIdDesc(origin.getTagLogId())
                        .map(attempt -> attempt.getStatus()
                                == com.example.jobpuzzle.evaluation.entity.WeaknessRemediationStatus.RESOLVED)
                        .orElse(false));
        if (!allOriginsResolved) {
            return;
        }
        weaknessTagStatusRepository
                .findByUser_UserIdAndTag(
                        session.getUser().getUserId(),
                        session.getTargetWeaknessTag())
                .filter(status -> status.getStatus() == WeaknessTagResolveStatus.UNRESOLVED)
                .ifPresent(status -> status.resolve(session));
    }

    private boolean passesTargetDimension(
            InterviewSessionQuestion question,
            String targetDimension
    ) {
        List<Integer> scores = answerEvaluationRepository
                .findBySessionQuestion_SessionQuestionIdOrderByEvaluationIdAsc(
                        question.getSessionQuestionId())
                .stream()
                .map(AnswerEvaluation::getEvaluationDetail)
                .map(details -> details.get(targetDimension))
                .filter(Objects::nonNull)
                .map(AnswerEvaluation.DimensionEvaluation::getScore)
                .filter(Objects::nonNull)
                .toList();
        return !scores.isEmpty()
                && scores.stream().mapToInt(Integer::intValue).average().orElse(0) >= 70;
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

    private void validateCompanyFitSelection(
            Long userId,
            QuestionSet questionSet,
            List<Long> selectedIds
    ) {
        if (questionSet.getSnapshot() == null || questionSet.getSnapshot().getAnalysisCase() == null) {
            throw new CustomException(ErrorCode.QUESTION_SET_NOT_READY);
        }
        CompanyFitQuestionSetResponse response =
                companyFitQuestionSetQueryService.getCompanyFitQuestionSet(
                        userId,
                        questionSet.getSnapshot().getAnalysisCase().getAnalysisCaseId()
                );
        if (!response.isCanGenerateQuestions()
                || !Objects.equals(response.getQuestionSetId(), questionSet.getQuestionSetId())) {
            throw new CustomException(ErrorCode.QUESTION_SET_NOT_READY);
        }
        Set<Long> availableIds = response.getQuestions().stream()
                .map(CompanyFitQuestionSetResponse.Question::getQuestionId)
                .collect(Collectors.toSet());
        if (!availableIds.containsAll(selectedIds)) {
            throw new CustomException(ErrorCode.INVALID_QUESTION_SELECTION);
        }
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

    private String displayWeaknessTag(String tag) {
        if (tag == null) return "확인이 필요한 답변";
        return switch (tag) {
            case "requirementConnection_weak", "requirementConnection_insufficient", "요구사항 연결부족" ->
                    "공고 요구사항 연결 부족";
            case "specificity_weak", "specificity_insufficient", "구체성 부족" ->
                    "경험 구체성 부족";
            case "ownRole_weak", "ownRole_insufficient" -> "본인 역할 설명 부족";
            case "problemSolving_weak", "problemSolving_insufficient" -> "문제 해결 과정 부족";
            case "resultExpression_weak", "resultExpression_insufficient" -> "성과·결과 표현 부족";
            case "guideAlignment_weak", "guideAlignment_insufficient" -> "직무 가이드 연결 부족";
            case "deliveryClarity_weak", "deliveryClarity_insufficient" -> "답변 전달력 부족";
            case "intentMatch_weak", "intentMatch_insufficient", "질문의도미파악" -> "질문 의도 파악 부족";
            case "기술스택 언급없음" -> "필요 기술 경험 설명 부족";
            case "경험연결부족" -> "관련 경험 연결 부족";
            default -> tag.replace("#", "").replace("_weak", "").replace("_insufficient", "");
        };
    }

    private String weaknessDescription(String tag) {
        String name = displayWeaknessTag(tag);
        return name + "이 반복해서 확인되었습니다. 최근 평가를 바탕으로 집중 연습합니다.";
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
                messages
        );
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
