package com.example.jobpuzzle.analysis.service;

import com.example.jobpuzzle.analysis.dto.AnalysisCaseResponse;
import com.example.jobpuzzle.analysis.entity.AnalysisCase;
import com.example.jobpuzzle.analysis.entity.AnalysisCaseSource;
import com.example.jobpuzzle.analysis.entity.AnalysisCaseStatus;
import com.example.jobpuzzle.analysis.entity.AnalysisInputSnapshot;
import com.example.jobpuzzle.analysis.repository.AnalysisCaseRepository;
import com.example.jobpuzzle.analysis.repository.AnalysisCaseSourceRepository;
import com.example.jobpuzzle.analysis.repository.AnalysisInputSnapshotRepository;
import com.example.jobpuzzle.analysis.repository.AnalysisInputSnapshotSourceRepository;
import com.example.jobpuzzle.document.entity.DocumentExtraction;
import com.example.jobpuzzle.document.entity.UserDocument;
import com.example.jobpuzzle.document.entity.UserDocumentType;
import com.example.jobpuzzle.document.service.DocumentExtractionService;
import com.example.jobpuzzle.interview.entity.InterviewSessionMode;
import com.example.jobpuzzle.interview.entity.QuestionSet;
import com.example.jobpuzzle.interview.repository.InterviewSessionRepository;
import com.example.jobpuzzle.interview.repository.QuestionSetRepository;
import com.example.jobpuzzle.jobcategory.entity.JobCategory;
import com.example.jobpuzzle.jobcategory.repository.JobCategoryRepository;
import com.example.jobpuzzle.user.entity.User;
import com.example.jobpuzzle.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalysisCasePreparationListTest {

    @Mock private AnalysisCaseRepository analysisCaseRepository;
    @Mock private AnalysisCaseSourceRepository analysisCaseSourceRepository;
    @Mock private AnalysisInputSnapshotRepository analysisInputSnapshotRepository;
    @Mock private AnalysisInputSnapshotSourceRepository analysisInputSnapshotSourceRepository;
    @Mock private UserRepository userRepository;
    @Mock private JobCategoryRepository jobCategoryRepository;
    @Mock private DocumentExtractionService documentExtractionService;
    @Mock private AnalysisMaterialChunkService analysisMaterialChunkService;
    @Mock private QuestionSetRepository questionSetRepository;
    @Mock private InterviewSessionRepository interviewSessionRepository;

    @InjectMocks private AnalysisCaseService service;

    @Test
    void completedAnalysisDisappearsFromPreparationListAfterAnySessionConsumesIt() {
        User user = mock(User.class);
        when(user.getUserId()).thenReturn(7L);
        JobCategory category = mock(JobCategory.class);
        AnalysisCase analysisCase = AnalysisCase.builder().user(user).jobCategory(category).build();
        ReflectionTestUtils.setField(analysisCase, "analysisCaseId", 11L);
        ReflectionTestUtils.setField(analysisCase, "status", AnalysisCaseStatus.COMPLETED);
        AnalysisInputSnapshot snapshot = mock(AnalysisInputSnapshot.class);
        when(snapshot.getSnapshotId()).thenReturn(21L);
        QuestionSet questionSet = mock(QuestionSet.class);
        when(questionSet.getQuestionSetId()).thenReturn(31L);
        when(analysisCaseRepository.findByUser_UserIdAndStatusOrderByCreatedAtDesc(
                7L, AnalysisCaseStatus.COMPLETED
        )).thenReturn(List.of(analysisCase));
        when(analysisInputSnapshotRepository.findByAnalysisCase_AnalysisCaseIdAndUser_UserId(
                11L, 7L
        )).thenReturn(Optional.of(snapshot));
        when(questionSetRepository.findBySnapshot_SnapshotIdAndInterviewMode(
                21L, InterviewSessionMode.COMPANY_FIT
        )).thenReturn(Optional.of(questionSet));
        when(interviewSessionRepository.existsByQuestionSet_QuestionSetId(31L)).thenReturn(true);

        assertThat(service.getCompletedCases(7L)).isEmpty();
    }

    @Test
    void caseListCarriesPostingAndUnusedQuestionSetFlag() {
        User user = mock(User.class);
        when(user.getUserId()).thenReturn(7L);
        JobCategory category = mock(JobCategory.class);
        AnalysisCase completed = AnalysisCase.builder().user(user).jobCategory(category).build();
        ReflectionTestUtils.setField(completed, "analysisCaseId", 11L);
        ReflectionTestUtils.setField(completed, "status", AnalysisCaseStatus.COMPLETED);
        AnalysisCase failed = AnalysisCase.builder().user(user).jobCategory(category).build();
        ReflectionTestUtils.setField(failed, "analysisCaseId", 12L);
        ReflectionTestUtils.setField(failed, "status", AnalysisCaseStatus.FAILED);
        when(analysisCaseRepository.findByUser_UserIdAndStatusInOrderByCreatedAtDesc(eq(7L), anyCollection()))
                .thenReturn(List.of(completed, failed));
        List<AnalysisCaseSource> sources = List.of(source(UserDocumentType.RESUME, 40L, "이력서"),
                source(UserDocumentType.JOB_POSTING, 41L, "LUME_채용공고"));
        when(analysisCaseSourceRepository.findByAnalysisCase_AnalysisCaseIdOrderByAnalysisCaseSourceIdAsc(11L))
                .thenReturn(sources);
        when(analysisCaseSourceRepository.findByAnalysisCase_AnalysisCaseIdOrderByAnalysisCaseSourceIdAsc(12L))
                .thenReturn(List.of());
        AnalysisInputSnapshot snapshot = mock(AnalysisInputSnapshot.class);
        when(snapshot.getSnapshotId()).thenReturn(21L);
        QuestionSet questionSet = mock(QuestionSet.class);
        when(questionSet.getQuestionSetId()).thenReturn(31L);
        when(analysisInputSnapshotRepository.findByAnalysisCase_AnalysisCaseIdAndUser_UserId(11L, 7L))
                .thenReturn(Optional.of(snapshot));
        when(questionSetRepository.findBySnapshot_SnapshotIdAndInterviewMode(21L, InterviewSessionMode.COMPANY_FIT))
                .thenReturn(Optional.of(questionSet));
        when(interviewSessionRepository.existsByQuestionSet_QuestionSetId(31L)).thenReturn(false);

        List<AnalysisCaseResponse> cases = service.getCases(7L);

        assertThat(cases).hasSize(2);
        assertThat(cases.get(0).getPostingDocumentId()).isEqualTo(41L);
        assertThat(cases.get(0).getPostingName()).isEqualTo("LUME_채용공고");
        assertThat(cases.get(0).getQuestionSetAvailable()).isTrue();
        assertThat(cases.get(1).getPostingDocumentId()).isNull();
        assertThat(cases.get(1).getQuestionSetAvailable()).isFalse();
    }

    private AnalysisCaseSource source(UserDocumentType type, Long documentId, String name) {
        UserDocument document = mock(UserDocument.class);
        when(document.getDocumentId()).thenReturn(documentId);
        when(document.getDisplayName()).thenReturn(name);
        DocumentExtraction extraction = mock(DocumentExtraction.class);
        when(extraction.getDocument()).thenReturn(document);
        AnalysisCaseSource source = mock(AnalysisCaseSource.class);
        when(source.getExtraction()).thenReturn(extraction);
        when(source.getDocumentType()).thenReturn(type);
        return source;
    }
}
