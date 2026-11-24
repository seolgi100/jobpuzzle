package com.example.jobpuzzle.interview.service;

import com.example.jobpuzzle.ai.config.AiGenerationProperties;
import com.example.jobpuzzle.ai.dto.QuestionGenerationV2Result;
import com.example.jobpuzzle.ai.log.AiCallLog;
import com.example.jobpuzzle.ai.log.AiCallLogErrorType;
import com.example.jobpuzzle.ai.log.AiCallLogRepository;
import com.example.jobpuzzle.ai.log.AiExecutionStage;
import com.example.jobpuzzle.ai.log.AiInputReferenceType;
import com.example.jobpuzzle.ai.prompt.PromptTemplate;
import com.example.jobpuzzle.ai.prompt.PromptTemplateRepository;
import com.example.jobpuzzle.ai.service.AiClientService;
import com.example.jobpuzzle.simulation.SimulationService;
import com.example.jobpuzzle.ai.service.GenerationClientSelection;
import com.example.jobpuzzle.ai.validation.AiProcessingException;
import com.example.jobpuzzle.ai.validation.AiResponseProcessor;
import com.example.jobpuzzle.analysis.dto.QuestionSetResponse;
import com.example.jobpuzzle.analysis.entity.AnalysisCase;
import com.example.jobpuzzle.analysis.entity.AnalysisCaseStatus;
import com.example.jobpuzzle.analysis.entity.AnalysisInputSnapshot;
import com.example.jobpuzzle.analysis.entity.CandidateMaterialAnalysis;
import com.example.jobpuzzle.analysis.entity.JobPostingAnalysis;
import com.example.jobpuzzle.analysis.entity.MatchAnalysisResult;
import com.example.jobpuzzle.analysis.entity.ReadinessResult;
import com.example.jobpuzzle.analysis.repository.AnalysisCaseRepository;
import com.example.jobpuzzle.analysis.repository.AnalysisInputSnapshotRepository;
import com.example.jobpuzzle.analysis.repository.CandidateMaterialAnalysisRepository;
import com.example.jobpuzzle.analysis.repository.JobPostingAnalysisRepository;
import com.example.jobpuzzle.analysis.repository.MatchAnalysisResultRepository;
import com.example.jobpuzzle.analysis.repository.ReadinessResultRepository;
import com.example.jobpuzzle.analysis.service.AnalysisSummaries;
import com.example.jobpuzzle.competency.entity.PersonalityQuestion;
import com.example.jobpuzzle.competency.repository.PersonalityQuestionRepository;
import com.example.jobpuzzle.competency.service.CompetencyService;
import com.example.jobpuzzle.evaluation.service.EvaluationFailureDiagnostics;
import com.example.jobpuzzle.global.error.CustomException;
import com.example.jobpuzzle.global.error.ErrorCode;
import com.example.jobpuzzle.interview.dto.InterviewModeAvailabilityResponse;
import com.example.jobpuzzle.interview.entity.InterviewQuestion;
import com.example.jobpuzzle.interview.entity.InterviewQuestionType;
import com.example.jobpuzzle.interview.entity.InterviewSessionMode;
import com.example.jobpuzzle.interview.entity.QuestionSet;
import com.example.jobpuzzle.interview.repository.InterviewQuestionRepository;
import com.example.jobpuzzle.interview.repository.QuestionSetRepository;
import com.example.jobpuzzle.jobcategory.entity.JobCategory;
import com.example.jobpuzzle.jobcategory.entity.JobCategoryCareerLevel;
import com.example.jobpuzzle.user.entity.User;
import com.example.jobpuzzle.user.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 평가 명세 v2 면접 질문 생성.
 * 기초 지식·자료 기반은 사용자가 고른 분석 결과로 AI(JSON-12)가 만들고, 인성은 관리자가 등록한 질문 중에서 고른다.
 * 티켓 차감·반환은 컨트롤러의 TicketService.spend가 맡고, 여기서는 최종 실패 시 예외만 던진다.
 * 실패해도 AI 호출 기록(FAILED)은 남아야 해서 CustomException으로는 롤백하지 않는다 (실패 전에는 질문을 저장하지 않음).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(noRollbackFor = CustomException.class)
public class QuestionGenerationV2Service {

    static final String TARGET_JSON = "JSON-12";
    private static final int MAX_QUESTIONS = 10;

