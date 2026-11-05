package com.example.jobpuzzle.user.service;

import com.example.jobpuzzle.analysis.repository.ActionPlanRepository;
import com.example.jobpuzzle.analysis.repository.AnalysisCaseRepository;
import com.example.jobpuzzle.analysis.repository.AnalysisCaseSourceRepository;
import com.example.jobpuzzle.analysis.repository.AnalysisInputSnapshotRepository;
import com.example.jobpuzzle.analysis.repository.AnalysisInputSnapshotSourceRepository;
import com.example.jobpuzzle.analysis.repository.AnalysisMaterialChunkRepository;
import com.example.jobpuzzle.analysis.repository.CandidateMaterialAnalysisRepository;
import com.example.jobpuzzle.analysis.repository.ConfirmedAnalysisSnapshotRepository;
import com.example.jobpuzzle.analysis.repository.JobPostingAnalysisRepository;
import com.example.jobpuzzle.analysis.repository.MatchAnalysisResultRepository;
import com.example.jobpuzzle.analysis.repository.ReadinessResultRepository;
import com.example.jobpuzzle.document.entity.UserDocument;
import com.example.jobpuzzle.document.entity.UserDocumentSourceType;
import com.example.jobpuzzle.document.entity.UserDocumentType;
import com.example.jobpuzzle.document.repository.DocumentExtractionRepository;
import com.example.jobpuzzle.document.repository.UserDocumentRepository;
import com.example.jobpuzzle.document.storage.FileStorage;
import com.example.jobpuzzle.guide.repository.GuideContextChunkRepository;
import com.example.jobpuzzle.guide.repository.GuideContextResultRepository;
import com.example.jobpuzzle.interview.repository.InterviewQuestionRepository;
import com.example.jobpuzzle.interview.repository.QuestionSetRepository;
import com.example.jobpuzzle.jobposting.repository.JobPostingRepository;
import com.example.jobpuzzle.analysis.rag.repository.RequirementRetrievalChunkRepository;
import com.example.jobpuzzle.analysis.rag.repository.RequirementRetrievalResultRepository;
import com.example.jobpuzzle.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserWithdrawalServiceTest {

    @Mock private AnalysisCaseSourceRepository analysisCaseSourceRepository;
    @Mock private AnalysisInputSnapshotSourceRepository analysisInputSnapshotSourceRepository;
    @Mock private AnalysisMaterialChunkRepository analysisMaterialChunkRepository;
    @Mock private RequirementRetrievalChunkRepository requirementRetrievalChunkRepository;
    @Mock private RequirementRetrievalResultRepository requirementRetrievalResultRepository;
    @Mock private ConfirmedAnalysisSnapshotRepository confirmedAnalysisSnapshotRepository;
    @Mock private AnalysisInputSnapshotRepository analysisInputSnapshotRepository;
    @Mock private JobPostingAnalysisRepository jobPostingAnalysisRepository;
    @Mock private CandidateMaterialAnalysisRepository candidateMaterialAnalysisRepository;
    @Mock private ActionPlanRepository actionPlanRepository;
    @Mock private ReadinessResultRepository readinessResultRepository;
    @Mock private MatchAnalysisResultRepository matchAnalysisResultRepository;
    @Mock private InterviewQuestionRepository interviewQuestionRepository;
    @Mock private QuestionSetRepository questionSetRepository;
    @Mock private GuideContextChunkRepository guideContextChunkRepository;
    @Mock private GuideContextResultRepository guideContextResultRepository;
    @Mock private AnalysisCaseRepository analysisCaseRepository;
    @Mock private JobPostingRepository jobPostingRepository;
    @Mock private DocumentExtractionRepository documentExtractionRepository;
    @Mock private UserDocumentRepository userDocumentRepository;
    @Mock private UserRepository userRepository;
    @Mock private FileStorage fileStorage;
    @Mock private com.example.jobpuzzle.ticket.service.TicketService ticketService;

    @InjectMocks
    private UserWithdrawalService userWithdrawalService;

    @Test
    @DisplayName("자료 1건에 파일이 여러 개 있으면 탈퇴 시 전부 삭제된다")
    void deleteAllDataAndUser_deletesEveryFileOfEveryDocument() {
        UserDocument singleFileDocument = UserDocument.builder()
                .documentType(UserDocumentType.JOB_POSTING).sourceType(UserDocumentSourceType.PDF)
                .displayName("공고 PDF").keepOriginal(true).build();
        singleFileDocument.addFile("pdf/posting.pdf", "posting.pdf");

        UserDocument multiFileDocument = UserDocument.builder()
                .documentType(UserDocumentType.JOB_POSTING).sourceType(UserDocumentSourceType.IMAGE)
                .displayName("스크린샷 공고").keepOriginal(true).build();
        multiFileDocument.addFile("image/cap1.png", "cap1.png");
        multiFileDocument.addFile("image/cap2.png", "cap2.png");

        when(userDocumentRepository.findByUser_UserId(1L))
                .thenReturn(List.of(singleFileDocument, multiFileDocument));

        userWithdrawalService.deleteAllDataAndUser(1L);

        verify(fileStorage, times(3)).delete(anyString());
        verify(userDocumentRepository).deleteByUser_UserId(1L);
        verify(userRepository).deleteById(1L);
    }

    @Test
    @DisplayName("탈퇴 시 retrieval과 material chunk를 snapshot보다 먼저 삭제한다")
    void deleteAllDataAndUser_deletesRetrievalAndMaterialChunksBeforeSnapshot() {
        when(requirementRetrievalResultRepository.findRetrievalResultIdsByUserId(1L)).thenReturn(List.of(501L));
        when(userDocumentRepository.findByUser_UserId(1L)).thenReturn(List.of());

        userWithdrawalService.deleteAllDataAndUser(1L);

        var order = inOrder(requirementRetrievalChunkRepository, requirementRetrievalResultRepository,
                analysisMaterialChunkRepository, analysisInputSnapshotSourceRepository, analysisInputSnapshotRepository);
        order.verify(requirementRetrievalChunkRepository).deleteByRetrievalResult_RetrievalResultIdIn(List.of(501L));
        order.verify(requirementRetrievalResultRepository).deleteByUserId(1L);
        order.verify(analysisMaterialChunkRepository).deleteBySnapshot_User_UserId(1L);
        order.verify(analysisInputSnapshotSourceRepository).deleteBySnapshot_User_UserId(1L);
        order.verify(analysisInputSnapshotRepository).deleteByUser_UserId(1L);
    }
}
