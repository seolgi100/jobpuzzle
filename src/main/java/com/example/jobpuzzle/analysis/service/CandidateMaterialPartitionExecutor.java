package com.example.jobpuzzle.analysis.service;

import com.example.jobpuzzle.ai.log.AiCallLog;
import com.example.jobpuzzle.ai.log.AiCallLogErrorType;
import com.example.jobpuzzle.ai.log.AiFailureKind;
import com.example.jobpuzzle.ai.config.AiGenerationProperties;
import com.example.jobpuzzle.ai.client.AiProviderCompletionMetadata;
import com.example.jobpuzzle.ai.log.AiCallLogRepository;
import com.example.jobpuzzle.ai.log.AiCallLogStatus;
import com.example.jobpuzzle.ai.log.AiExecutionStage;
import com.example.jobpuzzle.ai.log.AiInputReferenceType;
import com.example.jobpuzzle.ai.prompt.PromptTemplate;
import com.example.jobpuzzle.ai.prompt.PromptTemplateRenderer;
import com.example.jobpuzzle.ai.prompt.PromptTemplateRepository;
import com.example.jobpuzzle.ai.service.AiClientService;
import com.example.jobpuzzle.ai.service.GenerationClientSelection;
import com.example.jobpuzzle.ai.service.GenerationInputLimitValidator;
import com.example.jobpuzzle.ai.validation.AiProcessingException;
import com.example.jobpuzzle.ai.validation.AiResponseProcessor;
import com.example.jobpuzzle.analysis.dto.AnalysisInputSnapshotContext;
import com.example.jobpuzzle.analysis.dto.AnalysisInputSnapshotContextSource;
import com.example.jobpuzzle.analysis.entity.CandidateMaterialPartition;
import com.example.jobpuzzle.analysis.entity.CandidateMaterialPartitionRun;
import com.example.jobpuzzle.analysis.entity.CandidateMaterialPartitionStatus;
import com.example.jobpuzzle.analysis.repository.CandidateMaterialPartitionRepository;
import com.example.jobpuzzle.analysis.repository.CandidateMaterialPartitionRunRepository;
import com.example.jobpuzzle.analysis.repository.AnalysisInputSnapshotRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

// 4-6b-3의 독립 executor다. 기존 /run은 아직 이 서비스를 호출하지 않아 현재 단일 JSON-02 흐름에 영향이 없다.
@Service
public class CandidateMaterialPartitionExecutor {
    private final CandidateMaterialPartitionPlanner planner;
    private final CandidateMaterialPartitionRunRepository runs;
    private final CandidateMaterialPartitionRepository partitions;
    private final AnalysisInputSnapshotRepository snapshots;
    private final PromptTemplateRepository prompts;
    private final AiClientService clients;
    private final PromptTemplateRenderer renderer;
    private final GenerationInputLimitValidator inputLimits;
    private final AiResponseProcessor responses;
    private final AiCallLogRepository logs;
    private final ObjectMapper objectMapper;
    private final AiGenerationProperties properties;
    private final CandidateMaterialPartitionMerger merger;
    private final CandidateMaterialPartitionSchemaFactory partitionSchemas;
    private final Executor partitionExecutor;

    public CandidateMaterialPartitionExecutor(CandidateMaterialPartitionPlanner planner, CandidateMaterialPartitionRunRepository runs,
                                              CandidateMaterialPartitionRepository partitions, AnalysisInputSnapshotRepository snapshots, PromptTemplateRepository prompts,
                                              AiClientService clients, PromptTemplateRenderer renderer,
                                              GenerationInputLimitValidator inputLimits, AiResponseProcessor responses,
                                              AiCallLogRepository logs, ObjectMapper objectMapper, AiGenerationProperties properties,
                                              CandidateMaterialPartitionMerger merger,
                                              @Qualifier("json02PartitionExecutor") Executor partitionExecutor) {
        this.planner = planner; this.runs = runs; this.partitions = partitions; this.snapshots = snapshots; this.prompts = prompts; this.clients = clients;
        this.renderer = renderer; this.inputLimits = inputLimits; this.responses = responses; this.logs = logs; this.objectMapper = objectMapper; this.properties = properties; this.merger = merger;
        this.partitionExecutor = partitionExecutor;
        this.partitionSchemas = new CandidateMaterialPartitionSchemaFactory(objectMapper);
    }

