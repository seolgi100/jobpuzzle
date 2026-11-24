package com.example.jobpuzzle.analysis.service;

import com.example.jobpuzzle.ai.dto.CandidateMaterialAnalysisResult;
import com.example.jobpuzzle.ai.dto.JobPostingAnalysisResult;
import com.example.jobpuzzle.ai.log.AiCallLog;
import com.example.jobpuzzle.ai.log.AiCallLogErrorType;
import com.example.jobpuzzle.ai.log.AiCallLogRepository;
import com.example.jobpuzzle.ai.log.AiCallLogStatus;
import com.example.jobpuzzle.ai.log.AiExecutionStage;
import com.example.jobpuzzle.ai.log.AiInputReferenceType;
import com.example.jobpuzzle.ai.log.AiProvider;
import com.example.jobpuzzle.ai.prompt.PromptTemplate;
import com.example.jobpuzzle.ai.prompt.PromptTemplateRepository;
import com.example.jobpuzzle.ai.prompt.PromptTemplateRenderer;
import com.example.jobpuzzle.ai.service.AiClientService;
import com.example.jobpuzzle.ai.service.GenerationClientSelection;
import com.example.jobpuzzle.ai.service.GenerationInputLimitValidator;
import com.example.jobpuzzle.ai.validation.AiProcessingException;
import com.example.jobpuzzle.ai.validation.AiResponseProcessor;
import com.example.jobpuzzle.analysis.dto.AnalysisInputSnapshotContext;
import com.example.jobpuzzle.analysis.dto.AnalysisInputSnapshotContextSource;
import com.example.jobpuzzle.analysis.entity.AnalysisInputSnapshot;
import com.example.jobpuzzle.analysis.entity.CandidateMaterialAnalysis;
import com.example.jobpuzzle.analysis.entity.JobPostingAnalysis;
import com.example.jobpuzzle.analysis.repository.AnalysisInputSnapshotRepository;
import com.example.jobpuzzle.analysis.repository.CandidateMaterialAnalysisRepository;
import com.example.jobpuzzle.analysis.repository.JobPostingAnalysisRepository;
import com.example.jobpuzzle.global.error.CustomException;
import com.example.jobpuzzle.global.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.time.LocalDateTime;

// JSON-01·02를 짧은 DB 트랜잭션과 트랜잭션 밖 AI 호출로 분리해 실행한다.
@Service
public class InitialAnalysisStageExecutor {

    private final AnalysisInputSnapshotRepository analysisInputSnapshotRepository;
    private final JobPostingAnalysisRepository jobPostingAnalysisRepository;
    private final CandidateMaterialAnalysisRepository candidateMaterialAnalysisRepository;
    private final AiCallLogRepository aiCallLogRepository;
    private final PromptTemplateRepository promptTemplateRepository;
    private final AiClientService aiClientService;
    private final GenerationInputLimitValidator inputLimitValidator;
    private final PromptTemplateRenderer promptTemplateRenderer;
    private final AiResponseProcessor aiResponseProcessor;
    private final TransactionTemplate transactionTemplate;
    private final long runningTimeoutSeconds;