    private final UserRepository userRepository;
    private final AnalysisCaseRepository analysisCaseRepository;
    private final AnalysisInputSnapshotRepository snapshotRepository;
    private final ReadinessResultRepository readinessResultRepository;
    private final JobPostingAnalysisRepository jobPostingAnalysisRepository;
    private final CandidateMaterialAnalysisRepository candidateAnalysisRepository;
    private final MatchAnalysisResultRepository matchRepository;
    private final PersonalityQuestionRepository personalityQuestionRepository;
    private final QuestionSetRepository questionSetRepository;
    private final InterviewQuestionRepository interviewQuestionRepository;
    private final PromptTemplateRepository promptTemplateRepository;
    private final AiCallLogRepository aiCallLogRepository;
    private final AiClientService aiClientService;
    private final SimulationService simulationService;
    private final AiResponseProcessor aiResponseProcessor;
    private final AiGenerationProperties aiProperties;
    private final InterviewQuestionProperties properties;
    private final CompetencyService competencyService;
    private final ObjectMapper objectMapper;

    /** 기초 지식(KNOWLEDGE)·자료 기반(MATERIAL). 분석이 끝난 분석 작업만 쓸 수 있다. */
    public QuestionSetResponse generate(Long userId, InterviewSessionMode mode, Long analysisCaseId) {
        return generate(userId, mode, analysisCaseId, List.of());
    }

    /** avoidQuestions: 질문 다시 생성 때 앞 회차 질문. 같은 내용을 다시 묻지 않게 AI에 넘긴다. */
    public QuestionSetResponse generate(Long userId, InterviewSessionMode mode, Long analysisCaseId,
                                        List<String> avoidQuestions) {
        if (mode != InterviewSessionMode.KNOWLEDGE && mode != InterviewSessionMode.MATERIAL) {
            throw new IllegalArgumentException("AI 질문 생성 대상 모드가 아님: " + mode);
        }
        AnalysisCase analysisCase = analysisCaseRepository.findByAnalysisCaseIdAndUser_UserId(analysisCaseId, userId)
                .orElseThrow(() -> new CustomException(ErrorCode.ANALYSIS_CASE_NOT_FOUND));
        if (analysisCase.getStatus() != AnalysisCaseStatus.COMPLETED) {
            throw new CustomException(ErrorCode.ANALYSIS_CASE_NOT_READY);
        }
        AnalysisInputSnapshot snapshot = snapshotRepository.findByAnalysisCase_AnalysisCaseIdAndUser_UserId(analysisCaseId, userId)
                .orElseThrow(() -> new CustomException(ErrorCode.SNAPSHOT_NOT_FOUND));
        Long snapshotId = snapshot.getSnapshotId();
        JobPostingAnalysis posting = jobPostingAnalysisRepository.findBySnapshot_SnapshotId(snapshotId)
                .orElseThrow(() -> new CustomException(ErrorCode.ANALYSIS_CASE_NOT_READY));
        JobCategory job = snapshot.getJobCategory();
        Map<String, String> tags = competencyService.availableTags(job.getMainCategory(), job.getSubCategory());

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("mode", mode.name());
        input.put("questionCount", properties.getGeneratedQuestionCount());
        input.put("job", Map.of("mainCategory", job.getMainCategory(), "subCategory", job.getSubCategory(),
                "careerLevel", job.getCareerLevel().name()));
        input.put("competencyTags", tags.entrySet().stream()
                .map(tag -> Map.of("code", tag.getKey(), "name", tag.getValue())).toList());
        input.put("jobRequirements", AnalysisSummaries.requirements(posting));
        if (!avoidQuestions.isEmpty()) input.put("avoidQuestions", avoidQuestions);
        Set<String> requirementIds = Set.of();
        if (mode == InterviewSessionMode.MATERIAL) {
            // 분석이 "자료 기반 면접을 만들 수 없음"으로 판단했으면 AI를 부르지 않는다 (수정사항 19번)
            boolean ready = readinessResultRepository.findBySnapshot_SnapshotId(snapshotId)
                    .map(ReadinessResult::isCanGenerateQuestions).orElse(false);
            CandidateMaterialAnalysis candidate = candidateAnalysisRepository.findBySnapshot_SnapshotId(snapshotId).orElse(null);
            if (!ready || candidate == null) throw new CustomException(ErrorCode.MATERIAL_INTERVIEW_UNAVAILABLE);
            List<MatchAnalysisResult> matches = matchRepository.findBySnapshot_SnapshotIdOrderByMatchIdAsc(snapshotId);
            input.put("matches", matches.stream().map(this::match).toList());
            input.put("candidate", AnalysisSummaries.candidate(candidate));
            requirementIds = matches.stream().map(MatchAnalysisResult::getRequirementId).collect(Collectors.toSet());
        }

        PromptTemplate template = promptTemplateRepository
                .findFirstByTargetJsonAndIsActiveTrueOrderByPromptTemplateIdDesc(TARGET_JSON)
                .orElseThrow(() -> new CustomException(ErrorCode.AI_PROMPT_TEMPLATE_NOT_FOUND));
        String prompt = template.getTemplateText() + "\n\n[INPUT]\n" + toJson(input);
        GenerationClientSelection selection = aiClientService.resolve(AiExecutionStage.QUESTION_GENERATION);
        AiCallLog callLog = AiCallLog.pending(selection.provider(), selection.model(), AiExecutionStage.QUESTION_GENERATION,
                AiInputReferenceType.ANALYSIS_SNAPSHOT, String.valueOf(snapshotId),
                Integer.toHexString(prompt.hashCode()), template, null, null);
        callLog.start();
        aiCallLogRepository.save(callLog);

        // 시뮬레이션 계정: 같은 분석·모드로 전에 만든 질문이 있으면 AI 대신 그 질문을 쓴다 (로딩만 흉내)
        String simulated = simulationService.storedQuestions(userId, snapshotId, mode, avoidQuestions).orElse(null);
        if (simulated != null) simulationService.pause();

        List<QuestionGenerationV2Result.Question> generated = null;
        RuntimeException last = null;
        int attempts = 1 + Math.max(0, aiProperties.getMaxRetries());
        for (int attempt = 1; attempt <= attempts && generated == null; attempt++) {
            try {
                generated = validated(aiResponseProcessor.parseQuestionGenerationV2(simulated != null ? simulated
                        : aiClientService.generateQuestionsV2(selection, prompt)), tags.keySet());
            } catch (RuntimeException exception) {
                last = exception;
                log.warn("ai_question_generation_v2_retry attempt={}/{} snapshotId={} mode={} cause={}", attempt, attempts,
                        snapshotId, mode, EvaluationFailureDiagnostics.rootCauseSummary(exception));
            }
        }
        if (generated == null) {
            AiCallLogErrorType errorType = EvaluationFailureDiagnostics.errorType(last);
            callLog.fail(errorType, errorType + " | " + EvaluationFailureDiagnostics.failureDetail(last));
            log.error("ai_question_generation_v2_failed snapshotId={} mode={}", snapshotId, mode, last);
            throw new CustomException(ErrorCode.AI_RESPONSE_INVALID);
        }
        callLog.succeed();

        QuestionSet set = questionSetRepository.save(QuestionSet.v2(snapshot.getUser(), mode, job, snapshot, callLog));
        InterviewQuestionType type = mode == InterviewSessionMode.KNOWLEDGE
                ? InterviewQuestionType.SKILL : InterviewQuestionType.EXPERIENCE;
        List<InterviewQuestion> questions = new ArrayList<>();
        for (QuestionGenerationV2Result.Question value : generated) {
            int order = questions.size() + 1;
            // 공고에 없는 요구사항 ID는 재호출할 만큼의 문제가 아니라 버린다.
            String requirementId = value.relatedRequirementId() != null && requirementIds.contains(value.relatedRequirementId())
                    ? value.relatedRequirementId() : null;
            questions.add(InterviewQuestion.v2(set, "q" + order, type, value.question().strip(), value.intent().strip(),
                    value.competencyTags(), requirementId, order));
        }
        return QuestionSetResponse.from(set, interviewQuestionRepository.saveAll(questions));
    }