    public CandidateMaterialPartitionRun executeRun(AnalysisInputSnapshotContext context, List<AnalysisInputSnapshotContextSource> sources,
                           CandidateMaterialPartitionPlanner.Limits limits) {
        PromptTemplate prompt = prompts.findFirstByTargetJsonAndIsActiveTrueOrderByPromptTemplateIdDesc("JSON-02").orElseThrow();
        GenerationClientSelection selection = clients.resolve(AiExecutionStage.CANDIDATE_MATERIAL_ANALYSIS);
        List<CandidateMaterialMarkerPartition> plan = planner.plan(sources, limits);
        String manifestHash = hash(plan.stream().map(CandidateMaterialMarkerPartition::markerContentHash).reduce("", (a, b) -> a + "|" + b));
        // 같은 marker라도 thinking·output 정책이 달라지면 이전 partial 결과를 재사용하지 않는다.
        String fingerprint = hash(selection.provider() + "|" + selection.model() + "|" + prompt.getVersion() + "|" + manifestHash
                + "|" + limits + "|" + properties.generationPolicyFingerprintMaterial(AiExecutionStage.CANDIDATE_MATERIAL_ANALYSIS));
        CandidateMaterialPartitionRun run = runs.findBySnapshot_SnapshotIdAndRunFingerprint(context.getSnapshotId(), fingerprint)
                .orElseGet(() -> runs.save(CandidateMaterialPartitionRun.pending(
                        snapshots.findById(context.getSnapshotId()).orElseThrow(), fingerprint, manifestHash,
                        selection.provider(), selection.model(), prompt
                )));
        List<CandidateMaterialPartition> stored = partitions.findByPartitionRun_PartitionRunIdOrderByPartitionOrdinalAsc(run.getPartitionRunId());
        if (stored.isEmpty()) {
            stored = plan.stream().map(value -> {
                String partitionFingerprint = partitionFingerprint(selection, prompt, value);
                CandidateMaterialPartition created = CandidateMaterialPartition.pending(run, value.documentType(), value.extractionId(),
                        value.partitionIndex(), value.partitionOrdinal(), value.firstSegmentId(), value.lastSegmentId(), value.markers().size(),
                        value.markerContentHash(), partitionFingerprint);
                java.util.Optional<CandidateMaterialPartition> reusable = partitions
                        .findFirstByInputFingerprintAndStatusOrderByPartitionIdDesc(partitionFingerprint, CandidateMaterialPartitionStatus.SUCCEEDED);
                if (reusable != null) reusable.ifPresent(created::reuseFrom);
                return partitions.save(created);
            }).toList();
        }
        run.start(); runs.save(run);
        // ponytail: partition마다 완전히 독립된 입력·로그·행이라 전용 풀(json02PartitionExecutor)에서 동시에 돌려도 안전하다.
        AtomicBoolean failed = new AtomicBoolean(false);
        List<CompletableFuture<Void>> futures = new java.util.ArrayList<>();
        for (int index = 0; index < stored.size(); index++) {
            int partitionIndex = index;
            CandidateMaterialPartition storedPartition = stored.get(partitionIndex);
            if (storedPartition.getStatus() == CandidateMaterialPartitionStatus.SUCCEEDED) continue;
            futures.add(CompletableFuture.runAsync(() -> {
                if (!processPartition(partitionIndex, storedPartition, plan, context, sources, prompt, selection)) {
                    failed.set(true);
                }
            }, partitionExecutor));
        }
        futures.forEach(CompletableFuture::join);
        if (failed.get()) { run.fail(); return runs.save(run); }
        run.succeed(); return runs.save(run);
    }

