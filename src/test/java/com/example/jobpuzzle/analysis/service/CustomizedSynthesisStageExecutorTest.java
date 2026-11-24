package com.example.jobpuzzle.analysis.service;

import com.example.jobpuzzle.ai.dto.CandidateMaterialAnalysisResult;
import com.example.jobpuzzle.ai.dto.JobPostingAnalysisResult;
import com.example.jobpuzzle.ai.log.*;
import com.example.jobpuzzle.ai.prompt.PromptTemplate;
import com.example.jobpuzzle.ai.prompt.PromptTemplateRenderer;
import com.example.jobpuzzle.ai.prompt.PromptTemplateRepository;
import com.example.jobpuzzle.ai.service.AiClientService;
import com.example.jobpuzzle.ai.service.GenerationClientSelection;
import com.example.jobpuzzle.ai.service.GenerationInputLimitValidator;
import com.example.jobpuzzle.ai.validation.AiResponseProcessor;
import com.example.jobpuzzle.ai.validation.AiProcessingException;
import com.example.jobpuzzle.ai.validation.CustomizedAnalysisResponseValidator;
import com.example.jobpuzzle.ai.dto.CustomizedAnalysisGenerationResult;
import com.example.jobpuzzle.analysis.entity.*;
import com.example.jobpuzzle.analysis.rag.dto.RetrievedEvidenceContext;
import com.example.jobpuzzle.analysis.rag.dto.RetrievedEvidenceContextDto;
import com.example.jobpuzzle.analysis.rag.service.RetrievalContextService;
import com.example.jobpuzzle.analysis.repository.*;
import com.example.jobpuzzle.analysis.synthesis.service.CustomizedSynthesisEvidenceCatalogFactory;
import com.example.jobpuzzle.analysis.synthesis.service.CustomizedSynthesisProviderInputFactory;
import com.example.jobpuzzle.analysis.synthesis.service.CustomizedSynthesisResultAssembler;
import com.example.jobpuzzle.analysis.synthesis.service.CustomizedSynthesisV13SchemaFactory;
import com.example.jobpuzzle.analysis.synthesis.dto.CustomizedSynthesisEvidenceCatalog;
import com.example.jobpuzzle.analysis.synthesis.dto.CustomizedSynthesisProviderInput;
import com.example.jobpuzzle.analysis.synthesis.dto.CustomizedSynthesisProviderResult;
import com.example.jobpuzzle.guide.entity.GuideContextResult;
import com.example.jobpuzzle.guide.entity.GuideMatchType;
import com.example.jobpuzzle.guide.repository.GuideContextChunkRepository;
import com.example.jobpuzzle.guide.repository.GuideContextResultRepository;
import com.example.jobpuzzle.interview.repository.InterviewQuestionRepository;
import com.example.jobpuzzle.interview.repository.QuestionSetRepository;
import com.example.jobpuzzle.jobcategory.entity.JobCategory;
import com.example.jobpuzzle.jobcategory.entity.JobCategoryCareerLevel;
import com.example.jobpuzzle.user.entity.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CustomizedSynthesisStageExecutorTest {
    private static final long SNAPSHOT_ID = 501L;

    @Mock private AnalysisInputSnapshotRepository snapshotRepository;
    @Mock private JobPostingAnalysisRepository jobPostingRepository;
    @Mock private CandidateMaterialAnalysisRepository candidateRepository;
    @Mock private GuideContextResultRepository guideContextRepository;
    @Mock private GuideContextChunkRepository guideContextChunkRepository;
    @Mock private ReadinessResultRepository readinessRepository;
    @Mock private MatchAnalysisResultRepository matchRepository;
    @Mock private ActionPlanRepository actionPlanRepository;
    @Mock private QuestionSetRepository questionSetRepository;
    @Mock private InterviewQuestionRepository questionRepository;
    @Mock private AiCallLogRepository aiCallLogRepository;
    @Mock private PromptTemplateRepository promptTemplateRepository;
    @Mock private AiClientService aiClientService;
    @Mock private GenerationInputLimitValidator inputLimitValidator;
    @Mock private PromptTemplateRenderer promptTemplateRenderer;
    @Mock private AiResponseProcessor aiResponseProcessor;
    @Mock private CustomizedAnalysisResponseValidator validator;
    @Mock private CustomizedAnalysisInputMapper inputMapper;
    @Mock private CustomizedSynthesisResultWriter resultWriter;
    @Mock private RetrievalContextService retrievalContextService;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private Json05ExecutionDiagnostic diagnostic;
    @Mock private CustomizedSynthesisEvidenceCatalogFactory evidenceCatalogFactory;
    @Mock private CustomizedSynthesisProviderInputFactory providerInputFactory;
    @Mock private CustomizedSynthesisV13SchemaFactory v13SchemaFactory;
    @Mock private CustomizedSynthesisResultAssembler resultAssembler;
    @Mock private com.example.jobpuzzle.analysis.synthesis.service.CustomizedSynthesisV15SchemaFactory v15SchemaFactory;
    @Mock private com.example.jobpuzzle.analysis.synthesis.service.CustomizedSynthesisV15ResultAssembler v15ResultAssembler;
    @Mock private com.example.jobpuzzle.analysis.synthesis.service.CustomizedSynthesisV16SchemaFactory v16SchemaFactory;
    @Mock private com.example.jobpuzzle.analysis.synthesis.service.CustomizedSynthesisV16ResultAssembler v16ResultAssembler;
    @Mock private com.example.jobpuzzle.analysis.synthesis.service.CustomizedSynthesisV17SchemaFactory v17SchemaFactory;
    @Mock private com.example.jobpuzzle.analysis.synthesis.service.CustomizedSynthesisV17ResultAssembler v17ResultAssembler;
    @Mock private com.example.jobpuzzle.analysis.synthesis.service.CustomizedSynthesisV18SchemaFactory v18SchemaFactory;
    @Mock private com.example.jobpuzzle.analysis.synthesis.service.CustomizedSynthesisV18ResultAssembler v18ResultAssembler;
    @Mock private AnalysisInputSnapshot snapshot;
    @Mock private JobPostingAnalysis jobPosting;
    @Mock private CandidateMaterialAnalysis candidate;

    private CustomizedSynthesisStageExecutor executor;
    private AiCallLog runningLog;

    @BeforeEach
    void setUp() {
        executor = new CustomizedSynthesisStageExecutor(snapshotRepository, jobPostingRepository, candidateRepository,
                guideContextRepository, guideContextChunkRepository, readinessRepository, matchRepository,
                actionPlanRepository, questionSetRepository, questionRepository, aiCallLogRepository,
                promptTemplateRepository, aiClientService, promptTemplateRenderer, aiResponseProcessor, validator,
                inputMapper, resultWriter, new ObjectMapper(), retrievalContextService, inputLimitValidator, diagnostic,
                evidenceCatalogFactory, providerInputFactory, v13SchemaFactory, resultAssembler,
                v15SchemaFactory, v15ResultAssembler,
                v16SchemaFactory, v16ResultAssembler,
                v17SchemaFactory, v17ResultAssembler,
                v18SchemaFactory, v18ResultAssembler,
                600L, transactionManager);
        when(transactionManager.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());

        JobCategory category = JobCategory.builder().mainCategory("IT").subCategory("BACKEND")
                .careerLevel(JobCategoryCareerLevel.NEW).build();
        AnalysisCase analysisCase = AnalysisCase.builder().user(null).jobCategory(category).build();
        ReflectionTestUtils.setField(analysisCase, "status", AnalysisCaseStatus.ANALYZING);
        when(snapshot.getAnalysisCase()).thenReturn(analysisCase);
        when(snapshot.getJobCategory()).thenReturn(category);
        User owner = mock(User.class);
        when(owner.getUserId()).thenReturn(10L);
        when(snapshot.getUser()).thenReturn(owner);
        when(snapshotRepository.findWithLockBySnapshotId(SNAPSHOT_ID)).thenReturn(Optional.of(snapshot));
        when(jobPostingRepository.findBySnapshot_SnapshotId(SNAPSHOT_ID)).thenReturn(Optional.of(jobPosting));
        when(candidateRepository.findBySnapshot_SnapshotId(SNAPSHOT_ID)).thenReturn(Optional.of(candidate));
        when(readinessRepository.existsBySnapshot_SnapshotId(SNAPSHOT_ID)).thenReturn(false);
        when(matchRepository.existsBySnapshot_SnapshotId(SNAPSHOT_ID)).thenReturn(false);
        when(actionPlanRepository.existsBySnapshot_SnapshotId(SNAPSHOT_ID)).thenReturn(false);
        when(questionSetRepository.existsBySnapshot_SnapshotIdAndInterviewMode(anyLong(), any())).thenReturn(false);

        PromptTemplate initialPrompt = prompt("JSON-01", 1L);
        AiCallLog json01Log = succeededLog(initialPrompt, AiExecutionStage.JOB_POSTING_ANALYSIS);
        AiCallLog json02Log = succeededLog(initialPrompt, AiExecutionStage.CANDIDATE_MATERIAL_ANALYSIS);
        when(jobPosting.getAiCallLog()).thenReturn(json01Log);
        when(candidate.getAiCallLog()).thenReturn(json02Log);
        when(inputMapper.jobPosting(jobPosting)).thenReturn(JobPostingAnalysisResult.builder()
                .mainTasks(List.of()).requirements(List.of()).preferred(List.of()).companyValues(List.of())
                .coreCompetencies(List.of()).conflicts(List.of()).missingEvidence(List.of()).build());
        when(inputMapper.candidate(candidate)).thenReturn(CandidateMaterialAnalysisResult.builder()
                .availableDocumentTypes(List.of()).missingEvidence(List.of()).build());

        GuideContextResult guide = GuideContextResult.create(null, String.valueOf(SNAPSHOT_ID), category, null, GuideMatchType.NONE);
        ReflectionTestUtils.setField(guide, "guideContextResultId", 80L);
        when(guideContextRepository.findByPurposeAndInputReferenceTypeAndInputReferenceId(any(), any(), eq(String.valueOf(SNAPSHOT_ID))))
                .thenReturn(Optional.of(guide));
        when(guideContextChunkRepository.findByGuideContextResult_GuideContextResultIdOrderByDisplayOrderAsc(80L)).thenReturn(List.of());

        PromptTemplate synthesisPrompt = prompt("JSON-05", 2L);
        when(promptTemplateRepository.findFirstByTargetJsonAndIsActiveTrueOrderByPromptTemplateIdDesc("JSON-05"))
                .thenReturn(Optional.of(synthesisPrompt));
        when(aiClientService.resolve(any())).thenAnswer(invocation -> new GenerationClientSelection(
                invocation.getArgument(0), mock(com.example.jobpuzzle.ai.client.AiClient.class), AiProvider.MOCK, "mock-v1", 4096));
        when(retrievalContextService.getCandidateEvidenceContext(anyLong(), eq(SNAPSHOT_ID)))
                .thenReturn(new RetrievedEvidenceContext(new RetrievedEvidenceContextDto(List.of()), "empty-retrieval"));
        when(aiCallLogRepository.findFirstByExecutionStageAndInputReferenceTypeAndInputReferenceIdAndInputFingerprintOrderByAiCallLogIdDesc(
                any(), any(), anyString(), anyString())).thenReturn(Optional.empty());
        when(aiCallLogRepository.saveAndFlush(any(AiCallLog.class))).thenAnswer(invocation -> {
            runningLog = invocation.getArgument(0);
            ReflectionTestUtils.setField(runningLog, "aiCallLogId", 900L);
            return runningLog;
        });
        when(aiCallLogRepository.findById(900L)).thenAnswer(invocation -> Optional.ofNullable(runningLog));
    }

    @Test
    void unexpectedProviderBoundaryFailureRecordsOnlyItsExceptionType() {
        when(promptTemplateRenderer.renderCustomizedAnalysis(any(), anyString(), anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn("rendered prompt");
        when(aiClientService.generateCustomizedAnalysis(any(GenerationClientSelection.class), eq("rendered prompt")))
                .thenThrow(new IllegalStateException("provider unavailable"));

        assertThatThrownBy(() -> executor.execute(SNAPSHOT_ID))
                .isInstanceOf(com.example.jobpuzzle.global.error.CustomException.class)
                .extracting(error -> ((com.example.jobpuzzle.global.error.CustomException) error).getErrorCode())
                .isEqualTo(com.example.jobpuzzle.global.error.ErrorCode.AI_RESPONSE_INVALID);

        verify(resultWriter, never()).write(anyLong(), anyLong(), any(), any());
        assertThat(runningLog.getStatus()).isEqualTo(AiCallLogStatus.FAILED);
        assertThat(runningLog.getErrorType()).isEqualTo(AiCallLogErrorType.PROVIDER_ERROR);
        assertThat(runningLog.getErrorMessage())
                .isEqualTo("JSON-05 provider boundary failed; exceptionType=IllegalStateException")
                .doesNotContain("provider unavailable");
    }

    @Test
    void unexpectedPostProcessingFailureRecordsOnlyItsExceptionType() {
        when(promptTemplateRenderer.renderCustomizedAnalysis(any(), anyString(), anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn("rendered prompt");
        when(aiClientService.generateCustomizedAnalysis(any(GenerationClientSelection.class), eq("rendered prompt")))
                .thenReturn("{}");
        when(aiResponseProcessor.parseCustomizedAnalysis("{}")).thenThrow(new IllegalStateException("response detail must not be logged"));

        assertThatThrownBy(() -> executor.execute(SNAPSHOT_ID))
                .isInstanceOf(com.example.jobpuzzle.global.error.CustomException.class)
                .extracting(error -> ((com.example.jobpuzzle.global.error.CustomException) error).getErrorCode())
                .isEqualTo(com.example.jobpuzzle.global.error.ErrorCode.AI_RESPONSE_INVALID);

        assertThat(runningLog.getErrorType()).isEqualTo(AiCallLogErrorType.RESPONSE_PARSE_FAILED);
        assertThat(runningLog.getErrorMessage())
                .isEqualTo("AI response parsing failed")
                .doesNotContain("response detail");
    }

    @Test
    void unexpectedValidatorFailureIsNotMisclassifiedAsAProviderFailure() {
        when(promptTemplateRenderer.renderCustomizedAnalysis(any(), anyString(), anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn("rendered prompt");
        when(aiClientService.generateCustomizedAnalysis(any(GenerationClientSelection.class), eq("rendered prompt")))
                .thenReturn("{}");
        when(aiResponseProcessor.parseCustomizedAnalysis("{}")).thenReturn(mock(CustomizedAnalysisGenerationResult.class));
        when(validator.validate(any(), any())).thenThrow(new IllegalStateException("validator implementation detail"));

        assertThatThrownBy(() -> executor.execute(SNAPSHOT_ID))
                .isInstanceOf(com.example.jobpuzzle.global.error.CustomException.class)
                .extracting(error -> ((com.example.jobpuzzle.global.error.CustomException) error).getErrorCode())
                .isEqualTo(com.example.jobpuzzle.global.error.ErrorCode.AI_RESPONSE_INVALID);

        assertThat(runningLog.getErrorType()).isEqualTo(AiCallLogErrorType.RESPONSE_VALIDATION_FAILED);
        assertThat(runningLog.getErrorMessage()).isEqualTo("AI response validation failed")
                .doesNotContain("validator implementation detail");
        verify(diagnostic).failure(eq("JSON05_PROVIDER_DTO_MAPPED"), isA(IllegalStateException.class));
    }

    @Test
    void preservesValidatorFailureWhenAiCallLogFailureRecordingAlsoFails() {
        when(promptTemplateRenderer.renderCustomizedAnalysis(any(), anyString(), anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn("rendered prompt");
        when(aiClientService.generateCustomizedAnalysis(any(GenerationClientSelection.class), eq("rendered prompt")))
                .thenReturn("{}");
        when(aiResponseProcessor.parseCustomizedAnalysis("{}")).thenReturn(mock(CustomizedAnalysisGenerationResult.class));
        when(validator.validate(any(), any())).thenThrow(new AiProcessingException(
                AiCallLogErrorType.RESPONSE_VALIDATION_FAILED, "safe validator reason"));
        when(aiCallLogRepository.findById(900L)).thenThrow(new IllegalStateException("log database unavailable"));

        assertThatThrownBy(() -> executor.execute(SNAPSHOT_ID))
                .isInstanceOf(com.example.jobpuzzle.global.error.CustomException.class)
                .extracting(error -> ((com.example.jobpuzzle.global.error.CustomException) error).getErrorCode())
                .isEqualTo(com.example.jobpuzzle.global.error.ErrorCode.AI_RESPONSE_INVALID);

        verify(diagnostic).failure(eq("JSON05_PROVIDER_DTO_MAPPED"), isA(AiProcessingException.class));
        verify(diagnostic).failure(eq("JSON05_PROVIDER_DTO_MAPPED_FAILURE_RECORDING"), isA(IllegalStateException.class));
    }

    @Test
    void whitelistsOnlyAnthropicAdapterTransportExceptionTypes() {
        when(promptTemplateRenderer.renderCustomizedAnalysis(any(), anyString(), anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn("rendered prompt");
        when(aiClientService.generateCustomizedAnalysis(any(GenerationClientSelection.class), eq("rendered prompt")))
                .thenThrow(new AiProcessingException(AiCallLogErrorType.PROVIDER_ERROR,
                        "Anthropic request failed; exception=IllegalStateException; cause=NullPointerException"));

        assertThatThrownBy(() -> executor.execute(SNAPSHOT_ID))
                .isInstanceOf(com.example.jobpuzzle.global.error.CustomException.class);

        assertThat(runningLog.getErrorMessage())
                .isEqualTo("Anthropic request failed; exception=IllegalStateException; cause=NullPointerException");
    }

    @Test
    void preservesSanitizedAnthropicHttpDiagnosticForSchemaFailures() {
        when(promptTemplateRenderer.renderCustomizedAnalysis(any(), anyString(), anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn("rendered prompt");
        when(aiClientService.generateCustomizedAnalysis(any(GenerationClientSelection.class), eq("rendered prompt")))
                .thenThrow(new AiProcessingException(AiCallLogErrorType.PROVIDER_ERROR,
                        "Anthropic request failed; httpStatus=400; providerType=invalid_request_error; providerMessage=Schema is too complex for compilation.; requestId=req_safe_123"));

        assertThatThrownBy(() -> executor.execute(SNAPSHOT_ID))
                .isInstanceOf(com.example.jobpuzzle.global.error.CustomException.class);

        assertThat(runningLog.getErrorMessage())
                .isEqualTo("Anthropic request failed; httpStatus=400; providerType=invalid_request_error; providerMessage=Schema is too complex for compilation.; requestId=req_safe_123");
    }

    @Test
    void preservesCustomExceptionApiContractButRecordsItsErrorCodeAtProviderBoundary() {
        when(promptTemplateRenderer.renderCustomizedAnalysis(any(), anyString(), anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn("rendered prompt");
        when(aiClientService.generateCustomizedAnalysis(any(GenerationClientSelection.class), eq("rendered prompt")))
                .thenThrow(new com.example.jobpuzzle.global.error.CustomException(
                        com.example.jobpuzzle.global.error.ErrorCode.EMBEDDING_PROVIDER_ERROR));

        assertThatThrownBy(() -> executor.execute(SNAPSHOT_ID))
                .isInstanceOf(com.example.jobpuzzle.global.error.CustomException.class)
                .extracting(error -> ((com.example.jobpuzzle.global.error.CustomException) error).getErrorCode())
                .isEqualTo(com.example.jobpuzzle.global.error.ErrorCode.EMBEDDING_PROVIDER_ERROR);

        assertThat(runningLog.getErrorMessage())
                .isEqualTo("JSON-05 provider boundary failed; errorCode=EMBEDDING_PROVIDER_ERROR");
    }

    @Test
    void activeRunningLogBlocksAnotherProviderCall() {
        AiCallLog existing = succeededLog(prompt("JSON-05", 2L), AiExecutionStage.CUSTOMIZED_SYNTHESIS);
        existing.start();
        when(aiCallLogRepository.findByExecutionStageAndInputReferenceTypeAndInputReferenceIdAndStatusIn(
                eq(AiExecutionStage.CUSTOMIZED_SYNTHESIS), eq(AiInputReferenceType.ANALYSIS_SNAPSHOT),
                eq(String.valueOf(SNAPSHOT_ID)), any())).thenReturn(List.of(existing));

        executor.execute(SNAPSHOT_ID);

        verify(aiClientService, never()).generateCustomizedAnalysis(any(GenerationClientSelection.class), anyString());
        verify(resultWriter, never()).write(anyLong(), anyLong(), any(), any());
    }

    @Test
    void differentFingerprintRunningLogAlsoBlocksProviderCall() {
        when(aiCallLogRepository.findByExecutionStageAndInputReferenceTypeAndInputReferenceIdAndStatusIn(
                eq(AiExecutionStage.CUSTOMIZED_SYNTHESIS), eq(AiInputReferenceType.ANALYSIS_SNAPSHOT),
                eq(String.valueOf(SNAPSHOT_ID)), any())).thenReturn(List.of(runningLog("different-fingerprint", AiCallLogStatus.RUNNING)));

        executor.execute(SNAPSHOT_ID);

        verify(aiClientService, never()).generateCustomizedAnalysis(any(GenerationClientSelection.class), anyString());
        verify(aiCallLogRepository, never()).saveAndFlush(any(AiCallLog.class));
    }

    @Test
    void differentFingerprintPendingLogAlsoBlocksProviderCall() {
        AiCallLog pending = AiCallLog.pending(AiProvider.MOCK, "mock-v1", AiExecutionStage.CUSTOMIZED_SYNTHESIS,
                AiInputReferenceType.ANALYSIS_SNAPSHOT, String.valueOf(SNAPSHOT_ID), "different-fingerprint", prompt("JSON-05", 2L), null, null);
        when(aiCallLogRepository.findByExecutionStageAndInputReferenceTypeAndInputReferenceIdAndStatusIn(
                eq(AiExecutionStage.CUSTOMIZED_SYNTHESIS), eq(AiInputReferenceType.ANALYSIS_SNAPSHOT),
                eq(String.valueOf(SNAPSHOT_ID)), any())).thenReturn(List.of(pending));

        executor.execute(SNAPSHOT_ID);

        verify(aiClientService, never()).generateCustomizedAnalysis(any(GenerationClientSelection.class), anyString());
        verify(aiCallLogRepository, never()).saveAndFlush(any(AiCallLog.class));
    }

    @Test
    void runningLogForAnotherSnapshotDoesNotBlockCurrentSnapshot() {
        prepareSuccessfulExecution();
        when(aiCallLogRepository.findByExecutionStageAndInputReferenceTypeAndInputReferenceIdAndStatusIn(
                eq(AiExecutionStage.CUSTOMIZED_SYNTHESIS), eq(AiInputReferenceType.ANALYSIS_SNAPSHOT),
                eq(String.valueOf(SNAPSHOT_ID)), any())).thenReturn(List.of());

        executor.execute(SNAPSHOT_ID);

        verify(aiClientService).generateCustomizedAnalysis(any(GenerationClientSelection.class), eq("rendered prompt"));
        verify(resultWriter).write(eq(SNAPSHOT_ID), eq(900L), any(), any());
    }

    @Test
    void v14UsesProjectionSchemaAssemblerAndExistingValidatedWriter() {
        PromptTemplate v13 = prompt("JSON-05", 3L);
        ReflectionTestUtils.setField(v13, "version", "v1.4");
        when(promptTemplateRepository.findFirstByTargetJsonAndIsActiveTrueOrderByPromptTemplateIdDesc("JSON-05"))
                .thenReturn(Optional.of(v13));
        CustomizedSynthesisEvidenceCatalog authority =
                new CustomizedSynthesisEvidenceCatalog(List.of(), List.of());
        CustomizedSynthesisProviderInput input = new CustomizedSynthesisProviderInput(
                new CustomizedSynthesisProviderInput.JobContext("IT", "BACKEND", "NEW"),
                List.of(), List.of(), null,
                new com.example.jobpuzzle.analysis.synthesis.dto.CustomizedSynthesisProviderEvidenceCatalog(List.of()),
                new CustomizedSynthesisProviderInput.GenerationPolicy(false, 0));
        CustomizedSynthesisProviderResult provider = mock(CustomizedSynthesisProviderResult.class);
        CustomizedAnalysisGenerationResult assembled = mock(CustomizedAnalysisGenerationResult.class);
        com.fasterxml.jackson.databind.JsonNode schema = new ObjectMapper().createObjectNode();
        when(evidenceCatalogFactory.create(any(), any())).thenReturn(authority);
        when(providerInputFactory.create(anyString(), anyString(), anyString(), any(), any(), eq(authority)))
                .thenReturn(input);
        when(promptTemplateRenderer.renderCustomizedAnalysisV13(v13, input)).thenReturn("v1.3 prompt");
        when(v13SchemaFactory.schema(any())).thenReturn(schema);
        when(aiClientService.generateCustomizedAnalysisV13(any(), eq("v1.3 prompt"), eq(schema))).thenReturn("{}");
        when(aiResponseProcessor.parseCustomizedAnalysisV13("{}")).thenReturn(provider);
        when(resultAssembler.assemble(eq(provider), eq(authority), eq(GuideMatchType.NONE))).thenReturn(assembled);

        executor.execute(SNAPSHOT_ID);

        verify(aiClientService).generateCustomizedAnalysisV13(any(), eq("v1.3 prompt"), eq(schema));
        verify(aiClientService, never()).generateCustomizedAnalysis(any(), anyString());
        verify(validator).validate(any(), eq(assembled));
        verify(resultWriter).write(eq(SNAPSHOT_ID), eq(900L), any(), eq(assembled));
    }

    @Test
    void v15UsesFixedSlotSchemaAssemblerAndExistingValidatedWriter() {
        PromptTemplate template = prompt("JSON-05", 4L);
        ReflectionTestUtils.setField(template, "version", "v1.5");
        when(promptTemplateRepository.findFirstByTargetJsonAndIsActiveTrueOrderByPromptTemplateIdDesc("JSON-05"))
                .thenReturn(Optional.of(template));
        CustomizedSynthesisEvidenceCatalog authority =
                new CustomizedSynthesisEvidenceCatalog(List.of(), List.of());
        CustomizedSynthesisProviderInput input = new CustomizedSynthesisProviderInput(
                new CustomizedSynthesisProviderInput.JobContext("IT", "BACKEND", "NEW"),
                List.of(), List.of(), null,
                new com.example.jobpuzzle.analysis.synthesis.dto.CustomizedSynthesisProviderEvidenceCatalog(List.of()),
                new CustomizedSynthesisProviderInput.GenerationPolicy(false, 0));
        var provider = mock(com.example.jobpuzzle.analysis.synthesis.dto.CustomizedSynthesisV15ProviderResult.class);
        CustomizedAnalysisGenerationResult assembled = mock(CustomizedAnalysisGenerationResult.class);
        com.fasterxml.jackson.databind.JsonNode schema = new ObjectMapper().createObjectNode();
        when(evidenceCatalogFactory.create(any(), any())).thenReturn(authority);
        when(providerInputFactory.create(anyString(), anyString(), anyString(), any(), any(), eq(authority)))
                .thenReturn(input);
        when(promptTemplateRenderer.renderCustomizedAnalysisV13(template, input)).thenReturn("v1.5 prompt");
        when(v15SchemaFactory.schema(input)).thenReturn(schema);
        when(aiClientService.generateCustomizedAnalysisV15(any(), eq("v1.5 prompt"), eq(schema))).thenReturn("{}");
        when(aiResponseProcessor.parseCustomizedAnalysisV15("{}")).thenReturn(provider);
        when(v15ResultAssembler.assemble(provider, input, authority, GuideMatchType.NONE)).thenReturn(assembled);

        executor.execute(SNAPSHOT_ID);

        verify(aiClientService).generateCustomizedAnalysisV15(any(), eq("v1.5 prompt"), eq(schema));
        verify(validator).validate(any(), eq(assembled));
        verify(resultWriter).write(eq(SNAPSHOT_ID), eq(900L), any(), eq(assembled));
    }

    @Test
    void v16UsesRequirementScopedFlatSchemaAndExistingValidatedWriter() {
        PromptTemplate template = prompt("JSON-05", 5L);
        ReflectionTestUtils.setField(template, "version", "v1.6");
        when(promptTemplateRepository.findFirstByTargetJsonAndIsActiveTrueOrderByPromptTemplateIdDesc("JSON-05"))
                .thenReturn(Optional.of(template));
        CustomizedSynthesisEvidenceCatalog authority =
                new CustomizedSynthesisEvidenceCatalog(List.of(), List.of());
        CustomizedSynthesisProviderInput input = new CustomizedSynthesisProviderInput(
                new CustomizedSynthesisProviderInput.JobContext("IT", "BACKEND", "NEW"),
                List.of(), List.of(), null,
                new com.example.jobpuzzle.analysis.synthesis.dto.CustomizedSynthesisProviderEvidenceCatalog(List.of()),
                new CustomizedSynthesisProviderInput.GenerationPolicy(false, 0));
        var provider = mock(com.example.jobpuzzle.analysis.synthesis.dto.CustomizedSynthesisV16ProviderResult.class);
        CustomizedAnalysisGenerationResult assembled = mock(CustomizedAnalysisGenerationResult.class);
        com.fasterxml.jackson.databind.JsonNode schema = new ObjectMapper().createObjectNode();
        when(evidenceCatalogFactory.create(any(), any())).thenReturn(authority);
        when(providerInputFactory.create(anyString(), anyString(), anyString(), any(), any(), eq(authority)))
                .thenReturn(input);
        when(promptTemplateRenderer.renderCustomizedAnalysisV13(template, input)).thenReturn("v1.6 prompt");
        when(v16SchemaFactory.schema(input)).thenReturn(schema);
        when(aiClientService.generateCustomizedAnalysisV16(any(), eq("v1.6 prompt"), eq(schema))).thenReturn("{}");
        when(aiResponseProcessor.parseCustomizedAnalysisV16("{}")).thenReturn(provider);
        when(v16ResultAssembler.assemble(provider, input, authority, GuideMatchType.NONE)).thenReturn(assembled);

        executor.execute(SNAPSHOT_ID);

        verify(aiClientService).generateCustomizedAnalysisV16(any(), eq("v1.6 prompt"), eq(schema));
        verify(validator).validate(any(), eq(assembled));
        verify(resultWriter).write(eq(SNAPSHOT_ID), eq(900L), any(), eq(assembled));
    }

    @Test
    void v17UsesCompactSchemaAndExistingValidatedWriter() {
        PromptTemplate template = prompt("JSON-05", 6L);
        ReflectionTestUtils.setField(template, "version", "v1.7");
        when(promptTemplateRepository.findFirstByTargetJsonAndIsActiveTrueOrderByPromptTemplateIdDesc("JSON-05"))
                .thenReturn(Optional.of(template));
        CustomizedSynthesisEvidenceCatalog authority =
                new CustomizedSynthesisEvidenceCatalog(List.of(), List.of());
        CustomizedSynthesisProviderInput input = new CustomizedSynthesisProviderInput(
                new CustomizedSynthesisProviderInput.JobContext("IT", "BACKEND", "NEW"),
                List.of(), List.of(), null,
                new com.example.jobpuzzle.analysis.synthesis.dto.CustomizedSynthesisProviderEvidenceCatalog(List.of()),
                new CustomizedSynthesisProviderInput.GenerationPolicy(false, 0));
        var provider = mock(com.example.jobpuzzle.analysis.synthesis.dto.CustomizedSynthesisV17ProviderResult.class);
        CustomizedAnalysisGenerationResult assembled = mock(CustomizedAnalysisGenerationResult.class);
        com.fasterxml.jackson.databind.JsonNode schema = new ObjectMapper().createObjectNode();
        when(evidenceCatalogFactory.create(any(), any())).thenReturn(authority);
        when(providerInputFactory.create(anyString(), anyString(), anyString(), any(), any(), eq(authority)))
                .thenReturn(input);
        when(promptTemplateRenderer.renderCustomizedAnalysisV13(template, input)).thenReturn("v1.7 prompt");
        when(v17SchemaFactory.schema(input)).thenReturn(schema);
        when(aiClientService.generateCustomizedAnalysisV17(any(), eq("v1.7 prompt"), eq(schema))).thenReturn("{}");
        when(aiResponseProcessor.parseCustomizedAnalysisV17("{}")).thenReturn(provider);
        when(v17ResultAssembler.assemble(provider, input, authority, GuideMatchType.NONE)).thenReturn(assembled);

        executor.execute(SNAPSHOT_ID);

        verify(aiClientService).generateCustomizedAnalysisV17(any(), eq("v1.7 prompt"), eq(schema));
        verify(validator).validate(any(), eq(assembled));
        verify(resultWriter).write(eq(SNAPSHOT_ID), eq(900L), any(), eq(assembled));
    }

    @Test
    void staleDifferentFingerprintRunningLogFailsThenAllowsNewExecution() {
        AiCallLog stale = runningLog("different-fingerprint", AiCallLogStatus.RUNNING);
        ReflectionTestUtils.setField(stale, "startedAt", LocalDateTime.now().minusSeconds(601));
        when(aiCallLogRepository.findByExecutionStageAndInputReferenceTypeAndInputReferenceIdAndStatusIn(
                eq(AiExecutionStage.CUSTOMIZED_SYNTHESIS), eq(AiInputReferenceType.ANALYSIS_SNAPSHOT),
                eq(String.valueOf(SNAPSHOT_ID)), any())).thenReturn(List.of(stale));
        prepareSuccessfulExecution();

        executor.execute(SNAPSHOT_ID);

        assertThat(stale.getStatus()).isEqualTo(AiCallLogStatus.FAILED);
        assertThat(stale.getErrorType()).isEqualTo(AiCallLogErrorType.STALE_RUNNING);
        verify(aiClientService).generateCustomizedAnalysis(any(GenerationClientSelection.class), eq("rendered prompt"));
    }

    @Test
    void multipleActiveLogsAreRejectedAsIntegrityConflict() {
        when(aiCallLogRepository.findByExecutionStageAndInputReferenceTypeAndInputReferenceIdAndStatusIn(
                eq(AiExecutionStage.CUSTOMIZED_SYNTHESIS), eq(AiInputReferenceType.ANALYSIS_SNAPSHOT),
                eq(String.valueOf(SNAPSHOT_ID)), any())).thenReturn(List.of(
                runningLog("one", AiCallLogStatus.RUNNING), runningLog("two", AiCallLogStatus.PENDING)));

        assertThatThrownBy(() -> executor.execute(SNAPSHOT_ID))
                .isInstanceOf(com.example.jobpuzzle.global.error.CustomException.class)
                .extracting(error -> ((com.example.jobpuzzle.global.error.CustomException) error).getErrorCode())
                .isEqualTo(com.example.jobpuzzle.global.error.ErrorCode.JSON05_RESULT_INTEGRITY_CONFLICT);
        verify(aiClientService, never()).generateCustomizedAnalysis(any(GenerationClientSelection.class), anyString());
    }

    @Test
    void succeededLogWithoutResultIsIntegrityConflictAndDoesNotCreateNewExecution() {
        AiCallLog succeeded = runningLog("old", AiCallLogStatus.RUNNING);
        succeeded.succeed();
        when(aiCallLogRepository.findByExecutionStageAndInputReferenceTypeAndInputReferenceIdAndStatus(
                eq(AiExecutionStage.CUSTOMIZED_SYNTHESIS), eq(AiInputReferenceType.ANALYSIS_SNAPSHOT),
                eq(String.valueOf(SNAPSHOT_ID)), eq(AiCallLogStatus.SUCCEEDED))).thenReturn(List.of(succeeded));

        assertThatThrownBy(() -> executor.execute(SNAPSHOT_ID))
                .isInstanceOf(com.example.jobpuzzle.global.error.CustomException.class)
                .extracting(error -> ((com.example.jobpuzzle.global.error.CustomException) error).getErrorCode())
                .isEqualTo(com.example.jobpuzzle.global.error.ErrorCode.JSON05_RESULT_INTEGRITY_CONFLICT);
        assertThat(succeeded.getStatus()).isEqualTo(AiCallLogStatus.SUCCEEDED);
        verify(aiClientService, never()).generateCustomizedAnalysis(any(GenerationClientSelection.class), anyString());
        verify(aiCallLogRepository, never()).saveAndFlush(any(AiCallLog.class));
    }

    @Test
    void readinessOnlyResultIsRejectedAsIntegrityConflict() {
        AiCallLog successLog = customizedSucceededLog(71L);
        ReadinessResult readiness = mock(ReadinessResult.class);
        when(readiness.getAiCallLog()).thenReturn(successLog);
        when(readiness.isCanGenerateQuestions()).thenReturn(false);
        when(readinessRepository.existsBySnapshot_SnapshotId(SNAPSHOT_ID)).thenReturn(true);
        when(readinessRepository.findBySnapshot_SnapshotId(SNAPSHOT_ID)).thenReturn(Optional.of(readiness));
        when(inputMapper.jobPosting(jobPosting)).thenReturn(postingWithRequirement());
        when(matchRepository.findBySnapshot_SnapshotIdOrderByMatchIdAsc(SNAPSHOT_ID)).thenReturn(List.of());

        assertIntegrityConflict();
    }

    @Test
    void v2ResultWithoutQuestionSetIsTreatedAsCompleted() {
        // v2: 분석은 질문 세트를 만들지 않으므로 canGenerateQuestions=true여도 세트가 없는 것이 정상이다.
        AiCallLog successLog = customizedSucceededLog(72L);
        ReadinessResult readiness = mock(ReadinessResult.class);
        when(readiness.getAiCallLog()).thenReturn(successLog);
        when(readiness.isCanGenerateQuestions()).thenReturn(true);
        when(readinessRepository.existsBySnapshot_SnapshotId(SNAPSHOT_ID)).thenReturn(true);
        when(readinessRepository.findBySnapshot_SnapshotId(SNAPSHOT_ID)).thenReturn(Optional.of(readiness));
        when(matchRepository.findBySnapshot_SnapshotIdOrderByMatchIdAsc(SNAPSHOT_ID)).thenReturn(List.of());
        when(questionSetRepository.findBySnapshot_SnapshotIdAndInterviewMode(anyLong(), any())).thenReturn(Optional.empty());

        executor.execute(SNAPSHOT_ID);

        verify(aiClientService, never()).generateCustomizedAnalysis(any(GenerationClientSelection.class), anyString());
    }

    @Test
    void questionSetForQuestionGenerationDisabledResultIsRejected() {
        AiCallLog successLog = customizedSucceededLog(73L);
        ReadinessResult readiness = mock(ReadinessResult.class);
        when(readiness.getAiCallLog()).thenReturn(successLog);
        when(readiness.isCanGenerateQuestions()).thenReturn(false);
        when(readinessRepository.existsBySnapshot_SnapshotId(SNAPSHOT_ID)).thenReturn(true);
        when(readinessRepository.findBySnapshot_SnapshotId(SNAPSHOT_ID)).thenReturn(Optional.of(readiness));
        when(matchRepository.findBySnapshot_SnapshotIdOrderByMatchIdAsc(SNAPSHOT_ID)).thenReturn(List.of());
        when(questionSetRepository.findBySnapshot_SnapshotIdAndInterviewMode(anyLong(), any()))
                .thenReturn(Optional.of(mock(com.example.jobpuzzle.interview.entity.QuestionSet.class)));

        assertIntegrityConflict();
    }

    @Test
    void resultEntitiesWithDifferentAiCallLogsAreRejected() {
        AiCallLog readinessLog = customizedSucceededLog(74L);
        AiCallLog actionLog = customizedSucceededLog(75L);
        ReadinessResult readiness = mock(ReadinessResult.class);
        ActionPlan action = mock(ActionPlan.class);
        when(readiness.getAiCallLog()).thenReturn(readinessLog);
        when(readiness.isCanGenerateQuestions()).thenReturn(false);
        when(action.getAiCallLog()).thenReturn(actionLog);
        when(readinessRepository.existsBySnapshot_SnapshotId(SNAPSHOT_ID)).thenReturn(true);
        when(readinessRepository.findBySnapshot_SnapshotId(SNAPSHOT_ID)).thenReturn(Optional.of(readiness));
        when(matchRepository.findBySnapshot_SnapshotIdOrderByMatchIdAsc(SNAPSHOT_ID)).thenReturn(List.of());
        when(actionPlanRepository.findBySnapshot_SnapshotIdOrderByActionPlanIdAsc(SNAPSHOT_ID)).thenReturn(List.of(action));

        assertIntegrityConflict();
    }

    @Test
    void writerFailureUsesPersistenceErrorWithoutLeakingExceptionMessage() {
        prepareSuccessfulExecution();
        doThrow(new IllegalStateException("resume evidenceText: private candidate material"))
                .when(resultWriter).write(anyLong(), anyLong(), any(), any());

        assertThatThrownBy(() -> executor.execute(SNAPSHOT_ID))
                .isInstanceOf(com.example.jobpuzzle.global.error.CustomException.class)
                .extracting(error -> ((com.example.jobpuzzle.global.error.CustomException) error).getMessage())
                .isEqualTo("Customized analysis result persistence failed");

        assertThat(runningLog.getStatus()).isEqualTo(AiCallLogStatus.FAILED);
        assertThat(runningLog.getErrorType()).isEqualTo(AiCallLogErrorType.RESULT_PERSIST_FAILED);
        assertThat(runningLog.getErrorMessage()).isEqualTo("Customized analysis result persistence failed");
        assertThat(runningLog.getErrorMessage()).doesNotContain("private candidate material");
    }

    private void prepareSuccessfulExecution() {
        when(promptTemplateRenderer.renderCustomizedAnalysis(any(), anyString(), anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn("rendered prompt");
        when(aiClientService.generateCustomizedAnalysis(any(GenerationClientSelection.class), eq("rendered prompt"))).thenReturn("{}");
        when(aiResponseProcessor.parseCustomizedAnalysis("{}")).thenReturn(mock(CustomizedAnalysisGenerationResult.class));
    }

    private void assertIntegrityConflict() {
        assertThatThrownBy(() -> executor.execute(SNAPSHOT_ID))
                .isInstanceOf(com.example.jobpuzzle.global.error.CustomException.class)
                .extracting(error -> ((com.example.jobpuzzle.global.error.CustomException) error).getErrorCode())
                .isEqualTo(com.example.jobpuzzle.global.error.ErrorCode.JSON05_RESULT_INTEGRITY_CONFLICT);
        verify(aiClientService, never()).generateCustomizedAnalysis(any(GenerationClientSelection.class), anyString());
    }

    private JobPostingAnalysisResult postingWithRequirement() {
        return JobPostingAnalysisResult.builder().mainTasks(List.of()).requirements(List.of(
                JobPostingAnalysisResult.Requirement.builder().requirementId("required-1").text("Java").sourceRefs(List.of()).build()))
                .preferred(List.of()).companyValues(List.of()).coreCompetencies(List.of()).conflicts(List.of()).missingEvidence(List.of()).build();
    }

    private PromptTemplate prompt(String targetJson, Long id) {
        PromptTemplate prompt = PromptTemplate.builder().promptCode(targetJson).name(targetJson).version("v1")
                .targetJson(targetJson).templateText("template").isActive(true).build();
        ReflectionTestUtils.setField(prompt, "promptTemplateId", id);
        return prompt;
    }

    private AiCallLog succeededLog(PromptTemplate prompt, AiExecutionStage stage) {
        AiCallLog log = AiCallLog.pending(AiProvider.MOCK, "mock-v1", stage,
                AiInputReferenceType.ANALYSIS_SNAPSHOT, String.valueOf(SNAPSHOT_ID), stage.name(), prompt, null, null);
        log.start();
        log.succeed();
        return log;
    }

    private AiCallLog runningLog(String fingerprint, AiCallLogStatus status) {
        AiCallLog log = AiCallLog.pending(AiProvider.MOCK, "mock-v1", AiExecutionStage.CUSTOMIZED_SYNTHESIS,
                AiInputReferenceType.ANALYSIS_SNAPSHOT, String.valueOf(SNAPSHOT_ID), fingerprint, prompt("JSON-05", 2L), null, null);
        if (status == AiCallLogStatus.RUNNING) {
            log.start();
        }
        return log;
    }

    private AiCallLog customizedSucceededLog(Long id) {
        AiCallLog log = runningLog("result-" + id, AiCallLogStatus.RUNNING);
        log.succeed();
        ReflectionTestUtils.setField(log, "aiCallLogId", id);
        return log;
    }
}
