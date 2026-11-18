package com.example.jobpuzzle.simulation;

import com.example.jobpuzzle.analysis.entity.AnalysisCase;
import com.example.jobpuzzle.analysis.entity.AnalysisCaseSource;
import com.example.jobpuzzle.analysis.entity.AnalysisCaseStatus;
import com.example.jobpuzzle.analysis.repository.AnalysisCaseRepository;
import com.example.jobpuzzle.analysis.repository.AnalysisCaseSourceRepository;
import com.example.jobpuzzle.evaluation.entity.AnswerEvaluation;
import com.example.jobpuzzle.evaluation.repository.AnswerEvaluationRepository;
import com.example.jobpuzzle.global.error.CustomException;
import com.example.jobpuzzle.global.error.ErrorCode;
import com.example.jobpuzzle.interview.entity.FollowUpQuestion;
import com.example.jobpuzzle.interview.entity.InterviewMessage;
import com.example.jobpuzzle.interview.entity.InterviewQuestion;
import com.example.jobpuzzle.interview.entity.InterviewSessionMode;
import com.example.jobpuzzle.interview.repository.FollowUpQuestionRepository;
import com.example.jobpuzzle.interview.repository.InterviewQuestionRepository;
import com.example.jobpuzzle.user.entity.User;
import com.example.jobpuzzle.user.repository.UserRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * 시연용 시뮬레이션 계정 (설정 app.simulation.login-ids).
 * - 분석: 같은 공고·자료·직무로 이미 끝난 분석이 있으면 AI 대신 그 결과를 다시 보여 준다 (숨겨 둔 것이면 다시 보이게).
 * - 질문 생성·답변 평가: 이 계정에 저장된 질문·평가를 AI 응답 모양(JSON)으로 다시 만들어 넘긴다.
 *   파싱·검증·저장·티켓은 평소 흐름 그대로 탄다. 쓸 결과가 없으면 Optional.empty → 평소처럼 AI 호출.
 * - 삭제는 화면에서만 숨기고 DB에는 남긴다. 몇 번이든 다시 돌릴 수 있다.
 */
@Service
@RequiredArgsConstructor
public class SimulationService {

    private final SimulationProperties properties;
    private final UserRepository userRepository;
    private final AnalysisCaseRepository analysisCaseRepository;
    private final AnalysisCaseSourceRepository sourceRepository;
    private final InterviewQuestionRepository interviewQuestionRepository;
    private final AnswerEvaluationRepository answerEvaluationRepository;
    private final FollowUpQuestionRepository followUpQuestionRepository;
    private final ObjectMapper objectMapper;

    public boolean isSimulation(User user) {
        return user != null && properties.getLoginIds().contains(user.getLoginId());
    }

    public boolean isSimulation(Long userId) {
        return !properties.getLoginIds().isEmpty() && userRepository.findById(userId).map(this::isSimulation).orElse(false);
    }

    /** 로딩 흉내. 설정한 범위에서 무작위로 기다린다. */
    public void pause() {
        long min = properties.getMinDelay().toMillis();
        long max = Math.max(min, properties.getMaxDelay().toMillis());
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(min, max + 1));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    // ───────── 분석 ─────────

    /** 같은 직무·같은 자료(추출본) 조합으로 이미 끝난 분석. 시뮬레이션 계정이 아니면 항상 비어 있다. */
    @Transactional(readOnly = true)
    public Optional<AnalysisCase> reusableCase(Long userId, Long analysisCaseId) {
        if (!isSimulation(userId)) return Optional.empty();
        AnalysisCase target = analysisCaseRepository.findByAnalysisCaseIdAndUser_UserId(analysisCaseId, userId).orElse(null);
        if (target == null) return Optional.empty();
        Set<Long> inputs = extractionIds(analysisCaseId);
        Long jobCategoryId = target.getJobCategory().getJobCategoryId();
        return analysisCaseRepository.findByUser_UserIdAndStatusOrderByAnalysisCaseIdDesc(userId, AnalysisCaseStatus.COMPLETED)
                .stream()
                .filter(c -> !c.getAnalysisCaseId().equals(analysisCaseId))
                .filter(c -> c.getJobCategory().getJobCategoryId().equals(jobCategoryId))
                .filter(c -> extractionIds(c.getAnalysisCaseId()).equals(inputs))
                .findFirst();
    }

    /** 새로 만든 빈 분석은 숨기고, 저장된 결과를 다시 보이게 한다. 결과가 있는 분석 번호를 돌려준다. */
    @Transactional
    public Long takeOver(Long userId, Long newCaseId, Long storedCaseId) {
        analysisCaseRepository.findByAnalysisCaseIdAndUser_UserId(newCaseId, userId).ifPresent(AnalysisCase::hide);
        analysisCaseRepository.findByAnalysisCaseIdAndUser_UserId(storedCaseId, userId)
                .orElseThrow(() -> new CustomException(ErrorCode.ANALYSIS_CASE_NOT_FOUND)).show();
        return storedCaseId;
    }