    public InitialAnalysisStageExecutor(
            AnalysisInputSnapshotRepository analysisInputSnapshotRepository,
            JobPostingAnalysisRepository jobPostingAnalysisRepository,
            CandidateMaterialAnalysisRepository candidateMaterialAnalysisRepository,
            AiCallLogRepository aiCallLogRepository,
            PromptTemplateRepository promptTemplateRepository,
            AiClientService aiClientService,
            GenerationInputLimitValidator inputLimitValidator,
            PromptTemplateRenderer promptTemplateRenderer,
            AiResponseProcessor aiResponseProcessor,
            @Value("${app.ai.running-timeout-seconds}") long runningTimeoutSeconds,
            PlatformTransactionManager transactionManager
    ) {
        this.analysisInputSnapshotRepository = analysisInputSnapshotRepository;
        this.jobPostingAnalysisRepository = jobPostingAnalysisRepository;
        this.candidateMaterialAnalysisRepository = candidateMaterialAnalysisRepository;
        this.aiCallLogRepository = aiCallLogRepository;
        this.promptTemplateRepository = promptTemplateRepository;
        this.aiClientService = aiClientService;
        this.inputLimitValidator = inputLimitValidator;
        this.promptTemplateRenderer = promptTemplateRenderer;
        this.aiResponseProcessor = aiResponseProcessor;
        this.runningTimeoutSeconds = runningTimeoutSeconds;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // 한 실행 단계를 선점·AI 호출·성공 또는 실패 기록 순서로 처리한다.
    public void execute(
            AiExecutionStage executionStage,
            AnalysisInputSnapshotContext context,
            List<AnalysisInputSnapshotContextSource> primarySources,
            List<AnalysisInputSnapshotContextSource> supplementarySources
    ) {
        StageLease lease = acquire(executionStage, context, primarySources, supplementarySources);
        if (lease == null) {
            // 기존 성공 결과 또는 이미 선점된 실행은 현재 호출에서 중복 실행하지 않는다.
            return;
        }

        try {
            // 프롬프트 렌더링은 RUNNING 로그 커밋 뒤, DB 락 밖에서 수행한다.
            String prompt = promptTemplateRenderer.render(
                    lease.executionStage(), lease.promptTemplate(), lease.context(), lease.primarySources(), lease.supplementarySources()
            );
            inputLimitValidator.validateRenderedPrompt(lease.selection(), prompt);
            // DB 락을 해제한 뒤에만 Provider 원시 JSON을 호출하고 파싱·검증한다.
            if (executionStage == AiExecutionStage.JOB_POSTING_ANALYSIS) {
                JobPostingAnalysisResult result = aiResponseProcessor.parseJobPosting(
                        aiClientService.analyzeJobPosting(lease.selection(), prompt), allSources(lease)
                );
                // 요구사항이 하나도 없으면 맞춤 분석(JSON-05)을 만들 수 없다. 자료 분석(JSON-02) 비용을 쓰기 전에 멈추고 원인을 남긴다.
                requireRequirements(result);
                saveJobPostingSuccess(lease, result);
            } else {
                CandidateMaterialAnalysisResult result = aiResponseProcessor.parseCandidateMaterial(
                        aiClientService.analyzeCandidateMaterial(lease.selection(), prompt), lease.primarySources()
                );
                saveCandidateMaterialSuccess(lease, result);
            }
        } catch (RuntimeException exception) {
            // 호출 또는 결과 저장 실패를 별도 트랜잭션에서 로그로 종결한 뒤 안전한 API 예외로 전달한다.
            AiCallLogErrorType errorType = errorType(exception);
            recordFailure(lease.aiCallLogId(), errorType, exception);
            throw toApiException(errorType, exception);
        }
    }

    // snapshot 잠금 안에서 기존 결과·로그를 확인하고 새 RUNNING 로그를 선점한다.
    private StageLease acquire(
            AiExecutionStage executionStage,
            AnalysisInputSnapshotContext context,
            List<AnalysisInputSnapshotContextSource> primarySources,
            List<AnalysisInputSnapshotContextSource> supplementarySources
    ) {
        return transactionTemplate.execute(status -> {
            AnalysisInputSnapshot snapshot = findSnapshotWithLock(context.getSnapshotId());
            PromptTemplate promptTemplate = findActivePromptTemplate(executionStage);

            if (hasResult(executionStage, snapshot.getSnapshotId())) {
                return null;
            }

            GenerationClientSelection selection = aiClientService.resolve(executionStage);
            AiProvider provider = selection.provider();
            String model = selection.model();
            String inputReferenceId = String.valueOf(snapshot.getSnapshotId());
            String fingerprint = fingerprint(executionStage, provider, model, promptTemplate,
                    promptTemplateRenderer.fingerprintMaterial(executionStage, promptTemplate, context, primarySources, supplementarySources));
            AiCallLog latest = aiCallLogRepository
                    .findFirstByExecutionStageAndInputReferenceTypeAndInputReferenceIdAndInputFingerprintOrderByAiCallLogIdDesc(
                            executionStage,
                            AiInputReferenceType.ANALYSIS_SNAPSHOT,
                            inputReferenceId,
                            fingerprint
                    )
                    .orElse(null);

            // stale RUNNING은 선점 트랜잭션 안에서 실패로 종결한 뒤 새 재시도를 허용한다.
            if (latest != null && isStaleRunning(latest)) {
                latest.fail(AiCallLogErrorType.STALE_RUNNING, "running timeout exceeded");
            } else if (latest != null && (latest.getStatus() == AiCallLogStatus.RUNNING
                    || latest.getStatus() == AiCallLogStatus.PENDING
                    || latest.getStatus() == AiCallLogStatus.SUCCEEDED)) {
                return null;
            } else if (latest != null && latest.getStatus() == AiCallLogStatus.FAILED
                    && latest.getErrorType() == AiCallLogErrorType.REQUIREMENTS_NOT_FOUND) {
                // 같은 공고로 다시 호출해도 결과가 같으므로 유료 AI를 다시 부르지 않는다.
                throw new CustomException(ErrorCode.JOB_POSTING_REQUIREMENTS_NOT_FOUND);
            }

            AiCallLog log = AiCallLog.pending(
                    provider,
                    model,
                    executionStage,
                    AiInputReferenceType.ANALYSIS_SNAPSHOT,
                    inputReferenceId,
                    fingerprint,
                    promptTemplate,
                    null,
                    latest != null && latest.getStatus() == AiCallLogStatus.FAILED ? latest : null
            );
            log.start();
            aiCallLogRepository.saveAndFlush(log);
            return new StageLease(snapshot.getSnapshotId(), log.getAiCallLogId(), executionStage, promptTemplate,
                    context, primarySources, supplementarySources, selection);
        });
    }

    // JSON-01 결과와 SUCCEEDED 로그를 같은 짧은 트랜잭션에서 확정한다.
    private void saveJobPostingSuccess(StageLease lease, JobPostingAnalysisResult result) {
        transactionTemplate.executeWithoutResult(status -> {
            AnalysisInputSnapshot snapshot = findSnapshotWithLock(lease.snapshotId());
            AiCallLog log = findLog(lease.aiCallLogId());
            JobPostingAnalysis existing = jobPostingAnalysisRepository
                    .findBySnapshot_SnapshotId(snapshot.getSnapshotId())
                    .orElse(null);
            if (existing != null) {
                completeFromExisting(log, existing.getAiCallLog());
                return;
            }

            jobPostingAnalysisRepository.saveAndFlush(JobPostingAnalysis.from(snapshot, result, log));
            log.succeed();
        });
    }

    // JSON-02 결과와 SUCCEEDED 로그를 같은 짧은 트랜잭션에서 확정한다.
    private void saveCandidateMaterialSuccess(StageLease lease, CandidateMaterialAnalysisResult result) {
        transactionTemplate.executeWithoutResult(status -> {
            AnalysisInputSnapshot snapshot = findSnapshotWithLock(lease.snapshotId());
            AiCallLog log = findLog(lease.aiCallLogId());
            CandidateMaterialAnalysis existing = candidateMaterialAnalysisRepository
                    .findBySnapshot_SnapshotId(snapshot.getSnapshotId())
                    .orElse(null);
            if (existing != null) {
                completeFromExisting(log, existing.getAiCallLog());
                return;
            }

            candidateMaterialAnalysisRepository.saveAndFlush(CandidateMaterialAnalysis.from(snapshot, result, log));
            log.succeed();
        });
    }

    // 실패 원인은 RUNNING 로그에만 기록해 이미 성공한 실행을 덮어쓰지 않는다.
    private void recordFailure(Long aiCallLogId, AiCallLogErrorType errorType, RuntimeException exception) {
        transactionTemplate.executeWithoutResult(status -> {
            AiCallLog log = findLog(aiCallLogId);
            if (log.getStatus() == AiCallLogStatus.RUNNING) {
                log.fail(errorType, summarize(exception));
            }
        });
    }

    // 이미 계약된 CustomException은 보존하고 AI 실행 오류만 안전한 AI_001 응답으로 변환한다.
    private CustomException toApiException(AiCallLogErrorType errorType, RuntimeException exception) {
        if (exception instanceof CustomException customException) return customException;
        // 입력 공고 문제는 AI 오류(AI_001)와 구분해 사용자가 고칠 수 있는 오류로 돌려준다.
        if (errorType == AiCallLogErrorType.REQUIREMENTS_NOT_FOUND) {
            return new CustomException(ErrorCode.JOB_POSTING_REQUIREMENTS_NOT_FOUND);
        }
        return new CustomException(ErrorCode.AI_RESPONSE_INVALID, clientMessage(errorType));
    }

    private static void requireRequirements(JobPostingAnalysisResult result) {
        boolean noRequirements = result.getRequirements() == null || result.getRequirements().isEmpty();
        boolean noPreferred = result.getPreferred() == null || result.getPreferred().isEmpty();
        if (noRequirements && noPreferred) {
            throw new AiProcessingException(AiCallLogErrorType.REQUIREMENTS_NOT_FOUND,
                    "job posting has no requirements or preferred qualifications");
        }
    }

    private AiCallLogErrorType errorType(RuntimeException exception) {
        return exception instanceof AiProcessingException processingException
                ? processingException.getErrorType() : AiCallLogErrorType.PROVIDER_ERROR;
    }

    private String clientMessage(AiCallLogErrorType errorType) {
        return switch (errorType) {
            case RATE_LIMIT -> "AI provider rate limit exceeded";
            case TIMEOUT -> "AI provider request timed out";
            case COST_LIMIT -> "AI provider cost limit exceeded";
            case RESPONSE_PARSE_FAILED -> "AI response parsing failed";
            case RESPONSE_VALIDATION_FAILED -> "AI response validation failed";
            case SOURCE_REFERENCE_INVALID -> "AI source reference validation failed";
            case PROMPT_RENDER_FAILED -> "AI prompt rendering failed";
            case INPUT_LIMIT_EXCEEDED -> "AI input exceeds the configured limit";
            default -> "AI provider request failed";
        };
    }

    private AnalysisInputSnapshot findSnapshotWithLock(Long snapshotId) {
        return analysisInputSnapshotRepository.findWithLockBySnapshotId(snapshotId)
                .orElseThrow(() -> new CustomException(ErrorCode.SNAPSHOT_NOT_FOUND));
    }

    private PromptTemplate findActivePromptTemplate(AiExecutionStage executionStage) {
        return promptTemplateRepository
                .findFirstByTargetJsonAndIsActiveTrueOrderByPromptTemplateIdDesc(targetJson(executionStage))
                .orElseThrow(() -> new CustomException(ErrorCode.AI_PROMPT_TEMPLATE_NOT_FOUND));
    }

    private boolean hasResult(AiExecutionStage executionStage, Long snapshotId) {
        return executionStage == AiExecutionStage.JOB_POSTING_ANALYSIS
                ? jobPostingAnalysisRepository.existsBySnapshot_SnapshotId(snapshotId)
                : candidateMaterialAnalysisRepository.existsBySnapshot_SnapshotId(snapshotId);
    }

    private String targetJson(AiExecutionStage executionStage) {
        return executionStage == AiExecutionStage.JOB_POSTING_ANALYSIS ? "JSON-01" : "JSON-02";
    }

    private String fingerprint(
            AiExecutionStage executionStage,
            AiProvider provider,
            String model,
            PromptTemplate promptTemplate,
            String prompt
    ) {
        String value = executionStage + "|" + provider + "|" + model + "|"
                + promptTemplate.getPromptTemplateId() + "|" + promptTemplate.getVersion() + "|"
                + normalize(prompt);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 algorithm is unavailable", exception);
        }
    }