    // true = 성공(또는 marker 단위 복구 성공), false = 실패 처리됨. executeRun에서 partition별로 병렬 호출한다.
    private boolean processPartition(int index, CandidateMaterialPartition storedPartition, List<CandidateMaterialMarkerPartition> plan,
                                      AnalysisInputSnapshotContext context, List<AnalysisInputSnapshotContextSource> sources,
                                      PromptTemplate prompt, GenerationClientSelection selection) {
        AiCallLog log = null;
        AiProviderCompletionMetadata completion = null;
        boolean providerStarted = false;
        try {
            CandidateMaterialMarkerPartition partition = plan.get(index);
            if (partition.oversizedSingleMarker()) throw new AiProcessingException(AiCallLogErrorType.INPUT_LIMIT_EXCEEDED, "single marker exceeds partition limit");
            List<AnalysisInputSnapshotContextSource> partitionSources = List.of(sourceFor(partition, sources));
            String rendered = renderer.render(AiExecutionStage.CANDIDATE_MATERIAL_ANALYSIS, prompt, context, partitionSources, List.of());
            inputLimits.validateRenderedPrompt(selection, rendered);
            log = AiCallLog.pending(selection.provider(), selection.model(), AiExecutionStage.CANDIDATE_MATERIAL_ANALYSIS,
                    AiInputReferenceType.ANALYSIS_SNAPSHOT, String.valueOf(context.getSnapshotId()), storedPartition.getInputFingerprint(), prompt, null, null);
            log.start(); logs.save(log); storedPartition.start(log); partitions.save(storedPartition);
            providerStarted = true;
            String raw = clients.analyzeCandidateMaterial(selection, rendered, partition.documentType());
            completion = clients.consumeCompletionMetadata(selection);
            var result = responses.parseCandidateMaterial(raw, partitionSources);
            storedPartition.succeed(objectMapper.writeValueAsString(result));
            recordMetadata(log, completion == null ? null : completion.withPostProcessing(true, true, true, false, AiFailureKind.NONE));
            log.succeed(); partitions.save(storedPartition); logs.save(log);
            return true;
        } catch (RuntimeException | JsonProcessingException exception) {
            CandidateMaterialMarkerPartition failedPartition = plan.get(index);
            if (recoverBySingleMarkers(exception, failedPartition, context, sources, prompt, selection, storedPartition)) {
                return true;
            }
            if (log == null && !providerStarted) {
                log = AiCallLog.preparationFailure(selection.provider(), selection.model(), AiExecutionStage.CANDIDATE_MATERIAL_ANALYSIS,
                        AiInputReferenceType.ANALYSIS_SNAPSHOT, String.valueOf(context.getSnapshotId()), storedPartition.getInputFingerprint(), prompt);
                log.start();
                log.fail(errorType(exception), safeMessage(exception));
                logs.save(log);
                storedPartition.start(log);
            }
            if (log != null && log.getStatus() == AiCallLogStatus.RUNNING) {
                recordMetadata(log, failureMetadata(exception, completion));
                log.fail(errorType(exception), safeMessage(exception));
                logs.save(log);
            }
            storedPartition.fail(failureKind(exception)); partitions.save(storedPartition);
            return false;
        }
    }

    // max_tokens/JSON 미완결처럼 marker 경계가 원인인 실패만 한 단계 더 작게 재시도한다.
    private boolean recoverBySingleMarkers(Exception failure, CandidateMaterialMarkerPartition parent,
                                           AnalysisInputSnapshotContext context, List<AnalysisInputSnapshotContextSource> sources,
                                           PromptTemplate prompt, GenerationClientSelection selection,
                                           CandidateMaterialPartition stored) {
        if (parent.markers().size() < 2 || errorType(failure) != AiCallLogErrorType.RESPONSE_PARSE_FAILED) return false;
        try {
            List<com.example.jobpuzzle.ai.dto.CandidateMaterialAnalysisResult> children = new java.util.ArrayList<>();
            for (var marker : parent.markers()) {
                CandidateMaterialMarkerPartition child = new CandidateMaterialMarkerPartition(parent.documentType(), parent.extractionId(),
                        parent.documentId(), parent.partitionIndex(), parent.partitionOrdinal(), List.of(marker),
                        marker.segmentText() == null ? 0 : marker.segmentText().length(), false, parent.markerContentHash());
                List<AnalysisInputSnapshotContextSource> childSources = List.of(sourceFor(child, sources));
                String rendered = renderer.render(AiExecutionStage.CANDIDATE_MATERIAL_ANALYSIS, prompt, context, childSources, List.of());
                inputLimits.validateRenderedPrompt(selection, rendered);
                AiCallLog childLog = AiCallLog.pending(selection.provider(), selection.model(), AiExecutionStage.CANDIDATE_MATERIAL_ANALYSIS,
                        AiInputReferenceType.ANALYSIS_SNAPSHOT, String.valueOf(context.getSnapshotId()), stored.getInputFingerprint(),
                        prompt, null, stored.getAiCallLog());
                childLog.start(); logs.save(childLog);
                String raw = clients.analyzeCandidateMaterial(selection, rendered, child.documentType());
                AiProviderCompletionMetadata completion = clients.consumeCompletionMetadata(selection);
                children.add(responses.parseCandidateMaterial(raw, childSources));
                recordMetadata(childLog, completion == null ? null : completion.withPostProcessing(true, true, true, false, AiFailureKind.NONE));
                childLog.succeed(); logs.save(childLog);
            }
            stored.succeed(objectMapper.writeValueAsString(merger.mergeResults(children, sources)));
            partitions.save(stored);
            return true;
        } catch (RuntimeException | JsonProcessingException ignored) {
            return false;
        }
    }

