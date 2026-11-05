package com.example.jobpuzzle.analysis.service;

import com.example.jobpuzzle.ai.log.AiExecutionStage;
import com.example.jobpuzzle.analysis.dto.AnalysisInputSnapshotContext;
import com.example.jobpuzzle.analysis.dto.AnalysisInputSnapshotContextSource;
import com.example.jobpuzzle.analysis.entity.AnalysisCase;
import com.example.jobpuzzle.analysis.entity.AnalysisCaseStatus;
import com.example.jobpuzzle.analysis.entity.AnalysisInputSnapshot;
import com.example.jobpuzzle.analysis.rag.service.RequirementRetrievalService;
import com.example.jobpuzzle.analysis.rag.service.AnalysisVectorIndexService;
import com.example.jobpuzzle.analysis.repository.AnalysisCaseRepository;
import com.example.jobpuzzle.analysis.repository.AnalysisInputSnapshotRepository;
import com.example.jobpuzzle.document.entity.UserDocumentType;
import com.example.jobpuzzle.global.error.CustomException;
import com.example.jobpuzzle.global.error.ErrorCode;
import com.example.jobpuzzle.guide.service.GuideContextService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AnalysisServiceTest {

    private static final long USER_ID = 1L;
    private static final long ANALYSIS_CASE_ID = 10L;

    @Mock
    private AnalysisCaseRepository analysisCaseRepository;
    @Mock
    private AnalysisCaseService analysisCaseService;
    @Mock
    private AnalysisCaseStatusTransitionService statusTransitionService;
    @Mock
    private InitialAnalysisStageExecutor initialAnalysisStageExecutor;
    @Mock
    private CandidateMaterialPartitionOrchestrator candidateMaterialPartitionOrchestrator;
    @Mock
    private AnalysisInputSnapshotRepository analysisInputSnapshotRepository;
    @Mock
    private GuideContextService guideContextService;
    @Mock
    private RequirementRetrievalService requirementRetrievalService;
    @Mock
    private AnalysisVectorIndexService vectorIndexService;
    @Mock
    private CustomizedSynthesisStageExecutor customizedSynthesisStageExecutor;
    @Mock
    private AnalysisInputSnapshotContext context;

    @Mock
    private com.example.jobpuzzle.analysis.repository.AnalysisCaseSourceRepository analysisCaseSourceRepository;

    @Mock
    private com.example.jobpuzzle.ticket.service.TicketService ticketService;

    @InjectMocks
    private AnalysisService analysisService;

    private AnalysisCase analysisCase;
    private AnalysisInputSnapshotContextSource jobPosting;
    private AnalysisInputSnapshotContextSource companyInfo;
    private AnalysisInputSnapshotContextSource resume;

    @BeforeEach
    void setUp() {
        analysisCase = AnalysisCase.builder().user(null).jobCategory(null).build();
        ReflectionTestUtils.setField(analysisCase, "analysisCaseId", ANALYSIS_CASE_ID);
        ReflectionTestUtils.setField(analysisCase, "status", AnalysisCaseStatus.INPUT_CONFIRMED);
        when(analysisCaseRepository.findByAnalysisCaseIdAndUser_UserId(ANALYSIS_CASE_ID, USER_ID))
                .thenReturn(Optional.of(analysisCase));
        when(analysisCaseService.getAnalysisContext(USER_ID, ANALYSIS_CASE_ID)).thenReturn(context);
        when(statusTransitionService.startOrRestart(USER_ID, ANALYSIS_CASE_ID)).thenReturn(true);

        jobPosting = source(UserDocumentType.JOB_POSTING, "공고", "[SOURCE job] 공고 본문");
        companyInfo = source(UserDocumentType.COMPANY_INFO, "회사", "[SOURCE company] 회사 본문");
        resume = source(UserDocumentType.RESUME, "이력서", "[SOURCE resume] 이력서 본문");
        when(context.getSources()).thenReturn(List.of(jobPosting, companyInfo, resume));
    }

    @Test
    @DisplayName("JSON-03을 한 번 조회하고 JSON-01·02 독립 실행 단계로 자료를 분리한다")
    void coordinatesSeparatedInitialAnalysisStages() {
        analysisService.runInitialAnalysis(USER_ID, ANALYSIS_CASE_ID);

        ArgumentCaptor<List<AnalysisInputSnapshotContextSource>> primaryCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<AnalysisInputSnapshotContextSource>> supplementaryCaptor = ArgumentCaptor.forClass(List.class);
        verify(initialAnalysisStageExecutor).execute(
                eq(AiExecutionStage.JOB_POSTING_ANALYSIS), eq(context), primaryCaptor.capture(), supplementaryCaptor.capture()
        );
        assertThat(primaryCaptor.getAllValues().get(0)).containsExactly(jobPosting);
        assertThat(supplementaryCaptor.getAllValues().get(0)).containsExactly(companyInfo);
        verify(candidateMaterialPartitionOrchestrator).execute(eq(context), eq(List.of(resume)));
        verify(analysisCaseService).getAnalysisContext(USER_ID, ANALYSIS_CASE_ID);
    }

    @Test
    @DisplayName("다른 사용자의 분석 작업은 실행 단계에 전달하지 않는다")
    void rejectsAnotherUsersAnalysisCase() {
        when(analysisCaseRepository.findByAnalysisCaseIdAndUser_UserId(ANALYSIS_CASE_ID, USER_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> analysisService.runInitialAnalysis(USER_ID, ANALYSIS_CASE_ID))
                .isInstanceOf(CustomException.class)
                .extracting(exception -> ((CustomException) exception).getErrorCode())
                .isEqualTo(ErrorCode.ANALYSIS_CASE_NOT_FOUND);
        verify(analysisCaseService, never()).getAnalysisContext(any(), any());
        verify(initialAnalysisStageExecutor, never()).execute(any(), any(), any(), any());
        verify(candidateMaterialPartitionOrchestrator, never()).execute(any(), any());
    }

    @Test
    @DisplayName("INPUT_CONFIRMED 또는 ANALYZING이 아닌 작업은 실행할 수 없다")
    void rejectsAnalysisCaseOutsideRunnableStatus() {
        ReflectionTestUtils.setField(analysisCase, "status", AnalysisCaseStatus.DRAFT);

        assertThatThrownBy(() -> analysisService.runInitialAnalysis(USER_ID, ANALYSIS_CASE_ID))
                .isInstanceOf(CustomException.class)
                .extracting(exception -> ((CustomException) exception).getErrorCode())
                .isEqualTo(ErrorCode.ANALYSIS_CASE_NOT_READY);
        verify(analysisCaseService, never()).getAnalysisContext(any(), any());
    }

    @Test
    @DisplayName("맞춤 분석은 초기 분석 후 같은 snapshot의 가이드·candidate retrieval·JSON-05를 순서대로 실행한다")
    void coordinatesCustomizedAnalysisFromTheOwnedSnapshot() {
        AnalysisInputSnapshot snapshot = org.mockito.Mockito.mock(AnalysisInputSnapshot.class);
        when(snapshot.getSnapshotId()).thenReturn(200L);
        when(analysisInputSnapshotRepository.findByAnalysisCase_AnalysisCaseIdAndUser_UserId(ANALYSIS_CASE_ID, USER_ID))
                .thenReturn(Optional.of(snapshot));

        analysisService.runCustomizedAnalysis(USER_ID, ANALYSIS_CASE_ID);

        org.mockito.InOrder order = inOrder(guideContextService, requirementRetrievalService, customizedSynthesisStageExecutor);
        order.verify(guideContextService).getOrCreateCustomizedSynthesisGuideContext(USER_ID, 200L);
        order.verify(requirementRetrievalService).getOrCreateCandidateRetrievals(USER_ID, 200L, 3);
        order.verify(customizedSynthesisStageExecutor).execute(200L);
    }

    @Test
    @DisplayName("vector 색인이 준비되지 않으면 JSON 단계 전에 FAILED 전이를 요청한다")
    void stopsPipelineWhenVectorIndexIsNotReady() {
        CustomException failure = new CustomException(ErrorCode.VECTOR_INDEX_NOT_READY);
        org.mockito.Mockito.doThrow(failure).when(vectorIndexService).requireReady(USER_ID, ANALYSIS_CASE_ID);

        assertThatThrownBy(() -> analysisService.runCustomizedAnalysis(USER_ID, ANALYSIS_CASE_ID)).isSameAs(failure);

        verify(statusTransitionService).failIfAnalyzing(USER_ID, ANALYSIS_CASE_ID);
        verify(initialAnalysisStageExecutor, never()).execute(any(), any(), any(), any());
        verify(candidateMaterialPartitionOrchestrator, never()).execute(any(), any());
    }

    @Test
    @DisplayName("retrieval 실패는 case 실패 전이를 확정하고 원래 예외를 다시 전달한다")
    void marksCaseFailedWhenRetrievalFails() {
        AnalysisInputSnapshot snapshot = org.mockito.Mockito.mock(AnalysisInputSnapshot.class);
        when(snapshot.getSnapshotId()).thenReturn(200L);
        when(analysisInputSnapshotRepository.findByAnalysisCase_AnalysisCaseIdAndUser_UserId(ANALYSIS_CASE_ID, USER_ID))
                .thenReturn(Optional.of(snapshot));
        CustomException failure = new CustomException(ErrorCode.JSON05_RESULT_INTEGRITY_CONFLICT, "retrieval is missing");
        org.mockito.Mockito.doThrow(failure).when(requirementRetrievalService)
                .getOrCreateCandidateRetrievals(USER_ID, 200L, 3);

        assertThatThrownBy(() -> analysisService.runCustomizedAnalysis(USER_ID, ANALYSIS_CASE_ID))
                .isSameAs(failure);

        verify(statusTransitionService).failIfAnalyzing(USER_ID, ANALYSIS_CASE_ID);
        verify(customizedSynthesisStageExecutor, never()).execute(anyLong());
    }

    @Test
    @DisplayName("활성 가이드가 없으면 case를 FAILED로 전이하고 retrieval과 JSON-05를 호출하지 않는다")
    void marksCaseFailedWhenActiveGuideIsMissing() {
        // JSON-04 가이드 부재는 상위 파이프라인의 기존 실패 전이로 처리한다.
        AnalysisInputSnapshot snapshot = org.mockito.Mockito.mock(AnalysisInputSnapshot.class);
        when(snapshot.getSnapshotId()).thenReturn(200L);
        when(analysisInputSnapshotRepository.findByAnalysisCase_AnalysisCaseIdAndUser_UserId(ANALYSIS_CASE_ID, USER_ID))
                .thenReturn(Optional.of(snapshot));
        org.mockito.Mockito.doThrow(new CustomException(ErrorCode.GUIDE_ACTIVE_NOT_FOUND))
                .when(guideContextService).getOrCreateCustomizedSynthesisGuideContext(USER_ID, 200L);

        assertThatThrownBy(() -> analysisService.runCustomizedAnalysis(USER_ID, ANALYSIS_CASE_ID))
                .isInstanceOf(CustomException.class)
                .extracting(error -> ((CustomException) error).getErrorCode())
                .isEqualTo(ErrorCode.GUIDE_ACTIVE_NOT_FOUND);

        verify(statusTransitionService).failIfAnalyzing(USER_ID, ANALYSIS_CASE_ID);
        verify(requirementRetrievalService, never()).getOrCreateCandidateRetrievals(anyLong(), anyLong(), anyInt());
        verify(customizedSynthesisStageExecutor, never()).execute(anyLong());
    }

    @Test
    @DisplayName("초기 AI 단계가 실패하면 이후 단계로 진행하지 않고 case 실패 전이를 요청한다")
    void stopsPipelineWhenInitialAiStageFails() {
        org.mockito.Mockito.doThrow(new CustomException(ErrorCode.AI_RESPONSE_INVALID, "AI provider request failed"))
                .when(initialAnalysisStageExecutor).execute(eq(AiExecutionStage.JOB_POSTING_ANALYSIS), any(), any(), any());

        assertThatThrownBy(() -> analysisService.runCustomizedAnalysis(USER_ID, ANALYSIS_CASE_ID))
                .isInstanceOf(CustomException.class)
                .extracting(error -> ((CustomException) error).getErrorCode())
                .isEqualTo(ErrorCode.AI_RESPONSE_INVALID);

        verify(statusTransitionService).failIfAnalyzing(USER_ID, ANALYSIS_CASE_ID);
        verify(requirementRetrievalService, never()).getOrCreateCandidateRetrievals(anyLong(), anyLong(), anyInt());
    }

    @Test
    @DisplayName("JSON-05 실행 실패는 완료 처리 없이 case 실패 전이를 요청한다")
    void marksCaseFailedWhenJson05Fails() {
        AnalysisInputSnapshot snapshot = org.mockito.Mockito.mock(AnalysisInputSnapshot.class);
        when(snapshot.getSnapshotId()).thenReturn(200L);
        when(analysisInputSnapshotRepository.findByAnalysisCase_AnalysisCaseIdAndUser_UserId(ANALYSIS_CASE_ID, USER_ID))
                .thenReturn(Optional.of(snapshot));
        org.mockito.Mockito.doThrow(new CustomException(ErrorCode.AI_RESPONSE_INVALID, "AI response validation failed"))
                .when(customizedSynthesisStageExecutor).execute(200L);

        assertThatThrownBy(() -> analysisService.runCustomizedAnalysis(USER_ID, ANALYSIS_CASE_ID))
                .isInstanceOf(CustomException.class)
                .extracting(error -> ((CustomException) error).getErrorCode())
                .isEqualTo(ErrorCode.AI_RESPONSE_INVALID);

        verify(statusTransitionService).failIfAnalyzing(USER_ID, ANALYSIS_CASE_ID);
    }

    @Test
    @DisplayName("이미 ANALYZING인 동시 요청은 새 파이프라인 단계를 실행하지 않는다")
    void leavesConcurrentRunningRequestUntouched() {
        when(statusTransitionService.startOrRestart(USER_ID, ANALYSIS_CASE_ID)).thenReturn(false);

        analysisService.runCustomizedAnalysis(USER_ID, ANALYSIS_CASE_ID);

        verify(initialAnalysisStageExecutor, never()).execute(any(), any(), any(), any());
        verify(requirementRetrievalService, never()).getOrCreateCandidateRetrievals(anyLong(), anyLong(), anyInt());
        verify(customizedSynthesisStageExecutor, never()).execute(anyLong());
    }

    private AnalysisInputSnapshotContextSource source(UserDocumentType type, String displayName, String analysisText) {
        AnalysisInputSnapshotContextSource source = org.mockito.Mockito.mock(AnalysisInputSnapshotContextSource.class);
        when(source.getDocumentType()).thenReturn(type);
        when(source.getDisplayName()).thenReturn(displayName);
        when(source.getAnalysisText()).thenReturn(analysisText);
        return source;
    }
}