    private String normalize(String value) {
        return value.replace("\r\n", "\n").trim();
    }

    private AiCallLog findLog(Long aiCallLogId) {
        return aiCallLogRepository.findById(aiCallLogId)
                .orElseThrow(() -> new IllegalStateException("AI call log not found: " + aiCallLogId));
    }

    private void completeFromExisting(AiCallLog current, AiCallLog source) {
        if (source == null) {
            current.succeed();
            return;
        }
        current.reuse(source);
    }

    private String summarize(RuntimeException exception) {
        String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }

    private boolean isStaleRunning(AiCallLog log) {
        return log.getStatus() == AiCallLogStatus.RUNNING && log.getStartedAt() != null
                && log.getStartedAt().plusSeconds(runningTimeoutSeconds).isBefore(LocalDateTime.now());
    }

    private List<AnalysisInputSnapshotContextSource> allSources(StageLease lease) {
        return java.util.stream.Stream.concat(lease.primarySources().stream(), lease.supplementarySources().stream()).toList();
    }

    private record StageLease(
            Long snapshotId,
            Long aiCallLogId,
            AiExecutionStage executionStage,
            PromptTemplate promptTemplate,
            AnalysisInputSnapshotContext context,
            List<AnalysisInputSnapshotContextSource> primarySources,
            List<AnalysisInputSnapshotContextSource> supplementarySources,
            GenerationClientSelection selection
    ) {
    }
}
