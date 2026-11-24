package com.example.jobpuzzle.analysis.service;

import com.example.jobpuzzle.analysis.entity.*;
import com.example.jobpuzzle.analysis.repository.*;
import com.example.jobpuzzle.global.error.CustomException;
import com.example.jobpuzzle.global.error.ErrorCode;
import com.example.jobpuzzle.guide.entity.GuideContextResult;
import com.example.jobpuzzle.guide.entity.GuideContextInputReferenceType;
import com.example.jobpuzzle.guide.entity.GuideContextPurpose;
import com.example.jobpuzzle.guide.repository.GuideContextChunkRepository;
import com.example.jobpuzzle.guide.repository.GuideContextResultRepository;
import com.example.jobpuzzle.interview.entity.InterviewQuestion;
import com.example.jobpuzzle.interview.entity.InterviewQuestionReviewStatus;
import com.example.jobpuzzle.interview.entity.InterviewQuestionType;
import com.example.jobpuzzle.interview.entity.InterviewSession;
import com.example.jobpuzzle.interview.entity.InterviewSessionMode;
import com.example.jobpuzzle.interview.entity.InterviewSessionStatus;
import com.example.jobpuzzle.interview.entity.QuestionSet;
import com.example.jobpuzzle.interview.repository.InterviewQuestionRepository;
import com.example.jobpuzzle.interview.repository.InterviewSessionRepository;
import com.example.jobpuzzle.interview.repository.QuestionSetRepository;
import com.example.jobpuzzle.jobcategory.entity.JobCategory;
import com.example.jobpuzzle.jobcategory.entity.JobCategoryCareerLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalysisResultQueryServiceTest {

    private static final long USER_ID = 1L;
    private static final long CASE_ID = 10L;
    private static final long SNAPSHOT_ID = 20L;

    @Mock private AnalysisCaseRepository cases;
    @Mock private AnalysisInputSnapshotRepository snapshots;
    @Mock private JobPostingAnalysisRepository postings;
    @Mock private CandidateMaterialAnalysisRepository candidates;
    @Mock private GuideContextResultRepository guides;
    @Mock private GuideContextChunkRepository chunks;
    @Mock private ReadinessResultRepository readiness;
    @Mock private MatchAnalysisResultRepository matches;
    @Mock private ActionPlanRepository actions;
    @Mock private QuestionSetRepository sets;
    @Mock private InterviewQuestionRepository questions;
    @Mock private InterviewSessionRepository sessions;
    @Mock private CustomizedAnalysisInputMapper mapper;
    @InjectMocks private AnalysisResultQueryService service;

    private AnalysisCase analysisCase;

    @BeforeEach
    void setUp() {
        // 기본 소유 case를 만들고 각 테스트가 완료 상태와 저장 결과를 선택적으로 구성한다.
        analysisCase = AnalysisCase.builder().user(null).jobCategory(null).build();
        when(cases.findByAnalysisCaseIdAndUser_UserId(CASE_ID, USER_ID)).thenReturn(Optional.of(analysisCase));
    }

    @Test
    void rejectsResultBeforeTheCaseIsCompleted() {
        // 완료 전 case는 저장 결과가 있어도 /result로 노출하지 않는다.
        ReflectionTestUtils.setField(analysisCase, "status", AnalysisCaseStatus.FAILED);

        assertError(ErrorCode.ANALYSIS_CASE_NOT_READY);
        verify(snapshots, never()).findByAnalysisCase_AnalysisCaseIdAndUser_UserId(anyLong(), anyLong());
    }

    @Test
    void rejectsCompletedCaseWhenSnapshotIsMissing() {
        // COMPLETED 상태라도 기준 snapshot이 없으면 결과 정합성을 보장할 수 없다.
        completedCase();
        when(snapshots.findByAnalysisCase_AnalysisCaseIdAndUser_UserId(CASE_ID, USER_ID)).thenReturn(Optional.empty());

        assertError(ErrorCode.SNAPSHOT_NOT_FOUND);
    }

    @Test
    void rejectsCompletedCaseWhenInitialAnalysisIsMissing() {
        // JSON-01 또는 JSON-02가 누락된 완료 결과는 무결성 충돌로 차단한다.
        completedCase();
        snapshot();
        when(postings.findBySnapshot_SnapshotId(SNAPSHOT_ID)).thenReturn(Optional.empty());

        assertError(ErrorCode.JSON05_RESULT_INTEGRITY_CONFLICT);
    }

    private void prerequisites(boolean canGenerateQuestions) {
        // JSON-01·02·04·05 저장 결과를 갖춘 completed case의 최소 무결성 fixture를 만든다.
        completedCase();
        snapshot();
        when(postings.findBySnapshot_SnapshotId(SNAPSHOT_ID)).thenReturn(Optional.of(mock(JobPostingAnalysis.class)));
        when(candidates.findBySnapshot_SnapshotId(SNAPSHOT_ID)).thenReturn(Optional.of(mock(CandidateMaterialAnalysis.class)));
        GuideContextResult guide = mock(GuideContextResult.class);
        JobCategory guideJobCategory = mock(JobCategory.class);
        lenient().when(guide.getJobCategory()).thenReturn(guideJobCategory);
        lenient().when(guide.getCareerLevel()).thenReturn(JobCategoryCareerLevel.NEW);
        when(guides.findByPurposeAndInputReferenceTypeAndInputReferenceId(
                GuideContextPurpose.CUSTOMIZED_SYNTHESIS, GuideContextInputReferenceType.ANALYSIS_SNAPSHOT, String.valueOf(SNAPSHOT_ID)))
                .thenReturn(Optional.of(guide));
        ReadinessResult result = mock(ReadinessResult.class);
        when(result.isCanGenerateQuestions()).thenReturn(canGenerateQuestions);
        lenient().when(result.getStatus()).thenReturn(ReadinessResultStatus.SUFFICIENT);
        when(readiness.findBySnapshot_SnapshotId(SNAPSHOT_ID)).thenReturn(Optional.of(result));
        when(matches.findBySnapshot_SnapshotIdOrderByMatchIdAsc(SNAPSHOT_ID)).thenReturn(List.of());
        when(actions.findBySnapshot_SnapshotIdOrderByActionPlanIdAsc(SNAPSHOT_ID)).thenReturn(List.of());
    }

    private void completedCase() {
        // 결과 조회가 허용되는 COMPLETED 상태를 만든다.
        ReflectionTestUtils.setField(analysisCase, "status", AnalysisCaseStatus.COMPLETED);
    }

    private void snapshot() {
        // 완료 결과가 참조할 snapshot ID를 repository 조회에 연결한다.
        AnalysisInputSnapshot snapshot = mock(AnalysisInputSnapshot.class);
        when(snapshot.getSnapshotId()).thenReturn(SNAPSHOT_ID);
        JobCategory jobCategory = mock(JobCategory.class);
        lenient().when(jobCategory.getCareerLevel()).thenReturn(JobCategoryCareerLevel.NEW);
        lenient().when(snapshot.getJobCategory()).thenReturn(jobCategory);
        when(snapshots.findByAnalysisCase_AnalysisCaseIdAndUser_UserId(CASE_ID, USER_ID)).thenReturn(Optional.of(snapshot));
    }

    private void assertError(ErrorCode expected) {
        // 조회 예외가 일반 오류로 바뀌지 않고 기존 ErrorCode를 유지하는지 검증한다.
        assertThatThrownBy(() -> service.getResult(USER_ID, CASE_ID))
                .isInstanceOf(CustomException.class)
                .extracting(error -> ((CustomException) error).getErrorCode())
                .isEqualTo(expected);
    }
}
