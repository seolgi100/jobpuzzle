package com.example.jobpuzzle.analysis.service;
import com.example.jobpuzzle.ai.log.*; import com.example.jobpuzzle.analysis.dto.*; import com.example.jobpuzzle.analysis.entity.*; import com.example.jobpuzzle.analysis.repository.*; import com.example.jobpuzzle.guide.entity.GuideContextResult; import com.example.jobpuzzle.guide.repository.*; import com.example.jobpuzzle.interview.repository.*; import org.junit.jupiter.api.*; import org.junit.jupiter.api.extension.ExtendWith; import org.mockito.*; import org.mockito.junit.jupiter.MockitoExtension; import org.springframework.test.util.ReflectionTestUtils; import java.util.*; import static org.assertj.core.api.Assertions.*; import static org.mockito.Mockito.*;
@ExtendWith(MockitoExtension.class) @org.mockito.junit.jupiter.MockitoSettings(strictness=org.mockito.quality.Strictness.LENIENT) class AnalysisStatusQueryServiceTest { @Mock AnalysisCaseRepository cases; @Mock AnalysisInputSnapshotRepository snapshots; @Mock JobPostingAnalysisRepository jobs; @Mock CandidateMaterialAnalysisRepository candidates; @Mock GuideContextResultRepository guides; @Mock ReadinessResultRepository readiness; @Mock QuestionSetRepository sets; @Mock AiCallLogRepository logs; @Mock com.example.jobpuzzle.simulation.SimulationService simulation; @InjectMocks AnalysisStatusQueryService service; AnalysisCase c;
 @BeforeEach void set(){c=AnalysisCase.builder().user(null).jobCategory(null).build();ReflectionTestUtils.setField(c,"status",AnalysisCaseStatus.INPUT_CONFIRMED);when(cases.findByAnalysisCaseIdAndUser_UserId(1L,1L)).thenReturn(Optional.of(c));when(snapshots.findByAnalysisCase_AnalysisCaseIdAndUser_UserId(1L,1L)).thenReturn(Optional.empty());}
 @Test void inputConfirmedIsNotStartedWithoutSideEffects(){var r=service.getStatus(1L,1L);assertThat(r.getAnalysisCaseStatus()).isEqualTo("INPUT_CONFIRMED");assertThat(r.getJobPostingAnalysisStatus()).isEqualTo(AnalysisStageStatus.NOT_STARTED);verifyNoInteractions(logs);verify(cases,never()).save(any());}
 @Test void reportsRunningSucceededStagesAndLatestAiFailure(){
  // snapshot 기준 단계 로그를 화면 상태와 최근 AI 실패 정보로 조합한다.
  AnalysisInputSnapshot snapshot=mock(AnalysisInputSnapshot.class);when(snapshot.getSnapshotId()).thenReturn(10L);when(snapshots.findByAnalysisCase_AnalysisCaseIdAndUser_UserId(1L,1L)).thenReturn(Optional.of(snapshot));ReflectionTestUtils.setField(c,"status",AnalysisCaseStatus.ANALYZING);
  AiCallLog pending=log(AiCallLogStatus.PENDING);AiCallLog running=log(AiCallLogStatus.RUNNING);AiCallLog succeeded=log(AiCallLogStatus.SUCCEEDED);
  JobPostingAnalysis job=mock(JobPostingAnalysis.class);when(job.getAiCallLog()).thenReturn(pending);when(jobs.findBySnapshot_SnapshotId(10L)).thenReturn(Optional.of(job));
  CandidateMaterialAnalysis candidate=mock(CandidateMaterialAnalysis.class);when(candidate.getAiCallLog()).thenReturn(running);when(candidates.findBySnapshot_SnapshotId(10L)).thenReturn(Optional.of(candidate));
  ReadinessResult result=mock(ReadinessResult.class);when(result.getAiCallLog()).thenReturn(succeeded);when(result.getStatus()).thenReturn(ReadinessResultStatus.PARTIAL);when(result.isCanGenerateQuestions()).thenReturn(true);when(readiness.findBySnapshot_SnapshotId(10L)).thenReturn(Optional.of(result));
  when(logs.findFirstByExecutionStageAndInputReferenceTypeAndInputReferenceIdOrderByAiCallLogIdDesc(AiExecutionStage.CUSTOMIZED_SYNTHESIS,AiInputReferenceType.ANALYSIS_SNAPSHOT,"10")).thenReturn(Optional.of(succeeded));
  when(guides.findByPurposeAndInputReferenceTypeAndInputReferenceId(any(),any(),eq("10"))).thenReturn(Optional.of(mock(GuideContextResult.class)));
  AiCallLog failed=log(AiCallLogStatus.FAILED);when(failed.getExecutionStage()).thenReturn(AiExecutionStage.CANDIDATE_MATERIAL_ANALYSIS);when(failed.getErrorType()).thenReturn(AiCallLogErrorType.TIMEOUT);when(failed.getErrorMessage()).thenReturn("provider timed out");when(logs.findFirstByInputReferenceTypeAndInputReferenceIdOrderByAiCallLogIdDesc(AiInputReferenceType.ANALYSIS_SNAPSHOT,"10")).thenReturn(Optional.of(failed));

  var response=service.getStatus(1L,1L);

  assertThat(response.getAnalysisCaseStatus()).isEqualTo("ANALYZING");assertThat(response.getJobPostingAnalysisStatus()).isEqualTo(AnalysisStageStatus.RUNNING);assertThat(response.getCandidateMaterialAnalysisStatus()).isEqualTo(AnalysisStageStatus.RUNNING);assertThat(response.getGuideContextStatus()).isEqualTo(AnalysisStageStatus.SUCCEEDED);assertThat(response.getCustomizedAnalysisStatus()).isEqualTo(AnalysisStageStatus.SUCCEEDED);assertThat(response.getLatestFailureStage()).isEqualTo("CANDIDATE_MATERIAL_ANALYSIS");assertThat(response.getLatestFailureType()).isEqualTo("TIMEOUT");assertThat(response.getUserMessage()).isEqualTo("분석 서비스 응답이 지연되고 있어요.");assertThat(response.getErrorCode()).isEqualTo("ANALYSIS_PROVIDER_TEMPORARILY_UNAVAILABLE");assertThat(response.getRetryable()).isTrue();
 }
 @Test void clearsHistoricalFailureWhenLatestAttemptSucceeded(){
  AnalysisInputSnapshot snapshot=mock(AnalysisInputSnapshot.class);when(snapshot.getSnapshotId()).thenReturn(10L);when(snapshots.findByAnalysisCase_AnalysisCaseIdAndUser_UserId(1L,1L)).thenReturn(Optional.of(snapshot));
  AiCallLog succeeded=log(AiCallLogStatus.SUCCEEDED);when(logs.findFirstByInputReferenceTypeAndInputReferenceIdOrderByAiCallLogIdDesc(AiInputReferenceType.ANALYSIS_SNAPSHOT,"10")).thenReturn(Optional.of(succeeded));
  var response=service.getStatus(1L,1L);
  assertThat(response.getLatestFailureStage()).isNull();assertThat(response.getLatestFailureType()).isNull();assertThat(response.getUserMessage()).isNull();
 }
 private AiCallLog log(AiCallLogStatus status){
  // 단계 상태 변환만 검증하도록 필요한 AI call log 상태를 만든다.
  AiCallLog log=mock(AiCallLog.class);when(log.getStatus()).thenReturn(status);return log;
 }
 @Test void deniesOtherOwner(){when(cases.findByAnalysisCaseIdAndUser_UserId(1L,1L)).thenReturn(Optional.empty());assertThatThrownBy(()->service.getStatus(1L,1L)).isInstanceOf(com.example.jobpuzzle.global.error.CustomException.class);}
}
