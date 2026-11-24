package com.example.jobpuzzle.analysis.service;

import com.example.jobpuzzle.ai.dto.CandidateMaterialAnalysisResult;
import com.example.jobpuzzle.ai.dto.CustomizedAnalysisGenerationResult;
import com.example.jobpuzzle.ai.dto.JobPostingAnalysisResult;
import com.example.jobpuzzle.ai.client.AiProviderCompletionMetadata;
import com.example.jobpuzzle.ai.log.*;
import com.example.jobpuzzle.ai.prompt.PromptTemplate;
import com.example.jobpuzzle.ai.prompt.PromptTemplateRenderer;
import com.example.jobpuzzle.ai.prompt.PromptTemplateRepository;
import com.example.jobpuzzle.ai.service.AiClientService;
import com.example.jobpuzzle.ai.service.GenerationClientSelection;
import com.example.jobpuzzle.ai.service.GenerationInputLimitValidator;
import com.example.jobpuzzle.ai.validation.*;
import com.example.jobpuzzle.analysis.entity.*;
import com.example.jobpuzzle.analysis.rag.dto.RetrievedEvidenceContext;
import com.example.jobpuzzle.analysis.rag.service.RetrievalContextService;
import com.example.jobpuzzle.analysis.synthesis.dto.CustomizedSynthesisEvidenceCatalog;
import com.example.jobpuzzle.analysis.synthesis.dto.CustomizedSynthesisProviderInput;
import com.example.jobpuzzle.analysis.synthesis.dto.CustomizedSynthesisV18ProviderResult;
import com.example.jobpuzzle.analysis.synthesis.service.CustomizedSynthesisEvidenceCatalogFactory;
import com.example.jobpuzzle.analysis.synthesis.service.CustomizedSynthesisProviderInputFactory;
import com.example.jobpuzzle.analysis.synthesis.service.CustomizedSynthesisV18ResultAssembler;
import com.example.jobpuzzle.analysis.synthesis.service.CustomizedSynthesisV18SchemaFactory;
import com.example.jobpuzzle.analysis.repository.*;
import com.example.jobpuzzle.global.error.CustomException;
import com.example.jobpuzzle.global.error.ErrorCode;
import com.example.jobpuzzle.guide.dto.GuideContextResultDto;
import com.example.jobpuzzle.guide.entity.GuideContextInputReferenceType;
import com.example.jobpuzzle.guide.entity.GuideContextPurpose;
import com.example.jobpuzzle.guide.entity.GuideContextResult;
import com.example.jobpuzzle.guide.repository.GuideContextChunkRepository;
import com.example.jobpuzzle.guide.repository.GuideContextResultRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

// JSON-05 실행 선점·AI 호출·검증·실패 로그 처리를 저장기와 분리해 조정한다.
@Service
public class CustomizedSynthesisStageExecutor {
    // 보정 규칙 변경 시 과거 실패 fingerprint와 분리해 수정된 validator로 한 번은 다시 실행할 수 있게 한다.
    private static final String VALIDATION_POLICY_VERSION = "repair-v5";
    private static final Set<String> SUPPORTED_CONTRACT_VERSIONS = Set.of("v1.8", "v1.9", "v1.10");
    private final AnalysisInputSnapshotRepository snapshotRepository;
    private final JobPostingAnalysisRepository jobPostingRepository;
    private final CandidateMaterialAnalysisRepository candidateRepository;
    private final GuideContextResultRepository guideContextRepository;
    private final GuideContextChunkRepository guideContextChunkRepository;
    private final ReadinessResultRepository readinessRepository;
    private final MatchAnalysisResultRepository matchRepository;
    private final ActionPlanRepository actionPlanRepository;
    private final com.example.jobpuzzle.interview.repository.QuestionSetRepository questionSetRepository;
    private final com.example.jobpuzzle.interview.repository.InterviewQuestionRepository questionRepository;
    private final AiCallLogRepository aiCallLogRepository;
    private final PromptTemplateRepository promptTemplateRepository;
    private final AiClientService aiClientService;
    private final GenerationInputLimitValidator inputLimitValidator;
    private final PromptTemplateRenderer promptTemplateRenderer;
    private final AiResponseProcessor aiResponseProcessor;
    private final CustomizedAnalysisResponseValidator responseValidator;
    private final CustomizedAnalysisInputMapper inputMapper;
    private final CustomizedSynthesisResultWriter resultWriter;
    private final RetrievalContextService retrievalContextService;
    private final ObjectMapper objectMapper;
    private final Json05ExecutionDiagnostic diagnostic;
    private final CustomizedSynthesisEvidenceCatalogFactory evidenceCatalogFactory;
    private final CustomizedSynthesisProviderInputFactory providerInputFactory;
    private final CustomizedSynthesisV18SchemaFactory v18SchemaFactory;
    private final CustomizedSynthesisV18ResultAssembler v18ResultAssembler;
    private final TransactionTemplate transactionTemplate;
    private final long runningTimeoutSeconds;

