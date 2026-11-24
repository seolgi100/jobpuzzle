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
import com.example.jobpuzzle.ai.validation.AiResponseProcessor;
import com.example.jobpuzzle.analysis.dto.AnalysisInputSnapshotContext;
import com.example.jobpuzzle.analysis.dto.AnalysisInputSnapshotContextSource;
import com.example.jobpuzzle.analysis.entity.AnalysisCase;
import com.example.jobpuzzle.analysis.entity.AnalysisCaseStatus;
import com.example.jobpuzzle.analysis.entity.AnalysisInputSnapshot;
import com.example.jobpuzzle.analysis.repository.AnalysisInputSnapshotRepository;
import com.example.jobpuzzle.analysis.repository.CandidateMaterialAnalysisRepository;
import com.example.jobpuzzle.analysis.repository.JobPostingAnalysisRepository;
import com.example.jobpuzzle.document.entity.UserDocumentType;
import com.example.jobpuzzle.global.error.CustomException;
import com.example.jobpuzzle.jobcategory.entity.JobCategoryCareerLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InitialAnalysisStageExecutorTest {

    private static final long SNAPSHOT_ID = 100L;

    @Mock private AnalysisInputSnapshotRepository analysisInputSnapshotRepository;
    @Mock private JobPostingAnalysisRepository jobPostingAnalysisRepository;
    @Mock private CandidateMaterialAnalysisRepository candidateMaterialAnalysisRepository;
    @Mock private AiCallLogRepository aiCallLogRepository;
    @Mock private PromptTemplateRepository promptTemplateRepository;
    @Mock private AiClientService aiClientService;
    @Mock private GenerationInputLimitValidator inputLimitValidator;
    @Mock private PromptTemplateRenderer promptTemplateRenderer;
    @Mock private AiResponseProcessor aiResponseProcessor;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private AnalysisInputSnapshotContext context;
    @Mock private AnalysisInputSnapshot snapshot;

    private InitialAnalysisStageExecutor executor;
    private final List<AiCallLog> savedLogs = new ArrayList<>();
    private List<AnalysisInputSnapshotContextSource> jobSources;
    private List<AnalysisInputSnapshotContextSource> candidateSources;

    @BeforeEach
    void setUp() {
        executor = new InitialAnalysisStageExecutor(
                analysisInputSnapshotRepository,
                jobPostingAnalysisRepository,
                candidateMaterialAnalysisRepository,
                aiCallLogRepository,
                promptTemplateRepository,
                aiClientService,
                inputLimitValidator,
                promptTemplateRenderer,
                aiResponseProcessor,
                600L,
                transactionManager
        );

        AnalysisCase analysisCase = AnalysisCase.builder().user(null).jobCategory(null).build();
        ReflectionTestUtils.setField(analysisCase, "status", AnalysisCaseStatus.INPUT_CONFIRMED);
        when(snapshot.getSnapshotId()).thenReturn(SNAPSHOT_ID);
        when(snapshot.getAnalysisCase()).thenReturn(analysisCase);
        when(analysisInputSnapshotRepository.findWithLockBySnapshotId(SNAPSHOT_ID)).thenReturn(Optional.of(snapshot));
        when(context.getSnapshotId()).thenReturn(SNAPSHOT_ID);
        when(context.getMainCategory()).thenReturn("IT·개발");
        when(context.getSubCategory()).thenReturn("백엔드");
        when(context.getCareerLevel()).thenReturn(JobCategoryCareerLevel.NEW);
        when(aiClientService.resolve(any())).thenAnswer(invocation -> new GenerationClientSelection(
                invocation.getArgument(0), org.mockito.Mockito.mock(com.example.jobpuzzle.ai.client.AiClient.class), AiProvider.MOCK, "mock-fixed-sample", 4096));
        when(promptTemplateRenderer.fingerprintMaterial(any(), any(), any(), any(), any())).thenReturn("fingerprint-material");
        when(promptTemplateRenderer.render(any(), any(), any(), any(), any())).thenReturn("rendered prompt");
        when(aiResponseProcessor.parseJobPosting(anyString(), any())).thenReturn(JobPostingAnalysisResult.builder()
                .mainTasks(List.of()).requirements(List.of(JobPostingAnalysisResult.Requirement.builder()
                        .requirementId("REQ-1").text("Java 백엔드 개발 경험").sourceRefs(List.of(new com.example.jobpuzzle.ai.dto.SourceReference())).build()))
                .preferred(List.of()).companyValues(List.of()).coreCompetencies(List.of()).conflicts(List.of()).missingEvidence(List.of()).build());
        when(aiResponseProcessor.parseCandidateMaterial(anyString(), any())).thenReturn(CandidateMaterialAnalysisResult.builder()
                .availableDocumentTypes(List.of(UserDocumentType.RESUME)).missingEvidence(List.of()).build());
        when(jobPostingAnalysisRepository.existsBySnapshot_SnapshotId(SNAPSHOT_ID)).thenReturn(false);
        when(candidateMaterialAnalysisRepository.existsBySnapshot_SnapshotId(SNAPSHOT_ID)).thenReturn(false);
        when(jobPostingAnalysisRepository.findBySnapshot_SnapshotId(SNAPSHOT_ID)).thenReturn(Optional.empty());
        when(candidateMaterialAnalysisRepository.findBySnapshot_SnapshotId(SNAPSHOT_ID)).thenReturn(Optional.empty());
        when(aiCallLogRepository.findFirstByExecutionStageAndInputReferenceTypeAndInputReferenceIdAndInputFingerprintOrderByAiCallLogIdDesc(
                any(), any(), anyString(), anyString())).thenReturn(Optional.empty());
        when(transactionManager.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
        when(aiCallLogRepository.saveAndFlush(any(AiCallLog.class))).thenAnswer(invocation -> {
            AiCallLog log = invocation.getArgument(0);
            ReflectionTestUtils.setField(log, "aiCallLogId", (long) savedLogs.size() + 1);
            savedLogs.add(log);
            return log;
        });
        when(aiCallLogRepository.findById(anyLong())).thenAnswer(invocation -> savedLogs.stream()
                .filter(log -> log.getAiCallLogId().equals(invocation.getArgument(0)))
                .findFirst());

        PromptTemplate jobPrompt = promptTemplate("JSON-01", 1L);
        PromptTemplate candidatePrompt = promptTemplate("JSON-02", 2L);
        when(promptTemplateRepository.findFirstByTargetJsonAndIsActiveTrueOrderByPromptTemplateIdDesc("JSON-01"))
                .thenReturn(Optional.of(jobPrompt));
        when(promptTemplateRepository.findFirstByTargetJsonAndIsActiveTrueOrderByPromptTemplateIdDesc("JSON-02"))
                .thenReturn(Optional.of(candidatePrompt));

        jobSources = List.of(source(UserDocumentType.JOB_POSTING, "공고", "[SOURCE job] 공고"));
        candidateSources = List.of(source(UserDocumentType.RESUME, "이력서", "[SOURCE resume] 이력서"));
    }

    @Test
    @DisplayName("JSON-01 성공 후 JSON-02 실패는 별도 트랜잭션 로그로 기록되어 JSON-01을 보존한다")
    void preservesJobPostingResultWhenCandidateMaterialFails() {
        when(aiClientService.analyzeJobPosting(any(GenerationClientSelection.class), anyString())).thenReturn("{}");
        when(aiClientService.analyzeCandidateMaterial(any(GenerationClientSelection.class), anyString())).thenThrow(new IllegalStateException("candidate provider failure"));

        executor.execute(AiExecutionStage.JOB_POSTING_ANALYSIS, context, jobSources, List.of());
        assertThatThrownBy(() -> executor.execute(AiExecutionStage.CANDIDATE_MATERIAL_ANALYSIS, context, candidateSources, List.of()))
                .isInstanceOf(com.example.jobpuzzle.global.error.CustomException.class)
                .extracting(error -> ((com.example.jobpuzzle.global.error.CustomException) error).getErrorCode())
                .isEqualTo(com.example.jobpuzzle.global.error.ErrorCode.AI_RESPONSE_INVALID);

        verify(jobPostingAnalysisRepository).saveAndFlush(any());
        verify(candidateMaterialAnalysisRepository, never()).saveAndFlush(any());
        assertThat(savedLogs).hasSize(2);
        assertThat(savedLogs.get(0).getStatus()).isEqualTo(AiCallLogStatus.SUCCEEDED);
        assertThat(savedLogs.get(1).getExecutionStage()).isEqualTo(AiExecutionStage.CANDIDATE_MATERIAL_ANALYSIS);
        assertThat(savedLogs.get(1).getStatus()).isEqualTo(AiCallLogStatus.FAILED);
        assertThat(savedLogs.get(1).getErrorType()).isEqualTo(AiCallLogErrorType.PROVIDER_ERROR);
        assertThat(savedLogs.get(1).getErrorMessage()).contains("candidate provider failure");
    }

    @Test
    @DisplayName("typed provider rate limit은 AI_001과 안전한 quota 메시지로 전달하고 log 유형을 보존한다")
    void preservesRateLimitTypeForApiAndAiCallLog() {
        when(aiClientService.analyzeJobPosting(any(GenerationClientSelection.class), anyString())).thenThrow(new com.example.jobpuzzle.ai.validation.AiProcessingException(
                AiCallLogErrorType.RATE_LIMIT, "provider quota detail"));

        CustomException exception = catchThrowableOfType(
                () -> executor.execute(AiExecutionStage.JOB_POSTING_ANALYSIS, context, jobSources, List.of()),
                CustomException.class);
        assertThat(exception.getErrorCode()).isEqualTo(com.example.jobpuzzle.global.error.ErrorCode.AI_RESPONSE_INVALID);
        assertThat(exception.getMessage()).isEqualTo("AI provider rate limit exceeded");

        assertThat(savedLogs).hasSize(1);
        assertThat(savedLogs.get(0).getErrorType()).isEqualTo(AiCallLogErrorType.RATE_LIMIT);
    }

    @Test
    @DisplayName("공고에서 자격요건·우대사항을 하나도 찾지 못하면 결과를 저장하지 않고 입력 문제로 실패시킨다")
    void failsWithRequirementsNotFoundWhenPostingHasNoRequirements() {
        when(aiClientService.analyzeJobPosting(any(GenerationClientSelection.class), anyString())).thenReturn("{}");
        when(aiResponseProcessor.parseJobPosting(anyString(), any())).thenReturn(JobPostingAnalysisResult.builder()
                .mainTasks(List.of()).requirements(List.of()).preferred(List.of()).companyValues(List.of())
                .coreCompetencies(List.of()).conflicts(List.of()).missingEvidence(List.of()).build());

        CustomException exception = catchThrowableOfType(
                () -> executor.execute(AiExecutionStage.JOB_POSTING_ANALYSIS, context, jobSources, List.of()),
                CustomException.class);

        // AI 오류(AI_001)가 아니라 사용자가 고칠 수 있는 공고 문제로 알려야 한다.
        assertThat(exception.getErrorCode()).isEqualTo(com.example.jobpuzzle.global.error.ErrorCode.JOB_POSTING_REQUIREMENTS_NOT_FOUND);
        verify(jobPostingAnalysisRepository, never()).saveAndFlush(any());
        assertThat(savedLogs).hasSize(1);
        assertThat(savedLogs.get(0).getStatus()).isEqualTo(AiCallLogStatus.FAILED);
        assertThat(savedLogs.get(0).getErrorType()).isEqualTo(AiCallLogErrorType.REQUIREMENTS_NOT_FOUND);
    }

    @Test
    @DisplayName("요구사항 없음으로 실패한 공고는 같은 입력으로 다시 실행해도 AI를 다시 부르지 않는다")
    void doesNotCallAiAgainForPostingWithoutRequirements() {
        when(aiClientService.analyzeJobPosting(any(GenerationClientSelection.class), anyString())).thenReturn("{}");
        when(aiResponseProcessor.parseJobPosting(anyString(), any())).thenReturn(JobPostingAnalysisResult.builder()
                .mainTasks(List.of()).requirements(List.of()).preferred(List.of()).companyValues(List.of())
                .coreCompetencies(List.of()).conflicts(List.of()).missingEvidence(List.of()).build());
        when(aiCallLogRepository.findFirstByExecutionStageAndInputReferenceTypeAndInputReferenceIdAndInputFingerprintOrderByAiCallLogIdDesc(
                eq(AiExecutionStage.JOB_POSTING_ANALYSIS),
                eq(AiInputReferenceType.ANALYSIS_SNAPSHOT),
                eq(String.valueOf(SNAPSHOT_ID)),
                anyString()))
                .thenAnswer(invocation -> savedLogs.isEmpty() ? Optional.empty() : Optional.of(savedLogs.get(savedLogs.size() - 1)));

        assertThatThrownBy(() -> executor.execute(AiExecutionStage.JOB_POSTING_ANALYSIS, context, jobSources, List.of()))
                .isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> executor.execute(AiExecutionStage.JOB_POSTING_ANALYSIS, context, jobSources, List.of()))
                .isInstanceOf(CustomException.class)
                .extracting(error -> ((CustomException) error).getErrorCode())
                .isEqualTo(com.example.jobpuzzle.global.error.ErrorCode.JOB_POSTING_REQUIREMENTS_NOT_FOUND);

        verify(aiClientService, org.mockito.Mockito.times(1)).analyzeJobPosting(any(GenerationClientSelection.class), anyString());
        assertThat(savedLogs).hasSize(1);
    }

    @Test
    @DisplayName("FAILED 단계는 새 RUNNING 로그를 만들고 재시도한다")
    void retriesFailedStageOnly() {
        when(aiClientService.analyzeCandidateMaterial(any(GenerationClientSelection.class), anyString()))
                .thenThrow(new IllegalStateException("first failure"))
                .thenReturn("{}");
        when(aiCallLogRepository.findFirstByExecutionStageAndInputReferenceTypeAndInputReferenceIdAndInputFingerprintOrderByAiCallLogIdDesc(
                eq(AiExecutionStage.CANDIDATE_MATERIAL_ANALYSIS),
                eq(AiInputReferenceType.ANALYSIS_SNAPSHOT),
                eq(String.valueOf(SNAPSHOT_ID)),
                anyString()))
                .thenAnswer(invocation -> savedLogs.isEmpty() ? Optional.empty() : Optional.of(savedLogs.get(savedLogs.size() - 1)));

        assertThatThrownBy(() -> executor.execute(AiExecutionStage.CANDIDATE_MATERIAL_ANALYSIS, context, candidateSources, List.of()))
                .isInstanceOf(com.example.jobpuzzle.global.error.CustomException.class);
        executor.execute(AiExecutionStage.CANDIDATE_MATERIAL_ANALYSIS, context, candidateSources, List.of());

        verify(aiClientService, org.mockito.Mockito.times(2)).analyzeCandidateMaterial(any(GenerationClientSelection.class), anyString());
        verify(candidateMaterialAnalysisRepository).saveAndFlush(any());
        assertThat(savedLogs).hasSize(2);
        assertThat(savedLogs.get(0).getStatus()).isEqualTo(AiCallLogStatus.FAILED);
        assertThat(savedLogs.get(1).getStatus()).isEqualTo(AiCallLogStatus.SUCCEEDED);
        assertThat(savedLogs.get(1).getParentAiCallLog()).isSameAs(savedLogs.get(0));
    }

    @Test
    @DisplayName("RUNNING 로그가 있으면 같은 fingerprint의 AI 호출을 실행하지 않는다")
    void doesNotCallAiForRunningStage() {
        AiCallLog running = runningLog();
        when(aiCallLogRepository.findFirstByExecutionStageAndInputReferenceTypeAndInputReferenceIdAndInputFingerprintOrderByAiCallLogIdDesc(
                eq(AiExecutionStage.JOB_POSTING_ANALYSIS), eq(AiInputReferenceType.ANALYSIS_SNAPSHOT),
                eq(String.valueOf(SNAPSHOT_ID)), anyString())).thenReturn(Optional.of(running));

        executor.execute(AiExecutionStage.JOB_POSTING_ANALYSIS, context, jobSources, List.of());

        verify(aiClientService, never()).analyzeJobPosting(any(GenerationClientSelection.class), anyString());
        verify(jobPostingAnalysisRepository, never()).saveAndFlush(any());
        verify(aiCallLogRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("SUCCEEDED 로그가 있으면 같은 fingerprint의 AI 호출을 재실행하지 않는다")
    void doesNotCallAiForSucceededStage() {
        AiCallLog succeeded = runningLog();
        succeeded.succeed();
        when(aiCallLogRepository.findFirstByExecutionStageAndInputReferenceTypeAndInputReferenceIdAndInputFingerprintOrderByAiCallLogIdDesc(
                eq(AiExecutionStage.JOB_POSTING_ANALYSIS), eq(AiInputReferenceType.ANALYSIS_SNAPSHOT),
                eq(String.valueOf(SNAPSHOT_ID)), anyString())).thenReturn(Optional.of(succeeded));

        executor.execute(AiExecutionStage.JOB_POSTING_ANALYSIS, context, jobSources, List.of());

        verify(aiClientService, never()).analyzeJobPosting(any(GenerationClientSelection.class), anyString());
        verify(jobPostingAnalysisRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("활성 프롬프트 템플릿이 없으면 로그와 AI 호출을 만들지 않는다")
    void rejectsStageWithoutActivePromptTemplate() {
        when(promptTemplateRepository.findFirstByTargetJsonAndIsActiveTrueOrderByPromptTemplateIdDesc("JSON-01"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> executor.execute(AiExecutionStage.JOB_POSTING_ANALYSIS, context, jobSources, List.of()))
                .isInstanceOf(com.example.jobpuzzle.global.error.CustomException.class);
        verify(aiCallLogRepository, never()).saveAndFlush(any());
        verify(aiClientService, never()).analyzeJobPosting(any(GenerationClientSelection.class), anyString());
    }

    @Test
    @DisplayName("기존 snapshot 결과가 있으면 후속 요청은 AI를 다시 호출하지 않아 결과 행을 추가하지 않는다")
    void keepsAtMostOneResultForSnapshotOnRepeatedRequest() {
        when(jobPostingAnalysisRepository.existsBySnapshot_SnapshotId(SNAPSHOT_ID)).thenReturn(true);

        executor.execute(AiExecutionStage.JOB_POSTING_ANALYSIS, context, jobSources, List.of());

        verify(aiClientService, never()).analyzeJobPosting(any(GenerationClientSelection.class), anyString());
        verify(jobPostingAnalysisRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("동시 요청은 snapshot 선점 중 RUNNING 로그를 확인해 AI 호출과 결과 저장을 한 번으로 제한한다")
    void preventsDuplicateAiCallForConcurrentRequests() throws Exception {
        CountDownLatch aiStarted = new CountDownLatch(1);
        CountDownLatch releaseAi = new CountDownLatch(1);
        when(aiCallLogRepository.findFirstByExecutionStageAndInputReferenceTypeAndInputReferenceIdAndInputFingerprintOrderByAiCallLogIdDesc(
                eq(AiExecutionStage.JOB_POSTING_ANALYSIS), eq(AiInputReferenceType.ANALYSIS_SNAPSHOT),
                eq(String.valueOf(SNAPSHOT_ID)), anyString()))
                .thenAnswer(invocation -> savedLogs.isEmpty() ? Optional.empty() : Optional.of(savedLogs.get(0)));
        when(aiClientService.analyzeJobPosting(any(GenerationClientSelection.class), anyString())).thenAnswer(invocation -> {
            aiStarted.countDown();
            releaseAi.await(3, TimeUnit.SECONDS);
            return "{}";
        });

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> executor.execute(AiExecutionStage.JOB_POSTING_ANALYSIS, context, jobSources, List.of()));
            assertThat(aiStarted.await(3, TimeUnit.SECONDS)).isTrue();
            var second = pool.submit(() -> executor.execute(AiExecutionStage.JOB_POSTING_ANALYSIS, context, jobSources, List.of()));
            second.get(3, TimeUnit.SECONDS);
            releaseAi.countDown();
            first.get(3, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        verify(aiClientService, times(1)).analyzeJobPosting(any(GenerationClientSelection.class), anyString());
        verify(jobPostingAnalysisRepository, times(1)).saveAndFlush(any());
        assertThat(savedLogs).hasSize(1);
    }

    @Test
    @DisplayName("10분을 지난 RUNNING 로그는 FAILED로 종결하고 새 재시도 로그를 선점한다")
    void retriesAfterStaleRunningLog() {
        AiCallLog stale = runningLog();
        ReflectionTestUtils.setField(stale, "startedAt", LocalDateTime.now().minusSeconds(601));
        when(aiCallLogRepository.findFirstByExecutionStageAndInputReferenceTypeAndInputReferenceIdAndInputFingerprintOrderByAiCallLogIdDesc(
                eq(AiExecutionStage.JOB_POSTING_ANALYSIS), eq(AiInputReferenceType.ANALYSIS_SNAPSHOT),
                eq(String.valueOf(SNAPSHOT_ID)), anyString())).thenReturn(Optional.of(stale));
        when(aiClientService.analyzeJobPosting(any(GenerationClientSelection.class), anyString())).thenReturn("{}");

        executor.execute(AiExecutionStage.JOB_POSTING_ANALYSIS, context, jobSources, List.of());

        assertThat(stale.getStatus()).isEqualTo(AiCallLogStatus.FAILED);
        assertThat(stale.getErrorType()).isEqualTo(AiCallLogErrorType.STALE_RUNNING);
        assertThat(savedLogs).hasSize(1);
        assertThat(savedLogs.get(0).getParentAiCallLog()).isSameAs(stale);
    }

    @Test
    @DisplayName("응답 파싱·검증 실패는 결과를 저장하지 않고 해당 RUNNING 로그를 FAILED로 기록한다")
    void recordsValidationFailureWithoutSavingResult() {
        when(aiClientService.analyzeJobPosting(any(GenerationClientSelection.class), anyString())).thenReturn("not-json");
        when(aiResponseProcessor.parseJobPosting(anyString(), any()))
                .thenThrow(new com.example.jobpuzzle.ai.validation.AiProcessingException(
                        AiCallLogErrorType.RESPONSE_PARSE_FAILED, "JSON-01 parse failed"
                ));

        assertThatThrownBy(() -> executor.execute(AiExecutionStage.JOB_POSTING_ANALYSIS, context, jobSources, List.of()))
                .isInstanceOf(com.example.jobpuzzle.global.error.CustomException.class)
                .extracting(error -> ((com.example.jobpuzzle.global.error.CustomException) error).getMessage())
                .isEqualTo("AI response parsing failed");

        verify(jobPostingAnalysisRepository, never()).saveAndFlush(any());
        assertThat(savedLogs).hasSize(1);
        assertThat(savedLogs.get(0).getStatus()).isEqualTo(AiCallLogStatus.FAILED);
        assertThat(savedLogs.get(0).getErrorType()).isEqualTo(AiCallLogErrorType.RESPONSE_PARSE_FAILED);
    }

    @Test
    @DisplayName("JSON-01 성공 뒤 JSON-02 검증 실패는 JSON-01 결과를 보존한다")
    void preservesJobPostingResultWhenCandidateValidationFails() {
        when(aiClientService.analyzeJobPosting(any(GenerationClientSelection.class), anyString())).thenReturn("{}");
        when(aiClientService.analyzeCandidateMaterial(any(GenerationClientSelection.class), anyString())).thenReturn("invalid");
        when(aiResponseProcessor.parseCandidateMaterial(anyString(), any()))
                .thenThrow(new com.example.jobpuzzle.ai.validation.AiProcessingException(
                        AiCallLogErrorType.RESPONSE_VALIDATION_FAILED, "availableDocumentTypes mismatch"
                ));

        executor.execute(AiExecutionStage.JOB_POSTING_ANALYSIS, context, jobSources, List.of());
        assertThatThrownBy(() -> executor.execute(AiExecutionStage.CANDIDATE_MATERIAL_ANALYSIS, context, candidateSources, List.of()))
                .isInstanceOf(com.example.jobpuzzle.global.error.CustomException.class)
                .extracting(error -> ((com.example.jobpuzzle.global.error.CustomException) error).getMessage())
                .isEqualTo("AI response validation failed");

        verify(jobPostingAnalysisRepository).saveAndFlush(any());
        verify(candidateMaterialAnalysisRepository, never()).saveAndFlush(any());
        assertThat(savedLogs).hasSize(2);
        assertThat(savedLogs.get(0).getStatus()).isEqualTo(AiCallLogStatus.SUCCEEDED);
        assertThat(savedLogs.get(1).getErrorType()).isEqualTo(AiCallLogErrorType.RESPONSE_VALIDATION_FAILED);
    }

    private PromptTemplate promptTemplate(String targetJson, Long id) {
        PromptTemplate template = PromptTemplate.builder()
                .promptCode("PT-" + targetJson)
                .name(targetJson + " prompt")
                .version("v1.0")
                .targetJson(targetJson)
                .templateText("template " + targetJson)
                .isActive(true)
                .build();
        ReflectionTestUtils.setField(template, "promptTemplateId", id);
        return template;
    }

    private AiCallLog runningLog() {
        AiCallLog log = AiCallLog.pending(
                AiProvider.MOCK,
                "mock-fixed-sample",
                AiExecutionStage.JOB_POSTING_ANALYSIS,
                AiInputReferenceType.ANALYSIS_SNAPSHOT,
                String.valueOf(SNAPSHOT_ID),
                "fingerprint",
                promptTemplate("JSON-01", 1L),
                null,
                null
        );
        log.start();
        return log;
    }

    private AnalysisInputSnapshotContextSource source(UserDocumentType type, String displayName, String analysisText) {
        AnalysisInputSnapshotContextSource source = org.mockito.Mockito.mock(AnalysisInputSnapshotContextSource.class);
        when(source.getDocumentType()).thenReturn(type);
        when(source.getDisplayName()).thenReturn(displayName);
        when(source.getAnalysisText()).thenReturn(analysisText);
        return source;
    }
}