    /** 인성(PERSONALITY). AI 없이 회원 직무의 인성 질문 중 본인 경력 수준(+공통) 질문을 무작위로 고른다. */
    public QuestionSetResponse generatePersonality(Long userId) {
        return generatePersonality(userId, Set.of());
    }

    /** avoidQuestionKeys: 질문 다시 생성 때 앞 회차 질문. 남은 질문이 모자랄 때만 다시 쓴다. */
    public QuestionSetResponse generatePersonality(Long userId, Set<String> avoidQuestionKeys) {
        User user = userRepository.findById(userId).orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        JobCategory job = user.getDefaultJobCategory();
        if (job == null) throw new CustomException(ErrorCode.PERSONALITY_JOB_REQUIRED);
        List<PersonalityQuestion> pool = new ArrayList<>(personalityPool(job));
        if (pool.isEmpty()) throw new CustomException(ErrorCode.PERSONALITY_QUESTION_EMPTY);
        Collections.shuffle(pool);
        // 섞은 뒤 앞 회차 질문을 뒤로 보낸다 (정렬은 안정적이라 나머지 순서는 무작위 그대로)
        pool.sort(java.util.Comparator.comparing(q -> avoidQuestionKeys.contains(personalityKey(q))));

        QuestionSet set = questionSetRepository.save(QuestionSet.v2(user, InterviewSessionMode.PERSONALITY, job, null, null));
        List<InterviewQuestion> questions = new ArrayList<>();
        for (PersonalityQuestion picked : pool.subList(0, Math.min(properties.getPersonalityQuestionCount(), pool.size()))) {
            int order = questions.size() + 1;
            questions.add(InterviewQuestion.v2(set, personalityKey(picked),
                    InterviewQuestionType.GENERAL, picked.getQuestion(), picked.getIntent(),
                    picked.getCompetencyTagCodes(), null, order));
        }
        return QuestionSetResponse.from(set, interviewQuestionRepository.saveAll(questions));
    }