    public boolean execute(AnalysisInputSnapshotContext context, List<AnalysisInputSnapshotContextSource> sources,
                           CandidateMaterialPartitionPlanner.Limits limits) {
        return executeRun(context, sources, limits).getStatus() == com.example.jobpuzzle.analysis.entity.CandidateMaterialPartitionRunStatus.SUCCEEDED;
    }

    private AnalysisInputSnapshotContextSource sourceFor(CandidateMaterialMarkerPartition partition, List<AnalysisInputSnapshotContextSource> sources) {
        AnalysisInputSnapshotContextSource origin = sources.stream().filter(source -> source.getDocumentType() == partition.documentType()
                && source.getExtractionId().equals(partition.extractionId())).findFirst().orElseThrow();
        String text = partition.markers().stream().map(marker -> "[SOURCE extractionId=" + marker.extractionId() + " documentId=" + marker.documentId()
                + "][PAGE=" + marker.pageNumber() + "][SEGMENT=" + marker.segmentId() + "]\n" + marker.segmentText()).reduce("", (a, b) -> a.isBlank() ? b : a + "\n\n" + b);
        return origin.withAnalysisText(text);
    }

    private String hash(String value) { try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception exception) { throw new IllegalStateException(exception); } }
    private String partitionFingerprint(GenerationClientSelection selection, PromptTemplate prompt, CandidateMaterialMarkerPartition partition) {
        return hash(selection.provider() + "|" + selection.model() + "|" + prompt.getVersion() + "|"
                + properties.generationPolicyFingerprintMaterial(AiExecutionStage.CANDIDATE_MATERIAL_ANALYSIS) + "|"
                + partitionSchemas.fingerprintMaterial(partition.documentType()) + "|" + partition.markerContentHash());
    }
    private AiCallLogErrorType errorType(Exception exception) { return exception instanceof AiProcessingException value ? value.getErrorType() : AiCallLogErrorType.PROVIDER_ERROR; }
    private String safeMessage(Exception exception) { String value = exception.getMessage(); return value == null ? exception.getClass().getSimpleName() : value.substring(0, Math.min(value.length(), 1000)); }

    private void recordMetadata(AiCallLog log, AiProviderCompletionMetadata metadata) {
        if (metadata == null) return;
        try { log.recordProviderCompletionMetadata(objectMapper.writeValueAsString(metadata)); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("provider completion metadata serialization failed", exception); }
    }

    private AiProviderCompletionMetadata failureMetadata(Exception exception, AiProviderCompletionMetadata completion) {
        if (exception instanceof AiProcessingException processing && processing.getCompletionMetadata() != null)
            return processing.getCompletionMetadata();
        if (completion == null) return null;
        AiCallLogErrorType type = errorType(exception);
        AiFailureKind kind = switch (type) {
            case RESPONSE_PARSE_FAILED -> AiFailureKind.JSON_OBJECT_INCOMPLETE;
            case SOURCE_REFERENCE_INVALID -> AiFailureKind.SOURCE_REFERENCE_INVALID;
            case RESPONSE_VALIDATION_FAILED -> AiFailureKind.DTO_PARSE_FAILED;
            default -> AiFailureKind.PROVIDER_COMPLETION_FAILED;
        };
        return completion.withPostProcessing(type != AiCallLogErrorType.RESPONSE_PARSE_FAILED,
                type != AiCallLogErrorType.RESPONSE_PARSE_FAILED, type != AiCallLogErrorType.SOURCE_REFERENCE_INVALID,
                false, kind);
    }

    private AiFailureKind failureKind(Exception exception) {
        if (exception instanceof AiProcessingException processing && processing.getCompletionMetadata() != null
                && processing.getCompletionMetadata().failureKind() != null) return processing.getCompletionMetadata().failureKind();
        return switch (errorType(exception)) {
            case RESPONSE_PARSE_FAILED -> AiFailureKind.JSON_OBJECT_INCOMPLETE;
            case SOURCE_REFERENCE_INVALID -> AiFailureKind.SOURCE_REFERENCE_INVALID;
            case RESPONSE_VALIDATION_FAILED -> AiFailureKind.DTO_PARSE_FAILED;
            case OUTPUT_LIMIT_EXCEEDED -> AiFailureKind.OUTPUT_LIMIT_EXCEEDED;
            default -> AiFailureKind.PROVIDER_COMPLETION_FAILED;
        };
    }
}
