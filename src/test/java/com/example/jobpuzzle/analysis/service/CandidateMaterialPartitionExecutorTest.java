package com.example.jobpuzzle.analysis.service;

import com.example.jobpuzzle.ai.client.AiClient;
import com.example.jobpuzzle.ai.config.AiGenerationProperties;
import com.example.jobpuzzle.ai.dto.CandidateMaterialAnalysisResult;
import com.example.jobpuzzle.ai.log.AiCallLog;
import com.example.jobpuzzle.ai.log.AiCallLogRepository;
import com.example.jobpuzzle.ai.log.AiExecutionStage;
import com.example.jobpuzzle.ai.log.AiProvider;
import com.example.jobpuzzle.ai.prompt.PromptTemplate;
import com.example.jobpuzzle.ai.prompt.PromptTemplateRenderer;
import com.example.jobpuzzle.ai.prompt.PromptTemplateRepository;
import com.example.jobpuzzle.ai.service.AiClientService;
import com.example.jobpuzzle.ai.service.GenerationClientSelection;
import com.example.jobpuzzle.ai.service.GenerationInputLimitValidator;
import com.example.jobpuzzle.ai.validation.AiResponseProcessor;
import com.example.jobpuzzle.analysis.dto.AnalysisInputSnapshotContext;
import com.example.jobpuzzle.analysis.dto.AnalysisInputSnapshotContextSource;
import com.example.jobpuzzle.analysis.entity.AnalysisInputSnapshot;
import com.example.jobpuzzle.analysis.entity.CandidateMaterialPartition;
import com.example.jobpuzzle.analysis.entity.CandidateMaterialPartitionRun;
import com.example.jobpuzzle.analysis.repository.AnalysisInputSnapshotRepository;
import com.example.jobpuzzle.analysis.repository.CandidateMaterialPartitionRepository;
import com.example.jobpuzzle.analysis.repository.CandidateMaterialPartitionRunRepository;
import com.example.jobpuzzle.document.entity.UserDocumentType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class CandidateMaterialPartitionExecutorTest {

    @Test
    void reusesSucceededPartitionWithoutCallingProviderAgain() {
        CandidateMaterialPartitionRunRepository runs = mock(CandidateMaterialPartitionRunRepository.class);
        CandidateMaterialPartitionRepository partitions = mock(CandidateMaterialPartitionRepository.class);
        AnalysisInputSnapshotRepository snapshots = mock(AnalysisInputSnapshotRepository.class);
        PromptTemplateRepository prompts = mock(PromptTemplateRepository.class);
        AiClientService clients = mock(AiClientService.class);
        PromptTemplateRenderer renderer = mock(PromptTemplateRenderer.class);
        GenerationInputLimitValidator limits = mock(GenerationInputLimitValidator.class);
        AiResponseProcessor responses = mock(AiResponseProcessor.class);
        AiCallLogRepository logs = mock(AiCallLogRepository.class);
        PromptTemplate prompt = PromptTemplate.builder().promptCode("PT-CAND-001").name("json02").version("v1.1")
                .targetJson("JSON-02").templateText("x").isActive(true).build();
        AiClient client = mock(AiClient.class);
        GenerationClientSelection selection = new GenerationClientSelection(AiExecutionStage.CANDIDATE_MATERIAL_ANALYSIS,
                client, AiProvider.MOCK, "mock", 100);
        AnalysisInputSnapshot snapshot = mock(AnalysisInputSnapshot.class);
        AnalysisInputSnapshotContext context = mock(AnalysisInputSnapshotContext.class);
        when(context.getSnapshotId()).thenReturn(99L);
        AnalysisInputSnapshotContextSource source = source();
        AtomicReference<CandidateMaterialPartitionRun> run = new AtomicReference<>();
        AtomicReference<CandidateMaterialPartition> partition = new AtomicReference<>();

        when(prompts.findFirstByTargetJsonAndIsActiveTrueOrderByPromptTemplateIdDesc("JSON-02")).thenReturn(Optional.of(prompt));
        when(clients.resolve(AiExecutionStage.CANDIDATE_MATERIAL_ANALYSIS)).thenReturn(selection);
        when(snapshots.findById(99L)).thenReturn(Optional.of(snapshot));
        when(runs.findBySnapshot_SnapshotIdAndRunFingerprint(eq(99L), any())).thenAnswer(invocation -> Optional.ofNullable(run.get()));
        when(runs.save(any())).thenAnswer(invocation -> { run.compareAndSet(null, invocation.getArgument(0)); return invocation.getArgument(0); });
        when(partitions.findByPartitionRun_PartitionRunIdOrderByPartitionOrdinalAsc(any())).thenAnswer(invocation -> partition.get() == null ? List.of() : List.of(partition.get()));
        when(partitions.save(any())).thenAnswer(invocation -> { partition.compareAndSet(null, invocation.getArgument(0)); return invocation.getArgument(0); });
        when(renderer.render(eq(AiExecutionStage.CANDIDATE_MATERIAL_ANALYSIS), eq(prompt), eq(context), anyList(), anyList())).thenReturn("rendered");
        when(clients.analyzeCandidateMaterial(selection, "rendered", UserDocumentType.RESUME)).thenReturn("{}");
        when(responses.parseCandidateMaterial(eq("{}"), anyList())).thenReturn(CandidateMaterialAnalysisResult.builder()
                .availableDocumentTypes(List.of(UserDocumentType.RESUME)).missingEvidence(List.of()).build());

        CandidateMaterialPartitionExecutor executor = new CandidateMaterialPartitionExecutor(
                new CandidateMaterialPartitionPlanner(new com.example.jobpuzzle.ai.validation.AnalysisSourceMarkerParser()), runs, partitions,
                snapshots, prompts, clients, renderer, limits, responses, logs, new ObjectMapper(), new AiGenerationProperties(),
                mock(CandidateMaterialPartitionMerger.class), Runnable::run);
        var partitionLimits = new CandidateMaterialPartitionPlanner.Limits(100, 8);

        assertThat(executor.execute(context, List.of(source), partitionLimits)).isTrue();
        assertThat(executor.execute(context, List.of(source), partitionLimits)).isTrue();

        verify(clients, times(1)).analyzeCandidateMaterial(selection, "rendered", UserDocumentType.RESUME);
        assertThat(partition.get().getParsedResultJson()).contains("RESUME");
    }

    private AnalysisInputSnapshotContextSource source() {
        AnalysisInputSnapshotContextSource source = mock(AnalysisInputSnapshotContextSource.class);
        when(source.getDocumentType()).thenReturn(UserDocumentType.RESUME); when(source.getExtractionId()).thenReturn(10L);
        when(source.getDocumentId()).thenReturn(20L); when(source.getAnalysisText()).thenReturn("[SOURCE extractionId=10 documentId=20][PAGE=1][SEGMENT=seg-001]\nresume");
        when(source.withAnalysisText(any())).thenAnswer(invocation -> source);
        return source;
    }
}
