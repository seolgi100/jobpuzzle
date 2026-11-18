package com.example.jobpuzzle.evaluation.service;

import com.example.jobpuzzle.analysis.entity.AnalysisCaseSource;
import com.example.jobpuzzle.analysis.repository.AnalysisCaseSourceRepository;
import com.example.jobpuzzle.competency.service.CompetencyService;
import com.example.jobpuzzle.document.entity.UserDocumentType;
import com.example.jobpuzzle.evaluation.dto.SessionReportV2Response;
import com.example.jobpuzzle.evaluation.entity.AnswerEvaluation;
import com.example.jobpuzzle.evaluation.repository.AnswerEvaluationRepository;
import com.example.jobpuzzle.evaluation.v2.EvaluationCriterion;
import com.example.jobpuzzle.evaluation.v2.ScoreCalculator;
import com.example.jobpuzzle.global.error.CustomException;
import com.example.jobpuzzle.global.error.ErrorCode;
import com.example.jobpuzzle.interview.entity.InterviewMessage;
import com.example.jobpuzzle.interview.entity.InterviewMessageType;
import com.example.jobpuzzle.interview.entity.InterviewSession;
import com.example.jobpuzzle.interview.entity.InterviewSessionMode;
import com.example.jobpuzzle.interview.entity.InterviewSessionQuestion;
import com.example.jobpuzzle.interview.repository.InterviewMessageRepository;
import com.example.jobpuzzle.interview.repository.InterviewSessionQuestionRepository;
import com.example.jobpuzzle.interview.repository.InterviewSessionRepository;
import com.example.jobpuzzle.jobcategory.entity.JobCategory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 평가 명세 v2 6장 최종 보고서와 3장 점수 계산을 세션 단위로 묶는다. AI 호출 없음.
 * 점수는 ScoreCalculator 한 곳에서만 계산하고, /score 집계(SessionScoreAggregationService)도 v2 면접은 여기 결과를 쓴다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SessionReportV2Service {

    private static final Set<InterviewMessageType> ANSWERS =
            Set.of(InterviewMessageType.ORIGINAL_ANSWER, InterviewMessageType.FOLLOW_UP_ANSWER);
    private static final Set<InterviewMessageType> SHOWN =
            Set.of(InterviewMessageType.ORIGINAL_ANSWER, InterviewMessageType.FOLLOW_UP_QUESTION,
                    InterviewMessageType.FOLLOW_UP_ANSWER);
    private static final Map<InterviewSessionMode, String> MODE_LABEL = Map.of(
            InterviewSessionMode.KNOWLEDGE, "기초 지식 면접",
            InterviewSessionMode.MATERIAL, "자료 기반 면접",
            InterviewSessionMode.PERSONALITY, "인성 면접");

    private final InterviewSessionRepository sessionRepository;
    private final InterviewSessionQuestionRepository sessionQuestionRepository;
    private final InterviewMessageRepository messageRepository;
    private final AnswerEvaluationRepository evaluationRepository;
    private final AnalysisCaseSourceRepository analysisCaseSourceRepository;
    private final CompetencyService competencyService;

    public SessionReportV2Response report(Long userId, Long sessionId) {
        InterviewSession session = sessionRepository.findBySessionIdAndUser_UserIdAndDeletedAtIsNull(sessionId, userId)
                .orElseThrow(() -> new CustomException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND));
        if (!session.getMode().isV2()) throw new CustomException(ErrorCode.INVALID_REQUEST);
        return build(session);
    }

    /** 소유권은 호출자가 확인한 세션. */
    public SessionReportV2Response build(InterviewSession session) {
        List<EvaluationCriterion> criteria = EvaluationCriterion.forMode(session.getMode());
        JobCategory job = session.getJobCategory();
        Map<String, String> tagNames = competencyService.availableTags(job.getMainCategory(), job.getSubCategory());

        Calculated calc = calculate(session, criteria);
        List<SessionReportV2Response.Question> questions = new ArrayList<>();
        for (int i = 0; i < calc.data().size(); i++) {
            questions.add(calc.data().get(i).toResponse(calc.scores().get(i), criteria, tagNames));
        }
        int total = calc.data().size();
        int answeredCount = (int) calc.data().stream().filter(QuestionData::answered).count();
        int evaluated = (int) questions.stream().filter(q -> "EVALUATED".equals(q.status())).count();
        ScoreCalculator.Result result = calc.result();

        // 다회차 비교는 바로 앞 회차 총점만 (같은 회차 줄 = 같은 모드)
        InterviewSession previous = session.getPreviousSession();
        Integer previousTotal = previous != null && previous.isCompleted()
                ? calculate(previous, criteria).result().total() : null;

        return new SessionReportV2Response(
                session.getSessionId(),
                session.getMode().name(),
                MODE_LABEL.get(session.getMode()),
                session.getRoundNumber(),
                previous == null ? null : previous.getSessionId(),
                session.isCompleted(),
                session.getCompletedAt(),
                title(session),
                session.getSnapshot() == null ? null : session.getSnapshot().getAnalysisCase().getAnalysisCaseId(),
                result.total(),
                previousTotal,
                criteria.stream().map(c -> new SessionReportV2Response.Criterion(c.name(), c.label(),
                        result.criterionTotals().get(c))).toList(),
                new SessionReportV2Response.Tally(total, answeredCount, evaluated, total - answeredCount,
                        total == 0 ? 0 : (int) Math.round(answeredCount * 100.0 / total)),
                questions);
    }

    /** scores: data와 같은 순서의 질문 점수 (답하지 않았거나 평가가 모두 실패한 질문은 null). */
    private record Calculated(List<QuestionData> data, List<Integer> scores, ScoreCalculator.Result result) {
    }

    // 답한 질문만 점수 계산에 넣는다 (답하지 않은 질문은 건너뜀으로 빠짐)
    private Calculated calculate(InterviewSession session, List<EvaluationCriterion> criteria) {
        List<QuestionData> data = sessionQuestionRepository.findBySession_SessionIdOrderByDisplayOrderAsc(session.getSessionId())
                .stream().map(this::load).toList();
        List<QuestionData> answered = data.stream().filter(QuestionData::answered).toList();
        ScoreCalculator.Result result = ScoreCalculator.calculate(criteria,
                answered.stream().map(q -> q.toCalculatorQuestion(criteria)).toList());
        List<Integer> scores = new ArrayList<>();
        int next = 0;
        for (QuestionData q : data) scores.add(q.answered() ? result.questionScores().get(next++) : null);
        return new Calculated(data, scores, result);
    }

    // 공고로 본 면접은 공고 이름, 인성 면접은 직무 이름
    private String title(InterviewSession session) {
        if (session.getSnapshot() != null) {
            String posting = analysisCaseSourceRepository
                    .findByAnalysisCase_AnalysisCaseIdOrderByAnalysisCaseSourceIdAsc(
                            session.getSnapshot().getAnalysisCase().getAnalysisCaseId()).stream()
                    .filter(source -> source.getDocumentType() == UserDocumentType.JOB_POSTING)
                    .map(AnalysisCaseSource::getExtraction)
                    .map(extraction -> extraction.getDocument().getDisplayName())
                    .findFirst().orElse(null);
            if (posting != null) return posting;
        }
        return session.getJobCategory().getSubCategory() + " " + MODE_LABEL.get(session.getMode());
    }

    private QuestionData load(InterviewSessionQuestion question) {
        List<InterviewMessage> messages = messageRepository
                .findBySessionQuestion_SessionQuestionIdOrderByMessageIdAsc(question.getSessionQuestionId());
        Map<Long, AnswerEvaluation> evaluations = evaluationRepository
                .findBySessionQuestion_SessionQuestionIdOrderByEvaluationIdAsc(question.getSessionQuestionId()).stream()
                .filter(e -> Integer.valueOf(2).equals(e.getEvaluationVersion()))
                .collect(Collectors.toMap(e -> e.getAnswerMessage().getMessageId(), Function.identity(), (a, b) -> a));
        return new QuestionData(question, messages, evaluations);
    }

    private record QuestionData(InterviewSessionQuestion question, List<InterviewMessage> messages,
                                Map<Long, AnswerEvaluation> evaluations) {

        List<InterviewMessage> answers() {
            return messages.stream().filter(m -> ANSWERS.contains(m.getMessageType())).toList();
        }

        boolean answered() {
            return !answers().isEmpty();
        }

        // 평가가 없는 답변(평가 실패)은 계산에서 빠진다 (명세 3장)
        ScoreCalculator.Question toCalculatorQuestion(List<EvaluationCriterion> criteria) {
            return new ScoreCalculator.Question(answers().stream().map(answer -> {
                AnswerEvaluation evaluation = evaluations.get(answer.getMessageId());
                if (evaluation == null) return ScoreCalculator.Answer.ofFailed();
                if (Boolean.TRUE.equals(evaluation.getEvasive())) return ScoreCalculator.Answer.ofEvasive();
                Map<EvaluationCriterion, Integer> scores = new LinkedHashMap<>();
                criteria.forEach(c -> scores.put(c, evaluation.getEvaluationDetail().get(c.name()).getScore()));
                return ScoreCalculator.Answer.of(scores);
            }).toList());
        }

        SessionReportV2Response.Question toResponse(Integer score, List<EvaluationCriterion> criteria,
                                                    Map<String, String> tagNames) {
            AnswerEvaluation main = messages.stream()
                    .filter(m -> m.getMessageType() == InterviewMessageType.ORIGINAL_ANSWER)
                    .map(m -> evaluations.get(m.getMessageId()))
                    .filter(Objects::nonNull)
                    .findFirst().orElse(null);
            List<String> codes = question.getQuestion().getCompetencyTags();
            String status = !answered() ? "SKIPPED" : score == null ? "FAILED" : "EVALUATED";
            List<SessionReportV2Response.Item> items = main == null ? List.of() : criteria.stream().map(c -> {
                AnswerEvaluation.DimensionEvaluation detail = main.getEvaluationDetail().get(c.name());
                return new SessionReportV2Response.Item(c.name(), c.label(),
                        detail == null ? null : detail.getScore(), detail == null ? null : detail.getComment());
            }).toList();
            return new SessionReportV2Response.Question(
                    question.getSessionQuestionId(),
                    question.getDisplayOrder(),
                    question.getQuestionTextSnapshot(),
                    question.getIntentSnapshot(),
                    codes == null ? List.of() : codes.stream().map(code -> tagNames.getOrDefault(code, code)).toList(),
                    codes == null ? List.of() : List.copyOf(codes),
                    status,
                    score,
                    messages.stream().filter(m -> SHOWN.contains(m.getMessageType()))
                            .map(m -> new SessionReportV2Response.Message(m.getMessageType().name(), m.getMessageText()))
                            .toList(),
                    items,
                    main == null ? null : main.getSummary(),
                    main == null || main.getImprovementDirection().isEmpty() ? null : main.getImprovementDirection().get(0),
                    main == null ? null : main.getRecommendedAnswer(),
                    main == null || main.getComparison() == null ? null
                            : new SessionReportV2Response.Comparison(main.getComparison().get("improved"),
                            main.getComparison().get("toImprove")));
        }
    }

}