    /** 시뮬레이션 계정의 분석 삭제: 목록에서만 숨긴다. */
    @Transactional
    public void hide(Long userId, Long analysisCaseId) {
        if (!isSimulation(userId)) throw new CustomException(ErrorCode.ACCESS_DENIED);
        AnalysisCase analysisCase = analysisCaseRepository.findByAnalysisCaseIdAndUser_UserId(analysisCaseId, userId)
                .orElseThrow(() -> new CustomException(ErrorCode.ANALYSIS_CASE_NOT_FOUND));
        if (analysisCase.getStatus() == AnalysisCaseStatus.ANALYZING) throw new CustomException(ErrorCode.ANALYSIS_CASE_NOT_READY);
        analysisCase.hide();
    }

    private Set<Long> extractionIds(Long analysisCaseId) {
        return sourceRepository.findByAnalysisCase_AnalysisCaseIdOrderByAnalysisCaseSourceIdAsc(analysisCaseId).stream()
                .map(source -> source.getExtraction().getExtractionId()).collect(Collectors.toSet());
    }

    // ───────── 질문 생성 (JSON-12) ─────────

    /**
     * 같은 분석·같은 모드로 전에 만든 질문 세트를 JSON-12 응답 모양으로. 질문 다시 생성(avoid)이면 앞 회차와 겹치지 않는 세트를 먼저 고른다.
     */
    @Transactional(readOnly = true)
    public Optional<String> storedQuestions(Long userId, Long snapshotId, InterviewSessionMode mode, List<String> avoid) {
        if (!isSimulation(userId)) return Optional.empty();
        Map<Long, List<InterviewQuestion>> sets = new LinkedHashMap<>();
        interviewQuestionRepository.findStoredQuestions(userId, snapshotId, mode)
                .forEach(q -> sets.computeIfAbsent(q.getQuestionSet().getQuestionSetId(), k -> new ArrayList<>()).add(q));
        List<List<InterviewQuestion>> candidates = sets.values().stream().filter(qs -> !qs.isEmpty()).toList();
        if (candidates.isEmpty()) return Optional.empty();
        List<InterviewQuestion> chosen = candidates.stream()
                .filter(qs -> qs.stream().noneMatch(q -> avoid.contains(q.getQuestion())))
                .findFirst().orElse(candidates.get(0));
        return Optional.of(questionsJson(chosen));
    }

    String questionsJson(List<InterviewQuestion> questions) {
        List<Map<String, Object>> values = questions.stream().map(q -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("question", q.getQuestion());
            value.put("intent", q.getIntent());
            value.put("competencyTags", Objects.requireNonNullElse(q.getCompetencyTags(), List.of()));
            value.put("relatedRequirementId", q.getRelatedRequirementId());
            return value;
        }).toList();
        return toJson(Map.of("questions", values));
    }

    // ───────── 답변 평가 (JSON-06 v2) ─────────

    /**
     * 같은 질문(본 질문이면 질문 문장, 꼬리답변이면 꼬리질문 문장)에 전에 저장한 평가를 JSON-06 v2 응답 모양으로.
     * 다회차 비교(comparison)는 검증 규칙상 있어야 할 때와 없어야 할 때가 정해져 있어, 맞는 평가를 고른다.
     */
    @Transactional(readOnly = true)
    public Optional<String> storedEvaluation(User user, InterviewMessage answer, InterviewSessionMode mode,
                                             boolean comparisonExpected) {
        if (!isSimulation(user) || answer.getParentMessage() == null) return Optional.empty();
        return answerEvaluationRepository.findStoredEvaluations(user.getUserId(), mode, answer.getMessageType(),
                        answer.getParentMessage().getMessageText(), answer.getMessageId())
                .stream()
                .filter(e -> !comparisonExpected || hasComparison(e))
                .findFirst()
                .map(e -> evaluationJson(e, followUpQuestionRepository.findFirstByEvaluation_EvaluationId(e.getEvaluationId())
                        .orElse(null), comparisonExpected));
    }

    String evaluationJson(AnswerEvaluation evaluation, FollowUpQuestion followUp, boolean withComparison) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("evaluationVersion", 2);
        value.put("mode", evaluation.getEvaluationMode().name());
        value.put("isEvasive", Boolean.TRUE.equals(evaluation.getEvasive()));
        value.put("scores", evaluation.getEvaluationDetail().entrySet().stream().map(d -> Map.of(
                "criterion", d.getKey(),
                "score", d.getValue().getScore(),
                "reason", Objects.requireNonNullElse(d.getValue().getComment(), ""))).toList());
        value.put("strengths", evaluation.getSummary());
        List<String> improvements = evaluation.getImprovementDirection();
        value.put("improvements", improvements == null || improvements.isEmpty() ? "" : improvements.get(0));
        value.put("recommendedAnswer", evaluation.getRecommendedAnswer());
        value.put("comparison", withComparison ? evaluation.getComparison() : null);
        value.put("followUp", followUp == null ? null : Map.of(
                "question", followUp.getQuestionMessage().getMessageText(),
                "reason", Objects.requireNonNullElse(followUp.getReason(), "")));
        return toJson(value);
    }

    private static boolean hasComparison(AnswerEvaluation evaluation) {
        Map<String, String> comparison = evaluation.getComparison();
        return comparison != null && comparison.get("improved") != null && comparison.get("toImprove") != null;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
