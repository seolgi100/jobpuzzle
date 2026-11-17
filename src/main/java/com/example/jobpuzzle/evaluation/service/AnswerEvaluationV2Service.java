package com.example.jobpuzzle.evaluation.service;

import com.example.jobpuzzle.ai.config.AiGenerationProperties;
import com.example.jobpuzzle.ai.dto.AnswerEvaluationV2Result;
import com.example.jobpuzzle.ai.log.AiCallLog;
import com.example.jobpuzzle.ai.log.AiCallLogErrorType;
import com.example.jobpuzzle.ai.log.AiCallLogRepository;
import com.example.jobpuzzle.ai.log.AiExecutionStage;
import com.example.jobpuzzle.ai.log.AiInputReferenceType;
import com.example.jobpuzzle.ai.prompt.PromptTemplate;
import com.example.jobpuzzle.ai.prompt.PromptTemplateRepository;
import com.example.jobpuzzle.ai.service.AiClientService;
import com.example.jobpuzzle.ai.service.GenerationClientSelection;
import com.example.jobpuzzle.ai.validation.AiResponseProcessor;
import com.example.jobpuzzle.analysis.service.AnalysisSummaries;
import com.example.jobpuzzle.evaluation.entity.AnswerEvaluation;
import com.example.jobpuzzle.evaluation.repository.AnswerEvaluationRepository;
import com.example.jobpuzzle.evaluation.v2.AnswerEvaluationV2Validator;
import com.example.jobpuzzle.evaluation.v2.EvaluationCriterion;
import com.example.jobpuzzle.evaluation.v2.ScoreCalculator;
import com.example.jobpuzzle.global.error.CustomException;
import com.example.jobpuzzle.global.error.ErrorCode;
import com.example.jobpuzzle.interview.entity.*;
import com.example.jobpuzzle.interview.repository.FollowUpQuestionRepository;
import com.example.jobpuzzle.interview.repository.InterviewMessageRepository;
import com.example.jobpuzzle.interview.repository.InterviewSessionQuestionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 평가 명세 v2 답변 평가 (JSON-06-V2). stub·live 어느 모드든 같은 파싱·검증·저장을 거친다.
 * 검증에 실패하면 maxRetries번까지 다시 부르고, 그래도 실패하면 그 답변만 FAILED (수정사항 9번: 티켓 반환 없음).
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class AnswerEvaluationV2Service {

    static final String TARGET_JSON = "JSON-06-V2";
    private static final int MAX_FOLLOW_UP_DEPTH = 2;
    // 꼬리질문 기준: 답변 점수 3.5 미만 = 6개 항목 합계 21 미만 (수정사항 10번)
    private static final int FOLLOW_UP_SCORE_SUM = 21;

    private final AnswerEvaluationRepository answerEvaluationRepository;
    private final InterviewMessageRepository interviewMessageRepository;
    private final InterviewSessionQuestionRepository sessionQuestionRepository;
    private final FollowUpQuestionRepository followUpQuestionRepository;
    private final PromptTemplateRepository promptTemplateRepository;
    private final AiCallLogRepository aiCallLogRepository;
    private final AiClientService aiClientService;
    private final AiResponseProcessor aiResponseProcessor;
    private final AiGenerationProperties properties;
    private final ObjectMapper objectMapper;

    public AnswerEvaluationService.EvaluationOutcome evaluate(InterviewMessage answerMessage) {
        InterviewSessionQuestion question = answerMessage.getSessionQuestion();
        InterviewSession session = question.getSession();
        InterviewSessionMode mode = session.getMode();
        List<EvaluationCriterion> criteria = EvaluationCriterion.forMode(mode);

        PromptTemplate template = promptTemplateRepository
                .findFirstByTargetJsonAndIsActiveTrueOrderByPromptTemplateIdDesc(TARGET_JSON)
                .orElseThrow(() -> new CustomException(ErrorCode.AI_PROMPT_TEMPLATE_NOT_FOUND));
        GenerationClientSelection selection = aiClientService.resolve(AiExecutionStage.ANSWER_EVALUATION);
        AiCallLog callLog = AiCallLog.pending(selection.provider(), selection.model(), AiExecutionStage.ANSWER_EVALUATION,
                AiInputReferenceType.ANSWER, String.valueOf(answerMessage.getMessageId()),
                Integer.toHexString(answerMessage.getMessageText().hashCode()), template, session.getGuideDocument(), null);
        callLog.start();
        aiCallLogRepository.save(callLog);

        String prompt = template.getTemplateText() + "\n\n[INPUT]\n" + toJson(input(answerMessage, question, session, criteria));

        AnswerEvaluationV2Result result = null;
        Map<EvaluationCriterion, Integer> scores = null;
        RuntimeException last = null;
        int attempts = 1 + Math.max(0, properties.getMaxRetries());
        for (int attempt = 1; attempt <= attempts && scores == null; attempt++) {
            try {
                result = aiResponseProcessor.parseAnswerEvaluationV2(aiClientService.evaluateAnswer(selection, prompt));
                scores = AnswerEvaluationV2Validator.validate(result, mode, false);
            } catch (RuntimeException exception) {
                last = exception;
                log.warn("ai_evaluation_v2_retry attempt={}/{} answerMessageId={} errorType={} cause={}", attempt, attempts,
                        answerMessage.getMessageId(), EvaluationFailureDiagnostics.errorType(exception),
                        EvaluationFailureDiagnostics.rootCauseSummary(exception));
            }
        }
        if (scores == null) {
            String traceId = EvaluationFailureDiagnostics.newTraceId();
            AiCallLogErrorType errorType = EvaluationFailureDiagnostics.errorType(last);
            callLog.fail(errorType, traceId + " | " + errorType + " | " + EvaluationFailureDiagnostics.failureDetail(last));
            log.error("ai_evaluation_v2_failed traceId={} answerMessageId={} mode={}", traceId, answerMessage.getMessageId(), mode, last);
            return new AnswerEvaluationService.EvaluationOutcome(null, null, true, traceId);
        }

        boolean evasive = Boolean.TRUE.equals(result.isEvasive());
        ScoreCalculator.Answer answer = evasive ? ScoreCalculator.Answer.ofEvasive() : ScoreCalculator.Answer.of(scores);
        int score = ScoreCalculator.calculate(criteria, List.of(new ScoreCalculator.Question(List.of(answer))))
                .questionScores().get(0);

        Map<String, AnswerEvaluation.DimensionEvaluation> details = new LinkedHashMap<>();
        Map<String, String> reasons = new LinkedHashMap<>();
        result.scores().forEach(s -> reasons.put(s.criterion(), s.reason()));
        scores.forEach((c, value) -> details.put(c.name(),
                new AnswerEvaluation.DimensionEvaluation(evasive ? 1 : value, reasons.get(c.name()))));
        Map<String, String> comparison = result.comparison() == null ? null
                : Map.of("improved", result.comparison().improved(), "toImprove", result.comparison().toImprove());

        AnswerEvaluation evaluation = answerEvaluationRepository.save(AnswerEvaluation.createV2(
                answerMessage, question, mode, score, details, evasive, result.strengths(), result.improvements(),
                result.recommendedAnswer(), comparison, callLog));

        int scoreSum = details.values().stream().mapToInt(AnswerEvaluation.DimensionEvaluation::getScore).sum();
        InterviewMessage followUp = scoreSum < FOLLOW_UP_SCORE_SUM && result.followUp() != null
                ? createFollowUp(evaluation, answerMessage, result.followUp(), details)
                : null;
        callLog.succeed();
        return new AnswerEvaluationService.EvaluationOutcome(evaluation, followUp, false, null);
    }

    private InterviewMessage createFollowUp(AnswerEvaluation evaluation, InterviewMessage answerMessage,
                                            AnswerEvaluationV2Result.FollowUp followUp,
                                            Map<String, AnswerEvaluation.DimensionEvaluation> details) {
        long depth = interviewMessageRepository.countBySessionQuestion_SessionQuestionIdAndMessageType(
                evaluation.getSessionQuestion().getSessionQuestionId(), InterviewMessageType.FOLLOW_UP_QUESTION);
        if (depth >= MAX_FOLLOW_UP_DEPTH) {
            return null;
        }
        InterviewMessage message = interviewMessageRepository.save(
                InterviewMessage.followUpQuestion(evaluation.getSessionQuestion(), answerMessage, followUp.question()));
        // 가장 낮은 항목을 꼬리질문이 확인하는 대상으로 남긴다.
        String weakest = details.entrySet().stream()
                .min(Map.Entry.comparingByValue((a, b) -> Integer.compare(a.getScore(), b.getScore())))
                .map(Map.Entry::getKey).orElse(null);
        // ponytail: v2 응답에는 꼬리질문 유형이 없어 SPECIFICITY로 고정. 유형별 화면이 필요해지면 응답에 칸 추가
        followUpQuestionRepository.save(FollowUpQuestion.create(evaluation, message, FollowUpQuestionType.SPECIFICITY,
                weakest, Objects.requireNonNullElse(followUp.reason(), "답변 점수 3.5 미만으로 근거 추가 확인")));
        return message;
    }

    /** 명세 5장 "AI 평가 입력". 다회차 비교는 다회차 단계에서 채운다. */
    private Map<String, Object> input(InterviewMessage answer, InterviewSessionQuestion question,
                                      InterviewSession session, List<EvaluationCriterion> criteria) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("mode", session.getMode().name());
        input.put("question", question.getQuestionTextSnapshot());
        input.put("intent", question.getIntentSnapshot());
        input.put("competencyTags", Objects.requireNonNullElse(question.getQuestion().getCompetencyTags(), List.of()));
        input.put("answer", answer.getMessageText());
        input.put("criteria", criteria.stream().map(c -> Map.of(
                "code", c.name(), "label", c.label(), "question", c.question(),
                "score5", c.score5(), "score3", c.score3(), "score1", c.score1())).toList());

        Map<String, Object> context = new LinkedHashMap<>();
        switch (session.getMode()) {
            case KNOWLEDGE -> context.put("jobRequirements", AnalysisSummaries.requirements(session.getJobPostingAnalysis()));
            case MATERIAL -> {
                context.put("jobRequirements", AnalysisSummaries.requirements(session.getJobPostingAnalysis()));
                context.put("candidate", AnalysisSummaries.candidate(session.getCandidateAnalysis()));
            }
            case PERSONALITY -> {
                context.put("jobCategory", session.getJobCategory() == null ? null : session.getJobCategory().getSubCategory());
                if (session.getCandidateAnalysis() != null) {
                    context.put("candidate", AnalysisSummaries.candidate(session.getCandidateAnalysis()));
                }
                input.put("previousAnswers", previousAnswers(session, question));
            }
            default -> throw new IllegalArgumentException("v2 평가 대상 모드가 아님: " + session.getMode());
        }
        input.put("context", context);
        input.put("previousRound", null);
        return input;
    }

    /** 인성 면접 일관성 확인용: 같은 면접에서 이 질문보다 앞선 질문의 본 답변. */
    private List<Map<String, String>> previousAnswers(InterviewSession session, InterviewSessionQuestion current) {
        List<Map<String, String>> answers = new ArrayList<>();
        for (InterviewSessionQuestion q : sessionQuestionRepository.findBySession_SessionIdOrderByDisplayOrderAsc(session.getSessionId())) {
            if (q.getDisplayOrder() >= current.getDisplayOrder()) break;
            interviewMessageRepository.findBySessionQuestion_SessionQuestionIdAndMessageType(
                            q.getSessionQuestionId(), InterviewMessageType.ORIGINAL_ANSWER)
                    .ifPresent(m -> answers.add(Map.of("question", q.getQuestionTextSnapshot(), "answer", m.getMessageText())));
        }
        return answers;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("평가 입력을 JSON으로 만들 수 없습니다", exception);
        }
    }
}