    public CustomizedSynthesisStageExecutor(AnalysisInputSnapshotRepository snapshotRepository, JobPostingAnalysisRepository jobPostingRepository,
                                            CandidateMaterialAnalysisRepository candidateRepository, GuideContextResultRepository guideContextRepository,
                                            GuideContextChunkRepository guideContextChunkRepository, ReadinessResultRepository readinessRepository,
                                            MatchAnalysisResultRepository matchRepository, ActionPlanRepository actionPlanRepository,
                                            com.example.jobpuzzle.interview.repository.QuestionSetRepository questionSetRepository,
                                            com.example.jobpuzzle.interview.repository.InterviewQuestionRepository questionRepository,
                                            AiCallLogRepository aiCallLogRepository, PromptTemplateRepository promptTemplateRepository,
                                            AiClientService aiClientService, PromptTemplateRenderer promptTemplateRenderer,
                                            AiResponseProcessor aiResponseProcessor, CustomizedAnalysisResponseValidator responseValidator,
                                            CustomizedAnalysisInputMapper inputMapper, CustomizedSynthesisResultWriter resultWriter, ObjectMapper objectMapper,
                                            RetrievalContextService retrievalContextService, GenerationInputLimitValidator inputLimitValidator,
                                            Json05ExecutionDiagnostic diagnostic,
                                            CustomizedSynthesisEvidenceCatalogFactory evidenceCatalogFactory,
                                            CustomizedSynthesisProviderInputFactory providerInputFactory,
                                            CustomizedSynthesisV18SchemaFactory v18SchemaFactory,
                                            CustomizedSynthesisV18ResultAssembler v18ResultAssembler,
                                            @Value("${app.ai.running-timeout-seconds}") long runningTimeoutSeconds,
                                            PlatformTransactionManager transactionManager) {
        this.snapshotRepository = snapshotRepository;
        this.jobPostingRepository = jobPostingRepository;
        this.candidateRepository = candidateRepository;
        this.guideContextRepository = guideContextRepository;
        this.guideContextChunkRepository = guideContextChunkRepository;
        this.readinessRepository = readinessRepository;
        this.matchRepository = matchRepository;
        this.actionPlanRepository = actionPlanRepository;
        this.questionSetRepository = questionSetRepository;
        this.questionRepository = questionRepository;
        this.aiCallLogRepository = aiCallLogRepository;
        this.promptTemplateRepository = promptTemplateRepository;
        this.aiClientService = aiClientService;
        this.inputLimitValidator = inputLimitValidator;
        this.promptTemplateRenderer = promptTemplateRenderer;
        this.aiResponseProcessor = aiResponseProcessor;
        this.responseValidator = responseValidator;
        this.inputMapper = inputMapper;
        this.resultWriter = resultWriter;
        this.retrievalContextService = retrievalContextService;
        this.objectMapper = objectMapper;
        this.diagnostic = diagnostic;
        this.evidenceCatalogFactory = evidenceCatalogFactory;
        this.providerInputFactory = providerInputFactory;
        this.v18SchemaFactory = v18SchemaFactory;
        this.v18ResultAssembler = v18ResultAssembler;
        this.runningTimeoutSeconds = runningTimeoutSeconds;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // 선점은 짧게 끝내고 Provider 호출과 검증은 DB 락 밖에서 수행한다.
    public void execute(Long snapshotId) {
        Lease lease = acquire(snapshotId);
        // 기존 완결 결과 또는 이미 선점된 실행은 현재 호출에서 중복 실행하지 않는다.
        if (lease == null) {
            return;
        }
        try {
            // v1.8 이후 계약(결정 + 필수 설명)만 지원한다. 옛 버전은 v1-final 태그에 남아 있다.
            if (!SUPPORTED_CONTRACT_VERSIONS.contains(lease.promptTemplate().getVersion())) {
                throw new CustomException(ErrorCode.AI_PROMPT_TEMPLATE_NOT_FOUND);
            }
            executeV18(lease);
        } catch (RuntimeException exception) {
            // 하위 실행 단계가 이미 실패 log와 CustomException을 확정한 경우 중복 기록하지 않는다.
            if (exception instanceof CustomException customException) throw customException;
            // provider 호출 전(입력 구성·스키마·프롬프트 렌더링)에 난 예외는 아래에서 일반 메시지로 바뀌므로, 원인을 진단 로그에 남긴다.
            diagnostic.failure("JSON05_PREPARE", exception);
            recordFailure(lease.aiCallLogId(), errorType(exception), exception);
            throw toApiException(errorType(exception), exception);
        }
    }






    private void executeV18(Lease lease) {
        CustomizedSynthesisEvidenceCatalog authority =
                evidenceCatalogFactory.create(lease.jobPosting(), lease.retrievedEvidence().evidence());
        CustomizedSynthesisProviderInput input = providerInputFactory.create(
                lease.mainCategory(), lease.subCategory(), lease.careerLevel(),
                lease.candidate(), lease.guideDto(), authority);
        String prompt = promptTemplateRenderer.renderCustomizedAnalysisV13(lease.promptTemplate(), input);
        inputLimitValidator.validateRenderedPrompt(lease.selection(), prompt);
        executeV18WithPrompt(lease, prompt, v18SchemaFactory.schema(input), input, authority);
    }

    private void executeV18WithPrompt(Lease lease, String prompt, com.fasterxml.jackson.databind.JsonNode schema,
                                      CustomizedSynthesisProviderInput input,
                                      CustomizedSynthesisEvidenceCatalog authority) {
        String rawResponse;
        AiProviderCompletionMetadata completion = null;
        String phase = "JSON05_TEXT_EXTRACTED";
        try {
            rawResponse = aiClientService.generateCustomizedAnalysisV18(lease.selection(), prompt, schema);
            diagnostic.checkpoint(phase);
            completion = aiClientService.consumeCompletionMetadata(lease.selection());
            recordMetadata(lease.aiCallLogId(), completion);
        } catch (RuntimeException exception) {
            if (exception instanceof AiProcessingException processingException) {
                recordMetadata(lease.aiCallLogId(), processingException.getCompletionMetadata());
            }
            RuntimeException boundary = exception instanceof AiProcessingException || exception instanceof CustomException
                    ? exception : new Json05ProviderBoundaryException(exception);
            AiCallLogErrorType errorType = errorType(boundary);
            diagnostic.failure(phase, exception);
            safelyRecordFailure(lease.aiCallLogId(), errorType, boundary, phase);
            throw toApiException(errorType, boundary);
        }
        try {
            CustomizedSynthesisV18ProviderResult provider = aiResponseProcessor.parseCustomizedAnalysisV18(rawResponse);
            phase = "JSON05_PARSED";
            diagnostic.checkpoint(phase);
            CustomizedAnalysisGenerationResult result =
                    v18ResultAssembler.assemble(provider, input, authority, lease.guideDto().getMatchType());
            phase = "JSON05_PROVIDER_DTO_MAPPED";
            diagnostic.checkpoint(phase);
            phase = "JSON05_SOURCE_REFS_RESTORED";
            diagnostic.checkpoint(phase);
            responseValidator.validate(new CustomizedAnalysisValidationContext(
                    lease.jobPosting(), lease.candidate(), lease.guideDto(), lease.retrievedEvidence().evidence()), result);
            phase = "JSON05_VALIDATED";
            diagnostic.checkpoint(phase);
            recordMetadata(lease.aiCallLogId(), completion == null ? null
                    : completion.withPostProcessing(true, true, true, false, AiFailureKind.NONE));
            persistResult(lease, result);
            diagnostic.checkpoint("JSON05_WRITTEN");
            diagnostic.checkpoint("JSON05_QUESTION_SET_CREATED");
        } catch (RuntimeException exception) {
            if (exception instanceof CustomException customException) {
                diagnostic.failure(phase, exception);
                throw customException;
            }
            RuntimeException postProcessing = exception instanceof AiProcessingException
                    ? exception : new Json05PostProcessingException(exception);
            AiCallLogErrorType errorType = postProcessingErrorType(phase, postProcessing);
            diagnostic.failure(phase, exception);
            safelyRecordPostProcessingFailure(
                    lease.aiCallLogId(), completion, errorType, postProcessing, phase);
            throw toApiException(errorType, postProcessing);
        }
    }





    private AiProviderCompletionMetadata failureMetadata(AiProviderCompletionMetadata completion, RuntimeException exception) {
        if (completion == null) return null;
        AiCallLogErrorType type = errorType(exception);
        AiFailureKind kind = switch (type) {
            case OUTPUT_LIMIT_EXCEEDED -> AiFailureKind.OUTPUT_LIMIT_EXCEEDED;
            case SOURCE_REFERENCE_INVALID -> AiFailureKind.SOURCE_REFERENCE_INVALID;
            case RESPONSE_PARSE_FAILED -> AiFailureKind.DTO_PARSE_FAILED;
            case RESPONSE_VALIDATION_FAILED -> AiFailureKind.NONE;
            default -> AiFailureKind.PROVIDER_COMPLETION_FAILED;
        };
        return completion.withPostProcessing(null,
                type == AiCallLogErrorType.RESPONSE_PARSE_FAILED ? Boolean.FALSE : type == AiCallLogErrorType.RESPONSE_VALIDATION_FAILED ? Boolean.TRUE : null,
                type == AiCallLogErrorType.SOURCE_REFERENCE_INVALID ? Boolean.FALSE : null, Boolean.FALSE, kind);
    }

    // 검증 완료 결과의 DB 저장 실패는 Provider 오류와 구분한다.
    private void persistResult(Lease lease, CustomizedAnalysisGenerationResult result) {
        try {
            resultWriter.write(lease.snapshotId(), lease.aiCallLogId(), lease.guideContext(), result);
        } catch (RuntimeException exception) {
            recordFailure(lease.aiCallLogId(), AiCallLogErrorType.RESULT_PERSIST_FAILED, exception);
            throw toApiException(AiCallLogErrorType.RESULT_PERSIST_FAILED, exception);
        }
    }

    // snapshot 잠금 안에서 기존 결과 무결성·RUNNING·stale을 판정하고 새 로그를 선점한다.
    private Lease acquire(Long snapshotId) {
        return transactionTemplate.execute(status -> {
            AnalysisInputSnapshot snapshot = lockedSnapshot(snapshotId);
            if (hasAnyJson05Result(snapshotId)) {
                if (isValidCompletedResult(snapshotId)) return null;
                throw integrityConflict();
            }
            rejectSucceededLogWithoutResult(snapshotId);
            if (hasActiveExecution(snapshotId)) return null;
            if (snapshot.getAnalysisCase().getStatus() != AnalysisCaseStatus.ANALYZING)
                throw new CustomException(ErrorCode.ANALYSIS_CASE_NOT_READY);
            JobPostingAnalysis jobPosting = jobPostingRepository.findBySnapshot_SnapshotId(snapshotId).orElseThrow(() -> new IllegalStateException("JSON-01 result is missing"));
            CandidateMaterialAnalysis candidate = candidateRepository.findBySnapshot_SnapshotId(snapshotId).orElseThrow(() -> new IllegalStateException("JSON-02 result is missing"));
            requireSucceeded(jobPosting.getAiCallLog(), "JSON-01");
            requireSucceeded(candidate.getAiCallLog(), "JSON-02");
            GuideContextResult guide = guideContextRepository.findByPurposeAndInputReferenceTypeAndInputReferenceId(
                            GuideContextPurpose.CUSTOMIZED_SYNTHESIS, GuideContextInputReferenceType.ANALYSIS_SNAPSHOT, String.valueOf(snapshotId))
                    .orElseThrow(() -> new IllegalStateException("JSON-04 result is missing"));
            PromptTemplate template = promptTemplateRepository.findFirstByTargetJsonAndIsActiveTrueOrderByPromptTemplateIdDesc("JSON-05")
                    .orElseThrow(() -> new CustomException(ErrorCode.AI_PROMPT_TEMPLATE_NOT_FOUND));
            JobPostingAnalysisResult jobDto = inputMapper.jobPosting(jobPosting);
            CandidateMaterialAnalysisResult candidateDto = inputMapper.candidate(candidate);
            GuideContextResultDto guideDto = GuideContextResultDto.from(guide, guideContextChunkRepository.findByGuideContextResult_GuideContextResultIdOrderByDisplayOrderAsc(guide.getGuideContextResultId()));
            // 검색 실행과 JSON-05 실행을 분리해 재시도 시 저장된 동일 근거를 재사용한다.
            RetrievedEvidenceContext retrievedEvidence = retrievalContextService
                    .getCandidateEvidenceContext(snapshot.getUser().getUserId(), snapshotId);
            GenerationClientSelection selection = aiClientService.resolve(AiExecutionStage.CUSTOMIZED_SYNTHESIS);
            AiProvider provider = selection.provider();
            String model = selection.model();
            String fingerprint = fingerprint(provider, model, template, guide, jobDto, candidateDto, guideDto, retrievedEvidence.fingerprintMaterial());
            AiCallLog latest = aiCallLogRepository.findFirstByExecutionStageAndInputReferenceTypeAndInputReferenceIdAndInputFingerprintOrderByAiCallLogIdDesc(
                    AiExecutionStage.CUSTOMIZED_SYNTHESIS, AiInputReferenceType.ANALYSIS_SNAPSHOT, String.valueOf(snapshotId), fingerprint).orElse(null);
            if (latest != null && stale(latest))
                latest.fail(AiCallLogErrorType.STALE_RUNNING, "AI execution timed out");
            else if (latest != null && (latest.getStatus() == AiCallLogStatus.RUNNING || latest.getStatus() == AiCallLogStatus.PENDING || latest.getStatus() == AiCallLogStatus.SUCCEEDED))
                return null;
            else if (latest != null && latest.getStatus() == AiCallLogStatus.FAILED && retryBlocked(latest))
                throw new CustomException(ErrorCode.AI_RESPONSE_INVALID);
            AiCallLog log = AiCallLog.pending(provider, model, AiExecutionStage.CUSTOMIZED_SYNTHESIS, AiInputReferenceType.ANALYSIS_SNAPSHOT,
                    String.valueOf(snapshotId), fingerprint, template, guide.getGuide(), latest != null && latest.getStatus() == AiCallLogStatus.FAILED ? latest : null);
            log.start();
            aiCallLogRepository.saveAndFlush(log);
            return new Lease(snapshotId, log.getAiCallLogId(), template, jobDto, candidateDto, guide, guideDto, retrievedEvidence,
                    snapshot.getJobCategory().getMainCategory(), snapshot.getJobCategory().getSubCategory(), snapshot.getJobCategory().getCareerLevel().name(), selection);
        });
    }

    private void recordFailure(Long logId, AiCallLogErrorType errorType, RuntimeException exception) {
        transactionTemplate.executeWithoutResult(status -> aiCallLogRepository.findById(logId).ifPresent(log -> {
            if (log.getStatus() == AiCallLogStatus.RUNNING) log.fail(errorType, safeMessage(errorType, exception));
        }));
    }

    // 진단·API 오류보다 부차적인 DB 로그 기록 실패는 별도 phase로만 남기고 원래 오류를 보존한다.
    private void safelyRecordFailure(Long logId, AiCallLogErrorType errorType, RuntimeException exception, String phase) {
        try {
            recordFailure(logId, errorType, exception);
        } catch (RuntimeException recordingException) {
            diagnostic.failure(phase + "_FAILURE_RECORDING", recordingException);
        }
    }

    private void safelyRecordPostProcessingFailure(Long logId, AiProviderCompletionMetadata completion,
                                                    AiCallLogErrorType errorType, RuntimeException exception, String phase) {
        try {
            recordMetadata(logId, failureMetadata(completion, exception));
            recordFailure(logId, errorType, exception);
        } catch (RuntimeException recordingException) {
            diagnostic.failure(phase + "_FAILURE_RECORDING", recordingException);
        }
    }

    private AnalysisInputSnapshot lockedSnapshot(Long snapshotId) {
        return snapshotRepository.findWithLockBySnapshotId(snapshotId).orElseThrow(() -> new CustomException(ErrorCode.SNAPSHOT_NOT_FOUND));
    }

    private void requireSucceeded(AiCallLog log, String stage) {
        if (log == null || log.getStatus() != AiCallLogStatus.SUCCEEDED || !Boolean.TRUE.equals(log.getValid()))
            throw new IllegalStateException(stage + " is not succeeded");
    }

    private boolean hasAnyJson05Result(Long id) {
        return readinessRepository.existsBySnapshot_SnapshotId(id) || matchRepository.existsBySnapshot_SnapshotId(id) || actionPlanRepository.existsBySnapshot_SnapshotId(id);
    }

    // fingerprint와 무관하게 같은 snapshot의 활성 실행은 하나만 허용한다.
    private boolean hasActiveExecution(Long snapshotId) {
        List<AiCallLog> activeLogs = aiCallLogRepository.findByExecutionStageAndInputReferenceTypeAndInputReferenceIdAndStatusIn(
                AiExecutionStage.CUSTOMIZED_SYNTHESIS, AiInputReferenceType.ANALYSIS_SNAPSHOT, String.valueOf(snapshotId),
                List.of(AiCallLogStatus.PENDING, AiCallLogStatus.RUNNING));
        if (activeLogs.size() > 1) throw integrityConflict();
        if (activeLogs.size() == 1) {
            AiCallLog active = activeLogs.get(0);
            if (stale(active)) {
                active.fail(AiCallLogErrorType.STALE_RUNNING, "AI execution timed out");
                return false;
            }
            return true;
        }
        return false;
    }

    private void rejectSucceededLogWithoutResult(Long snapshotId) {
        List<AiCallLog> succeededLogs = aiCallLogRepository.findByExecutionStageAndInputReferenceTypeAndInputReferenceIdAndStatus(
                AiExecutionStage.CUSTOMIZED_SYNTHESIS, AiInputReferenceType.ANALYSIS_SNAPSHOT, String.valueOf(snapshotId), AiCallLogStatus.SUCCEEDED);
        if (!succeededLogs.isEmpty()) throw integrityConflict();
    }

    private boolean isValidCompletedResult(Long id) {
        ReadinessResult readiness = readinessRepository.findBySnapshot_SnapshotId(id).orElse(null);
        if (readiness == null || !isSucceededCustomizedLog(readiness.getAiCallLog())) return false;
        AiCallLog resultLog = readiness.getAiCallLog();
        List<MatchAnalysisResult> matches = matchRepository.findBySnapshot_SnapshotIdOrderByMatchIdAsc(id);
        if (!hasExpectedMatches(id, matches)) return false;
        if (matches.stream().anyMatch(value -> !sameLog(resultLog, value.getAiCallLog()))) return false;
        List<ActionPlan> actions = actionPlanRepository.findBySnapshot_SnapshotIdOrderByActionPlanIdAsc(id);
        return actions.stream().allMatch(value -> sameLog(resultLog, value.getAiCallLog()))
                && matches.stream().filter(value -> value.getMatchLevel() != MatchAnalysisResultMatchLevel.HIGH)
                .allMatch(match -> actions.stream().anyMatch(action -> action.getMatchAnalysisResult() != null
                        && match.getMatchId() != null && match.getMatchId().equals(action.getMatchAnalysisResult().getMatchId())));
    }

    private boolean hasExpectedMatches(Long snapshotId, List<MatchAnalysisResult> matches) {
        JobPostingAnalysis posting = jobPostingRepository.findBySnapshot_SnapshotId(snapshotId).orElse(null);
        if (posting == null) return false;
        JobPostingAnalysisResult result = inputMapper.jobPosting(posting);
        java.util.Set<String> expected = new java.util.HashSet<>();
        if (result.getRequirements() != null)
            result.getRequirements().forEach(value -> expected.add(value.getRequirementId()));
        if (result.getPreferred() != null)
            result.getPreferred().forEach(value -> expected.add(value.getRequirementId()));
        java.util.Set<String> actual = matches.stream().map(MatchAnalysisResult::getRequirementId).collect(java.util.stream.Collectors.toSet());
        return expected.equals(actual) && expected.size() == matches.size();
    }

    private boolean isSucceededCustomizedLog(AiCallLog log) {
        return log != null && log.getStatus() == AiCallLogStatus.SUCCEEDED && log.getExecutionStage() == AiExecutionStage.CUSTOMIZED_SYNTHESIS;
    }

    private boolean sameLog(AiCallLog expected, AiCallLog actual) {
        return isSucceededCustomizedLog(actual) && expected.getAiCallLogId() != null && expected.getAiCallLogId().equals(actual.getAiCallLogId());
    }

    private boolean stale(AiCallLog log) {
        return (log.getStatus() == AiCallLogStatus.RUNNING || log.getStatus() == AiCallLogStatus.PENDING)
                && log.getStartedAt() != null && log.getStartedAt().plusSeconds(runningTimeoutSeconds).isBefore(LocalDateTime.now());
    }

    // retrieval 근거가 바뀌면 동일 JSON-05 결과를 재사용하지 않도록 fingerprint에 반영한다.
    private String fingerprint(AiProvider provider, String model, PromptTemplate template, GuideContextResult guide, Object job,
                               Object candidate, Object guideDto, String retrievalFingerprintMaterial) {
        try {
            String value = AiExecutionStage.CUSTOMIZED_SYNTHESIS + "|" + VALIDATION_POLICY_VERSION + "|" + provider + "|" + model + "|" + template.getPromptTemplateId() + "|" + template.getVersion() + "|" + (guide.getGuide() == null ? "null" : guide.getGuide().getGuideId()) + "|" + guide.getGuideVersion() + "|" + objectMapper.writeValueAsString(job) + "|" + objectMapper.writeValueAsString(candidate) + "|" + objectMapper.writeValueAsString(guideDto) + "|" + retrievalFingerprintMaterial;
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8)));
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("cannot create JSON-05 fingerprint", exception);
        }
    }

    private AiCallLogErrorType errorType(RuntimeException exception) {
        return exception instanceof AiProcessingException value ? value.getErrorType() : AiCallLogErrorType.PROVIDER_ERROR;
    }

    private boolean retryBlocked(AiCallLog latest) {
        AiCallLogErrorType type = latest.getErrorType();
        if (type == AiCallLogErrorType.SOURCE_REFERENCE_INVALID
                || type == AiCallLogErrorType.RESPONSE_VALIDATION_FAILED
                || type == AiCallLogErrorType.RESPONSE_PARSE_FAILED
                || type == AiCallLogErrorType.INPUT_LIMIT_EXCEEDED
                || type == AiCallLogErrorType.PROMPT_RENDER_FAILED) {
            return true;
        }
        // 최초 호출 + 재시도 2회까지만 허용한다.
        return latest.getRetryCount() >= 2;
    }

    // Provider 응답을 받은 뒤의 예외는 transport 오류가 아니다. 단계에 맞춰 parse/validation으로 분류해 원인을 가리지 않는다.
    private AiCallLogErrorType postProcessingErrorType(String phase, RuntimeException exception) {
        if (exception instanceof AiProcessingException value) return value.getErrorType();
        return "JSON05_TEXT_EXTRACTED".equals(phase)
                ? AiCallLogErrorType.RESPONSE_PARSE_FAILED : AiCallLogErrorType.RESPONSE_VALIDATION_FAILED;
    }

    // 이미 계약된 CustomException은 보존하고 AI 실행 오류만 안전한 AI_001 응답으로 변환한다.
    private CustomException toApiException(AiCallLogErrorType errorType, RuntimeException exception) {
        if (exception instanceof CustomException customException) return customException;
        return new CustomException(ErrorCode.AI_RESPONSE_INVALID, safeMessage(errorType, exception));
    }

    private String safeMessage(AiCallLogErrorType errorType, RuntimeException exception) {
        return switch (errorType) {
            case RATE_LIMIT -> "AI provider rate limit exceeded";
            case TIMEOUT -> "AI provider request timed out";
            case COST_LIMIT -> "AI provider cost limit exceeded";
            case PROVIDER_ERROR -> exception instanceof Json05PostProcessingException diagnostic
                    ? "JSON-05 post-processing failed; exceptionType=" + safeExceptionType(diagnostic.getCause())
                    : exception instanceof Json05ProviderBoundaryException diagnostic
                    ? "JSON-05 provider boundary failed; exceptionType=" + safeExceptionType(diagnostic.getCause())
                    : safeProviderProcessingMessage(exception);
            case RESULT_PERSIST_FAILED -> "Customized analysis result persistence failed";
            case PROMPT_RENDER_FAILED -> "Prompt rendering failed";
            case RESPONSE_PARSE_FAILED -> "AI response parsing failed";
            case RESPONSE_VALIDATION_FAILED, SOURCE_REFERENCE_INVALID -> validatorMessage(exception, errorType);
            // validator가 만든 stage·문자 수·상한은 원문이나 비밀값을 포함하지 않아, E2E 한계 조정의 근거로 보존한다.
            case INPUT_LIMIT_EXCEEDED -> inputLimitMessage(exception);
            case STALE_RUNNING -> "AI execution timed out";
            default -> "Customized analysis execution failed";
        };
    }

    private CustomException integrityConflict() {
        return new CustomException(ErrorCode.JSON05_RESULT_INTEGRITY_CONFLICT);
    }

    private void recordMetadata(Long logId, AiProviderCompletionMetadata metadata) {
        if (metadata == null) return;
        transactionTemplate.executeWithoutResult(status -> aiCallLogRepository.findById(logId).ifPresent(log -> {
            try {
                log.recordProviderCompletionMetadata(objectMapper.writeValueAsString(metadata));
                aiCallLogRepository.save(log);
            } catch (JsonProcessingException ignored) {
                // 안전 metadata 직렬화 실패가 JSON-05 실행 결과를 가리면 안 된다.
            }
        }));
    }

    private String inputLimitMessage(RuntimeException exception) {
        String detail = exception.getMessage();
        if (detail == null || detail.isBlank()) return "AI input exceeds the configured limit";
        return detail.length() <= 500 ? detail : detail.substring(0, 500);
    }

    // validator는 원문을 포함하지 않는 고정 계약 메시지만 생성한다. JSON parse/provider 원문은 계속 일반화한다.
    private String validatorMessage(RuntimeException exception, AiCallLogErrorType errorType) {
        if (exception instanceof AiProcessingException processingException) {
            String detail = processingException.getMessage();
            if (detail != null && !detail.isBlank()) {
                return detail.substring(0, Math.min(detail.length(), 500));
            }
        }
        return errorType == AiCallLogErrorType.SOURCE_REFERENCE_INVALID
                ? "AI source reference validation failed" : "AI response validation failed";
    }

    // 원문·입력값·Provider 상세 메시지는 기록하지 않고, 서버 내부 분기 식별에 필요한 예외 클래스명만 남긴다.
    private String safeExceptionType(Throwable exception) {
        String type = exception == null ? null : exception.getClass().getSimpleName();
        return type != null && type.matches("[A-Za-z0-9_$]{1,100}") ? type : "UnknownRuntimeException";
    }

    // Anthropic adapter가 코드로 조합한 transport 예외 형태만 허용한다. HTTP/provider 원문은 계속 일반화한다.
    private String safeProviderProcessingMessage(RuntimeException exception) {
        if (exception instanceof CustomException customException) {
            return "JSON-05 provider boundary failed; errorCode=" + customException.getErrorCode().name();
        }
        if (exception instanceof AiProcessingException processingException) {
            String detail = processingException.getMessage();
            if (detail != null && detail.matches("Anthropic (request failed|transport request failed); exception=[A-Za-z0-9_$]{1,100}(; cause=[A-Za-z0-9_$]{1,100})?")) {
                return detail;
            }
            if (detail != null && detail.startsWith("Anthropic request failed; httpStatus=")) {
                // AnthropicClient가 API key 제거·개행 정규화·길이 제한을 끝낸 안전 진단만 보존한다.
                // schema 400을 일반 PROVIDER_ERROR로 뭉개면 유료 재시도 전에 원인을 구분할 수 없다.
                return detail.substring(0, Math.min(detail.length(), 500));
            }
            if (detail != null && detail.startsWith("Anthropic response")) {
                return "Anthropic response handling failed";
            }
            if (detail != null && detail.startsWith("Anthropic ")) {
                return "Anthropic adapter processing failed";
            }
            return "AI processing failed before JSON-05 response handling";
        }
        return "AI provider request failed";
    }

    private static final class Json05PostProcessingException extends RuntimeException {
        private Json05PostProcessingException(RuntimeException cause) {
            super(null, cause, false, false);
        }
    }

    private static final class Json05ProviderBoundaryException extends RuntimeException {
        private Json05ProviderBoundaryException(RuntimeException cause) {
            super(null, cause, false, false);
        }
    }

    private record Lease(Long snapshotId, Long aiCallLogId, PromptTemplate promptTemplate,
                         JobPostingAnalysisResult jobPosting, CandidateMaterialAnalysisResult candidate,
                         GuideContextResult guideContext, GuideContextResultDto guideDto, RetrievedEvidenceContext retrievedEvidence, String mainCategory,
                         String subCategory, String careerLevel, GenerationClientSelection selection) {
    }
}