    /** 모드별로 지금 면접을 만들 수 있는지 (평가 명세 1장 모드별 조건). 화면이 고르기 전에 막을 때 쓴다. */
    @Transactional(readOnly = true)
    public InterviewModeAvailabilityResponse availability(Long userId) {
        List<AnalysisInputSnapshot> analyzed = analysisCaseRepository
                .findByUser_UserIdAndStatusAndHiddenAtIsNullOrderByCreatedAtDesc(userId, AnalysisCaseStatus.COMPLETED).stream()
                .map(c -> snapshotRepository.findByAnalysisCase_AnalysisCaseIdAndUser_UserId(c.getAnalysisCaseId(), userId))
                .flatMap(java.util.Optional::stream)
                .toList();
        boolean material = analyzed.stream().anyMatch(snapshot -> readinessResultRepository
                .findBySnapshot_SnapshotId(snapshot.getSnapshotId())
                .map(ReadinessResult::isCanGenerateQuestions).orElse(false));
        JobCategory job = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND)).getDefaultJobCategory();
        boolean personality = job != null && !personalityPool(job).isEmpty();
        return InterviewModeAvailabilityResponse.builder().modes(List.of(
                mode(InterviewSessionMode.KNOWLEDGE, !analyzed.isEmpty(), "분석을 마친 공고가 필요합니다."),
                mode(InterviewSessionMode.MATERIAL, material, "자료 기반 면접을 만들 수 있는 분석 결과가 필요합니다."),
                mode(InterviewSessionMode.PERSONALITY, personality, (job == null
                        ? ErrorCode.PERSONALITY_JOB_REQUIRED : ErrorCode.PERSONALITY_QUESTION_EMPTY).getMessage())
        )).build();
    }

    private static InterviewModeAvailabilityResponse.ModeAvailability mode(InterviewSessionMode mode, boolean available,
                                                                           String reason) {
        return InterviewModeAvailabilityResponse.ModeAvailability.builder()
                .mode(mode).available(available).reason(available ? null : reason).build();
    }

    static String personalityKey(PersonalityQuestion question) {
        return "personality-" + question.getPersonalityQuestionId();
    }

    // 회원 직무의 인성 질문 중 본인 경력 수준과 공통(ANY) 질문
    private List<PersonalityQuestion> personalityPool(JobCategory job) {
        return personalityQuestionRepository
                .findByMainCategoryAndSubCategoryOrderByPersonalityQuestionIdAsc(job.getMainCategory(), job.getSubCategory())
                .stream()
                .filter(q -> q.getCareerLevel() == JobCategoryCareerLevel.ANY || q.getCareerLevel() == job.getCareerLevel())
                .toList();
    }

    private List<QuestionGenerationV2Result.Question> validated(QuestionGenerationV2Result result, Set<String> tags) {
        if (result.questions() == null || result.questions().isEmpty() || result.questions().size() > MAX_QUESTIONS) {
            throw invalid("질문 수가 1~" + MAX_QUESTIONS + "개가 아닙니다");
        }
        Set<String> seen = new HashSet<>();
        for (QuestionGenerationV2Result.Question question : result.questions()) {
            if (question == null || blank(question.question()) || blank(question.intent())) {
                throw invalid("질문 또는 출제 의도가 비어 있습니다");
            }
            if (!seen.add(question.question().strip())) throw invalid("같은 질문이 반복됩니다");
            String tagProblem = CompetencyService.tagProblem(question.competencyTags(), tags);
            if (tagProblem != null) throw invalid(tagProblem);
        }
        return result.questions();
    }

    private Map<String, Object> match(MatchAnalysisResult match) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("requirementId", match.getRequirementId());
        value.put("requirement", match.getRequirement());
        value.put("matchLevel", match.getMatchLevel().name());
        value.put("candidateEvidence", match.getCandidateEvidence());
        value.put("missingPoint", match.getMissingPoint());
        return value;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static AiProcessingException invalid(String message) {
        return new AiProcessingException(AiCallLogErrorType.RESPONSE_VALIDATION_FAILED, "JSON-12 " + message);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("질문 생성 입력을 JSON으로 만들 수 없습니다", exception);
        }
    }
}
