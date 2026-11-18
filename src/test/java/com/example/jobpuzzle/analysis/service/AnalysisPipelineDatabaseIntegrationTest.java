package com.example.jobpuzzle.analysis.service;

import com.example.jobpuzzle.ai.log.AiCallLogRepository;
import com.example.jobpuzzle.ai.log.AiCallLogRole;
import com.example.jobpuzzle.ai.log.AiCallLogStatus;
import com.example.jobpuzzle.ai.log.AiExecutionStage;
import com.example.jobpuzzle.ai.client.MockAiClient;
import com.example.jobpuzzle.ai.validation.AiProcessingException;
import com.example.jobpuzzle.ai.prompt.PromptTemplate;
import com.example.jobpuzzle.ai.prompt.PromptTemplateRepository;
import com.example.jobpuzzle.analysis.dto.AnalysisInputSnapshotResponse;
import com.example.jobpuzzle.analysis.entity.AnalysisCase;
import com.example.jobpuzzle.analysis.entity.AnalysisCaseSource;
import com.example.jobpuzzle.analysis.entity.AnalysisCaseStatus;
import com.example.jobpuzzle.analysis.entity.ReadinessResultStatus;
import com.example.jobpuzzle.analysis.rag.entity.RetrievalStatus;
import com.example.jobpuzzle.analysis.rag.repository.RequirementRetrievalResultRepository;
import com.example.jobpuzzle.analysis.rag.search.InMemoryVectorSearchAdapter;
import com.example.jobpuzzle.analysis.repository.AnalysisCaseRepository;
import com.example.jobpuzzle.analysis.repository.AnalysisCaseSourceRepository;
import com.example.jobpuzzle.analysis.repository.ReadinessResultRepository;
import com.example.jobpuzzle.document.entity.DocumentExtraction;
import com.example.jobpuzzle.document.entity.DocumentExtractionStatus;
import com.example.jobpuzzle.document.entity.DocumentVersionStatus;
import com.example.jobpuzzle.document.entity.UserDocument;
import com.example.jobpuzzle.document.entity.UserDocumentSourceType;
import com.example.jobpuzzle.document.entity.UserDocumentType;
import com.example.jobpuzzle.document.repository.DocumentExtractionRepository;
import com.example.jobpuzzle.document.repository.UserDocumentRepository;
import com.example.jobpuzzle.guide.entity.GuideScopeType;
import com.example.jobpuzzle.guide.entity.JobGuideChunk;
import com.example.jobpuzzle.guide.entity.JobGuideDocument;
import com.example.jobpuzzle.guide.entity.JobGuideDocumentSourceType;
import com.example.jobpuzzle.guide.repository.GuideContextResultRepository;
import com.example.jobpuzzle.guide.repository.JobGuideChunkRepository;
import com.example.jobpuzzle.guide.repository.JobGuideDocumentRepository;
import com.example.jobpuzzle.global.error.CustomException;
import com.example.jobpuzzle.global.error.ErrorCode;
import com.example.jobpuzzle.competency.repository.PersonalityQuestionRepository;
import com.example.jobpuzzle.interview.dto.SessionCreateRequest;
import com.example.jobpuzzle.interview.entity.InterviewSessionMode;
import com.example.jobpuzzle.interview.repository.InterviewSessionRepository;
import com.example.jobpuzzle.interview.repository.QuestionSetRepository;
import com.example.jobpuzzle.interview.service.InterviewSessionService;
import com.example.jobpuzzle.interview.service.QuestionGenerationV2Service;
import com.example.jobpuzzle.ticket.entity.TicketUsage;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import com.example.jobpuzzle.jobcategory.entity.JobCategory;
import com.example.jobpuzzle.jobcategory.entity.JobCategoryCareerLevel;
import com.example.jobpuzzle.jobcategory.repository.JobCategoryRepository;
import com.example.jobpuzzle.ticket.service.TicketService;
import com.example.jobpuzzle.user.entity.User;
import com.example.jobpuzzle.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Mock AI와 fake embedding을 사용해 JSON-01부터 JSON-05 결과 저장까지 실제 MariaDB 경계를 검증한다. */
@ExtendWith(SpringExtension.class)
@SpringBootTest(properties = "app.rag.mode=fake")
@ActiveProfiles("db-integration")
@Testcontainers(disabledWithoutDocker = true)
class AnalysisPipelineDatabaseIntegrationTest {

    @Container
    static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.4")
            .withDatabaseName("jobpuzzle_pipeline_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void mariaDbProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MARIADB::getJdbcUrl);
        registry.add("spring.datasource.username", MARIADB::getUsername);
        registry.add("spring.datasource.password", MARIADB::getPassword);
        registry.add("spring.datasource.driver-class-name", MARIADB::getDriverClassName);
    }

    @Autowired private AnalysisCaseService analysisCaseService;
    @Autowired private AnalysisService analysisService;
    @Autowired private UserRepository userRepository;
    @Autowired private JobCategoryRepository jobCategoryRepository;
    @Autowired private AnalysisCaseRepository analysisCaseRepository;
    @Autowired private AnalysisCaseSourceRepository analysisCaseSourceRepository;
    @Autowired private UserDocumentRepository userDocumentRepository;
    @Autowired private DocumentExtractionRepository extractionRepository;
    @Autowired private PromptTemplateRepository promptTemplateRepository;
    @Autowired private JobGuideDocumentRepository guideDocumentRepository;
    @Autowired private JobGuideChunkRepository guideChunkRepository;
    @Autowired private GuideContextResultRepository guideContextResultRepository;
    @Autowired private RequirementRetrievalResultRepository retrievalResultRepository;
    @Autowired private ReadinessResultRepository readinessResultRepository;
    @Autowired private QuestionSetRepository questionSetRepository;
    @Autowired private AiCallLogRepository aiCallLogRepository;
    @Autowired private TicketService ticketService;
    @Autowired private QuestionGenerationV2Service questionGenerationV2Service;
    @Autowired private InterviewSessionService interviewSessionService;
    @Autowired private InterviewSessionRepository interviewSessionRepository;
    @Autowired private PersonalityQuestionRepository personalityQuestionRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private com.example.jobpuzzle.interview.service.InterviewRoundService interviewRoundService;
    @Autowired private com.example.jobpuzzle.evaluation.repository.AnswerEvaluationRepository answerEvaluationRepository;
    @Autowired private com.example.jobpuzzle.evaluation.service.SessionReportV2Service sessionReportV2Service;
    @Autowired private com.example.jobpuzzle.evaluation.service.SessionScoreAggregationService scoreAggregationService;
    @SpyBean private InMemoryVectorSearchAdapter vectorSearchAdapter;
    @SpyBean private MockAiClient mockAiClient;

    @Test
    void completesThePersistedJson01ToJson05PipelineWithAnActiveGuide() throws IOException {
        Fixture fixture = fixture();

        // 입력 확정이 만든 snapshot과 material chunk를 사용해 실제 분석 파이프라인을 실행한다.
        AnalysisInputSnapshotResponse snapshot = analysisCaseService.confirmInput(fixture.user().getUserId(), fixture.analysisCase().getAnalysisCaseId());
        analysisService.runCustomizedAnalysis(fixture.user().getUserId(), fixture.analysisCase().getAnalysisCaseId());

        // JSON-01·02·05 로그와 case 완료 상태가 동일한 DB 흐름에서 확정됐는지 확인한다.
        assertThat(analysisCaseRepository.findById(fixture.analysisCase().getAnalysisCaseId()).orElseThrow().getStatus())
                .isEqualTo(AnalysisCaseStatus.COMPLETED);
        var snapshotLogs = aiCallLogRepository.findAll().stream()
                .filter(log -> log.getInputReferenceId().equals(String.valueOf(snapshot.getSnapshotId())))
                .toList();
        assertThat(snapshotLogs).extracting(log -> log.getExecutionStage()).containsExactlyInAnyOrder(
                AiExecutionStage.JOB_POSTING_ANALYSIS,
                AiExecutionStage.CANDIDATE_MATERIAL_ANALYSIS,
                AiExecutionStage.CANDIDATE_MATERIAL_ANALYSIS,
                AiExecutionStage.CUSTOMIZED_SYNTHESIS);
        // JSON-02 partition 호출과 최종 DTO 호환용 aggregate root는 서로 다른 역할의 로그다.
        assertThat(snapshotLogs).filteredOn(log -> log.getExecutionStage() == AiExecutionStage.CANDIDATE_MATERIAL_ANALYSIS)
                .extracting(log -> log.getCallRole())
                .containsExactlyInAnyOrder(AiCallLogRole.PROVIDER_CALL, AiCallLogRole.AGGREGATE_RESULT);
        assertThat(snapshotLogs)
                .allSatisfy(log -> assertThat(log.getStatus()).isEqualTo(AiCallLogStatus.SUCCEEDED));

        // CATEGORY ACTIVE 가이드가 JSON-04 context로 고정되고 candidate retrieval이 저장돼야 한다.
        assertThat(guideContextResultRepository.findAll()).hasSize(1)
                .allSatisfy(context -> {
                    assertThat(context.getGuideVersion()).isEqualTo(fixture.guide().getVersion());
                    assertThat(context.getApplicableScope()).isEqualTo(fixture.guide().getApplicableScope());
                });
        assertThat(retrievalResultRepository.findAll()).isNotEmpty()
                .allSatisfy(result -> assertThat(result.getStatus()).isIn(RetrievalStatus.COMPLETED, RetrievalStatus.EMPTY));

        // mock 응답은 candidate evidence가 있으므로 충분한 준비도를 저장한다.
        // v2: 분석은 자료 기반 면접이 가능하다고만 표시하고 질문 세트는 만들지 않는다(면접 생성 때 만든다).
        var readiness = readinessResultRepository.findBySnapshot_SnapshotId(snapshot.getSnapshotId()).orElseThrow();
        assertThat(readiness.getStatus()).isEqualTo(ReadinessResultStatus.SUFFICIENT);
        assertThat(readiness.isCanGenerateQuestions()).isTrue();
        assertThat(questionSetRepository.existsBySnapshot_SnapshotIdAndInterviewMode(snapshot.getSnapshotId(),
                com.example.jobpuzzle.interview.entity.InterviewSessionMode.COMPANY_FIT)).isFalse();
        // v2 분석은 1장만 쓴다 (충전 10장 - 1장).
        assertThat(ticketService.getBalance(fixture.user().getUserId()).balance()).isEqualTo(9);
    }

    @Test
    void failsTheCaseBeforeRetrievalAndJson05WhenNoActiveGuideExists() throws IOException {
        Fixture fixture = fixture(false);
        AnalysisInputSnapshotResponse snapshot = analysisCaseService.confirmInput(fixture.user().getUserId(), fixture.analysisCase().getAnalysisCaseId());

        assertThatThrownBy(() -> analysisService.runCustomizedAnalysis(fixture.user().getUserId(), fixture.analysisCase().getAnalysisCaseId()))
                .isInstanceOf(CustomException.class)
                .extracting(error -> ((CustomException) error).getErrorCode()).isEqualTo(ErrorCode.GUIDE_ACTIVE_NOT_FOUND);

        assertThat(analysisCaseRepository.findById(fixture.analysisCase().getAnalysisCaseId()).orElseThrow().getStatus())
                .isEqualTo(AnalysisCaseStatus.FAILED);
        assertThat(guideContextResultRepository.findByPurposeAndInputReferenceTypeAndInputReferenceId(
                com.example.jobpuzzle.guide.entity.GuideContextPurpose.CUSTOMIZED_SYNTHESIS,
                com.example.jobpuzzle.guide.entity.GuideContextInputReferenceType.ANALYSIS_SNAPSHOT,
                String.valueOf(snapshot.getSnapshotId()))).isEmpty();
        assertThat(retrievalResultRepository.findBySnapshot_SnapshotIdOrderByRetrievalResultIdAsc(snapshot.getSnapshotId())).isEmpty();
        var snapshotLogs = aiCallLogRepository.findAll().stream()
                .filter(log -> log.getInputReferenceId().equals(String.valueOf(snapshot.getSnapshotId())))
                .toList();
        assertThat(snapshotLogs).extracting(log -> log.getExecutionStage())
                .containsExactlyInAnyOrder(AiExecutionStage.JOB_POSTING_ANALYSIS,
                        AiExecutionStage.CANDIDATE_MATERIAL_ANALYSIS, AiExecutionStage.CANDIDATE_MATERIAL_ANALYSIS)
                .doesNotContain(AiExecutionStage.CUSTOMIZED_SYNTHESIS);
        assertThat(snapshotLogs).filteredOn(log -> log.getExecutionStage() == AiExecutionStage.CANDIDATE_MATERIAL_ANALYSIS)
                .extracting(log -> log.getCallRole())
                .containsExactlyInAnyOrder(AiCallLogRole.PROVIDER_CALL, AiCallLogRole.AGGREGATE_RESULT);
    }

    @Test
    void preservesRetrievalFailureAndMarksTheCaseFailed() throws IOException {
        Fixture fixture = fixture();
        AnalysisInputSnapshotResponse snapshot = analysisCaseService.confirmInput(fixture.user().getUserId(), fixture.analysisCase().getAnalysisCaseId());
        doThrow(new CustomException(ErrorCode.JSON05_RESULT_INTEGRITY_CONFLICT))
                .when(vectorSearchAdapter).search(any());

        assertThatThrownBy(() -> analysisService.runCustomizedAnalysis(fixture.user().getUserId(), fixture.analysisCase().getAnalysisCaseId()))
                .isInstanceOf(CustomException.class)
                .extracting(error -> ((CustomException) error).getErrorCode()).isEqualTo(ErrorCode.JSON05_RESULT_INTEGRITY_CONFLICT);

        assertThat(analysisCaseRepository.findById(fixture.analysisCase().getAnalysisCaseId()).orElseThrow().getStatus())
                .isEqualTo(AnalysisCaseStatus.FAILED);
        assertThat(retrievalResultRepository.findBySnapshot_SnapshotIdOrderByRetrievalResultIdAsc(snapshot.getSnapshotId())).isEmpty();
        assertThat(aiCallLogRepository.findAll()).filteredOn(log -> log.getInputReferenceId().equals(String.valueOf(snapshot.getSnapshotId())))
                .extracting(log -> log.getExecutionStage())
                .doesNotContain(AiExecutionStage.CUSTOMIZED_SYNTHESIS);
    }

    @Test
    void completesWithCandidateLackWhenEveryRetrievalIsEmpty() throws IOException {
        Fixture fixture = fixture();
        AnalysisInputSnapshotResponse snapshot = analysisCaseService.confirmInput(fixture.user().getUserId(), fixture.analysisCase().getAnalysisCaseId());
        doReturn(List.of()).when(vectorSearchAdapter).search(any());

        analysisService.runCustomizedAnalysis(fixture.user().getUserId(), fixture.analysisCase().getAnalysisCaseId());

        assertThat(analysisCaseRepository.findById(fixture.analysisCase().getAnalysisCaseId()).orElseThrow().getStatus())
                .isEqualTo(AnalysisCaseStatus.COMPLETED);
        assertThat(retrievalResultRepository.findBySnapshot_SnapshotIdOrderByRetrievalResultIdAsc(snapshot.getSnapshotId())).isNotEmpty()
                .allSatisfy(result -> assertThat(result.getStatus()).isEqualTo(RetrievalStatus.EMPTY));
        assertThat(readinessResultRepository.findBySnapshot_SnapshotId(snapshot.getSnapshotId()).orElseThrow().getStatus())
                .isEqualTo(ReadinessResultStatus.CANDIDATE_LACK);
    }

    @Test
    void retriesAfterJson05FailureAndReusesCompletedInitialStagesGuideAndRetrievals() throws IOException {
        Fixture fixture = fixture();
        AnalysisInputSnapshotResponse snapshot = analysisCaseService.confirmInput(fixture.user().getUserId(), fixture.analysisCase().getAnalysisCaseId());
        // 검증 실패는 같은 입력 재시도가 막혀 있으므로(retryBlocked) 재시도 가능한 공급자 오류로 실패시킨다.
        doThrow(new AiProcessingException(com.example.jobpuzzle.ai.log.AiCallLogErrorType.PROVIDER_ERROR, "forced JSON-05 failure"))
                .doCallRealMethod().when(mockAiClient).generateCustomizedAnalysisV18(any(), any());

        assertThatThrownBy(() -> analysisService.runCustomizedAnalysis(fixture.user().getUserId(), fixture.analysisCase().getAnalysisCaseId()))
                .isInstanceOf(CustomException.class)
                .extracting(error -> ((CustomException) error).getErrorCode()).isEqualTo(ErrorCode.AI_RESPONSE_INVALID);
        assertThat(analysisCaseRepository.findById(fixture.analysisCase().getAnalysisCaseId()).orElseThrow().getStatus())
                .isEqualTo(AnalysisCaseStatus.FAILED);
        assertThat(retrievalResultRepository.findBySnapshot_SnapshotIdOrderByRetrievalResultIdAsc(snapshot.getSnapshotId())).hasSize(2);
        clearInvocations(vectorSearchAdapter);

        analysisService.runCustomizedAnalysis(fixture.user().getUserId(), fixture.analysisCase().getAnalysisCaseId());

        assertThat(analysisCaseRepository.findById(fixture.analysisCase().getAnalysisCaseId()).orElseThrow().getStatus())
                .isEqualTo(AnalysisCaseStatus.COMPLETED);
        assertThat(guideContextResultRepository.findByPurposeAndInputReferenceTypeAndInputReferenceId(
                com.example.jobpuzzle.guide.entity.GuideContextPurpose.CUSTOMIZED_SYNTHESIS,
                com.example.jobpuzzle.guide.entity.GuideContextInputReferenceType.ANALYSIS_SNAPSHOT,
                String.valueOf(snapshot.getSnapshotId()))).isPresent();
        assertThat(retrievalResultRepository.findBySnapshot_SnapshotIdOrderByRetrievalResultIdAsc(snapshot.getSnapshotId())).hasSize(2);
        var snapshotLogs = aiCallLogRepository.findAll().stream()
                .filter(log -> log.getInputReferenceId().equals(String.valueOf(snapshot.getSnapshotId())))
                .toList();
        assertThat(snapshotLogs).extracting(log -> log.getExecutionStage())
                .containsExactlyInAnyOrder(AiExecutionStage.JOB_POSTING_ANALYSIS,
                        AiExecutionStage.CANDIDATE_MATERIAL_ANALYSIS, AiExecutionStage.CANDIDATE_MATERIAL_ANALYSIS,
                        AiExecutionStage.CUSTOMIZED_SYNTHESIS, AiExecutionStage.CUSTOMIZED_SYNTHESIS);
        // 재실행은 성공한 JSON-02 partition/root를 재사용하므로 로그가 추가되지 않아야 한다.
        assertThat(snapshotLogs).filteredOn(log -> log.getExecutionStage() == AiExecutionStage.CANDIDATE_MATERIAL_ANALYSIS)
                .extracting(log -> log.getCallRole())
                .containsExactlyInAnyOrder(AiCallLogRole.PROVIDER_CALL, AiCallLogRole.AGGREGATE_RESULT);
        verify(vectorSearchAdapter, never()).search(any());
    }

    @Test
    void generatesKnowledgeAndMaterialQuestionsFromTheChosenAnalysis() throws IOException {
        Fixture fixture = fixture();
        Long userId = fixture.user().getUserId();
        Long caseId = fixture.analysisCase().getAnalysisCaseId();
        analysisCaseService.confirmInput(userId, caseId);
        analysisService.runCustomizedAnalysis(userId, caseId);

        // 분석을 마치면 기초 지식·자료 기반을 고를 수 있고, 인성 질문이 없는 테스트 직무라 인성은 막힌다.
        assertThat(questionGenerationV2Service.availability(userId).getModes())
                .extracting(m -> m.getMode() + ":" + m.isAvailable())
                .containsExactly("KNOWLEDGE:true", "MATERIAL:true", "PERSONALITY:false");

        var knowledge = questionGenerationV2Service.generate(userId, InterviewSessionMode.KNOWLEDGE, caseId);
        var material = questionGenerationV2Service.generate(userId, InterviewSessionMode.MATERIAL, caseId);
        // 같은 분석 결과·같은 모드로 다시 만들 수 있다 (예전 (snapshot, mode) 유일 조건 제거)
        var knowledgeAgain = questionGenerationV2Service.generate(userId, InterviewSessionMode.KNOWLEDGE, caseId);

        assertThat(knowledge.getMode()).isEqualTo("KNOWLEDGE");
        assertThat(material.getMode()).isEqualTo("MATERIAL");
        assertThat(knowledgeAgain.getQuestionSetId()).isNotEqualTo(knowledge.getQuestionSetId());
        assertThat(List.of(knowledge, material)).allSatisfy(set -> assertThat(set.getQuestions()).hasSize(5)
                .allSatisfy(q -> {
                    assertThat(q.getIntent()).isNotBlank();
                    assertThat(q.getCompetencyTags()).hasSizeBetween(1, 2);
                }));

        // 그 묶음으로 면접을 만들면 고른 분석 결과가 세션에 고정되고, 평가 입력에 쓸 태그도 질문에 남는다.
        SessionCreateRequest request = new SessionCreateRequest();
        ReflectionTestUtils.setField(request, "questionSetId", material.getQuestionSetId());
        ReflectionTestUtils.setField(request, "selectedQuestionIds",
                material.getQuestions().stream().map(q -> q.getQuestionId()).toList());
        Long sessionId = interviewSessionService.createSession(userId, request).getSessionId();
        transactionTemplate.executeWithoutResult(status -> {
            var session = interviewSessionRepository.findById(sessionId).orElseThrow();
            assertThat(session.getMode()).isEqualTo(InterviewSessionMode.MATERIAL);
            assertThat(session.getJobPostingAnalysis()).isNotNull();
            assertThat(session.getCandidateAnalysis()).isNotNull();
        });

        // 자료 기반 면접을 끝내고 질문을 다시 생성하면 2장을 쓰고, 앞 회차 질문을 피하라고 AI에 넘긴다
        answerFirstQuestion(userId, sessionId);
        interviewSessionService.completeSession(userId, sessionId);
        clearInvocations(mockAiClient);
        var next = interviewRoundService.regenerate(userId, sessionId);
        assertThat(next.getRound()).isEqualTo(2);
        assertThat(next.getMode()).isEqualTo(InterviewSessionMode.MATERIAL);
        assertThat(ticketService.getBalance(userId).balance()).isEqualTo(7);
        var prompt = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(mockAiClient).generateQuestionsV2(prompt.capture());
        assertThat(prompt.getValue()).contains("\"avoidQuestions\"").contains(material.getQuestions().get(0).getQuestion());
    }

    @Test
    void refundsTicketsAndKeepsTheFailedLogWhenQuestionGenerationKeepsFailing() throws IOException {
        Fixture fixture = fixture();
        Long userId = fixture.user().getUserId();
        Long caseId = fixture.analysisCase().getAnalysisCaseId();
        analysisCaseService.confirmInput(userId, caseId);
        analysisService.runCustomizedAnalysis(userId, caseId);
        long setsBefore = questionSetRepository.count();
        // 목록에 없는 역량 태그 → 검증 실패 → 첫 호출 + 재호출 2번 모두 실패
        doReturn("{\"questions\":[{\"question\":\"q\",\"intent\":\"i\",\"competencyTags\":[\"NOT_A_TAG\"],\"relatedRequirementId\":null}]}")
                .when(mockAiClient).generateQuestionsV2(any());

        // 컨트롤러와 같은 방식으로 티켓을 쓴다 (분석 1장 뒤 잔액 9장, 자료 기반 3장)
        assertThatThrownBy(() -> ticketService.spend(userId, TicketUsage.MATERIAL, ticketService.cost(TicketUsage.MATERIAL),
                () -> questionGenerationV2Service.generate(userId, InterviewSessionMode.MATERIAL, caseId)))
                .isInstanceOf(CustomException.class)
                .extracting(error -> ((CustomException) error).getErrorCode()).isEqualTo(ErrorCode.AI_RESPONSE_INVALID);

        verify(mockAiClient, org.mockito.Mockito.times(3)).generateQuestionsV2(any());
        assertThat(ticketService.getBalance(userId).balance()).isEqualTo(9);
        assertThat(questionSetRepository.count()).isEqualTo(setsBefore);
        assertThat(aiCallLogRepository.findAll()).filteredOn(log -> log.getExecutionStage() == AiExecutionStage.QUESTION_GENERATION)
                .filteredOn(log -> log.getStatus() == AiCallLogStatus.FAILED).isNotEmpty();
    }

    @Test
    void picksPersonalityQuestionsFromTheMembersJobWithoutAi() {
        JobCategory backendNew = jobCategoryRepository.findAll().stream()
                .filter(c -> c.getMainCategory().equals("IT·개발") && c.getSubCategory().equals("백엔드 개발")
                        && c.getCareerLevel() == JobCategoryCareerLevel.NEW)
                .findFirst().orElseThrow();
        String suffix = java.util.UUID.randomUUID().toString();
        User user = userRepository.save(User.createLocalUser("personality-" + suffix, "encoded", suffix + "@example.test",
                "Personality", backendNew));

        // 분석 없이도 인성은 고를 수 있다
        assertThat(questionGenerationV2Service.availability(user.getUserId()).getModes())
                .extracting(m -> m.getMode() + ":" + m.isAvailable())
                .containsExactly("KNOWLEDGE:false", "MATERIAL:false", "PERSONALITY:true");

        var set = questionGenerationV2Service.generatePersonality(user.getUserId());

        assertThat(set.getMode()).isEqualTo("PERSONALITY");
        assertThat(set.getGenerationSource()).isEqualTo("ADMIN_POOL");
        assertThat(set.getQuestions()).hasSize(5).allSatisfy(q -> assertThat(q.getCompetencyTags()).hasSizeBetween(1, 2));
        verify(mockAiClient, never()).generateQuestionsV2(any());
        // 신입 회원에게는 경력 전용 질문이 나오지 않는다
        var experiencedOnly = personalityQuestionRepository
                .findByMainCategoryAndSubCategoryOrderByPersonalityQuestionIdAsc("IT·개발", "백엔드 개발").stream()
                .filter(q -> q.getCareerLevel() == JobCategoryCareerLevel.EXPERIENCED)
                .map(q -> q.getQuestion()).toList();
        assertThat(set.getQuestions()).noneMatch(q -> experiencedOnly.contains(q.getQuestion()));
    }

    @Test
    void runsTheNextRoundWithTheSameOrNewQuestionsAndComparesSameQuestions() {
        JobCategory backendNew = jobCategoryRepository.findAll().stream()
                .filter(c -> c.getMainCategory().equals("IT·개발") && c.getSubCategory().equals("백엔드 개발")
                        && c.getCareerLevel() == JobCategoryCareerLevel.NEW)
                .findFirst().orElseThrow();
        String suffix = java.util.UUID.randomUUID().toString();
        User user = userRepository.save(User.createLocalUser("round-" + suffix, "encoded", suffix + "@example.test",
                "Round", backendNew));
        Long userId = user.getUserId();
        ticketService.charge(userId, 10, "test-" + suffix);

        // 1회차: 인성 면접 질문 1개에 답하고 끝낸다
        var set = questionGenerationV2Service.generatePersonality(userId);
        Long first = createSessionWithAll(userId, set);
        answerFirstQuestion(userId, first);
        interviewSessionService.completeSession(userId, first);

        // 다시 진행: 같은 질문, 2회차, 티켓 1장
        var retry = interviewRoundService.retry(userId, first);
        assertThat(retry.getRound()).isEqualTo(2);
        assertThat(retry.getPreviousSessionId()).isEqualTo(first);
        assertThat(ticketService.getBalance(userId).balance()).isEqualTo(9);
        assertThat(questionTexts(userId, retry.getSessionId())).isEqualTo(questionTexts(userId, first));
        // 앞 회차에서는 다음 회차를 또 만들 수 없다 (회차는 한 줄)
        assertThatThrownBy(() -> interviewRoundService.retry(userId, first))
                .extracting(error -> ((CustomException) error).getErrorCode()).isEqualTo(ErrorCode.ROUND_ALREADY_EXISTS);
        // 같은 질문에 다시 답하면 이전 회차와 비교한 결과가 저장된다
        answerFirstQuestion(userId, retry.getSessionId());
        transactionTemplate.executeWithoutResult(status -> assertThat(answerEvaluationRepository.findAll().stream()
                .filter(e -> e.getSessionQuestion().getSession().getSessionId().equals(retry.getSessionId()))
                .findFirst().orElseThrow().getComparison()).containsKeys("improved", "toImprove"));
        interviewSessionService.completeSession(userId, retry.getSessionId());

        // 최종 보고서 v2 (AI 없음): 100점 척도, 6개 항목, 앞 회차 총점, 같은 질문 비교
        var firstReport = sessionReportV2Service.report(userId, first);
        var report = sessionReportV2Service.report(userId, retry.getSessionId());
        assertThat(report.round()).isEqualTo(2);
        assertThat(report.totalScore()).isBetween(20, 100);
        assertThat(report.previousTotalScore()).isEqualTo(firstReport.totalScore());
        assertThat(report.criteria()).hasSize(6).allSatisfy(c -> assertThat(c.score()).isBetween(20, 100));
        assertThat(report.tally().answered()).isEqualTo(1);
        assertThat(report.questions().get(0).status()).isEqualTo("EVALUATED");
        assertThat(report.questions().get(0).items()).hasSize(6);
        assertThat(report.questions().get(0).comparison()).isNotNull();
        assertThat(report.questions().get(1).status()).isEqualTo("SKIPPED");
        assertThat(firstReport.questions().get(0).comparison()).isNull();
        // 홈·대시보드·리포트 목록이 쓰는 /score도 같은 계산
        assertThat(scoreAggregationService.aggregate(userId, retry.getSessionId()).getOverallScore()).isEqualTo(report.totalScore());

        // 질문 다시 생성: 인성은 티켓 0장, 앞 회차와 다른 질문
        var regenerated = interviewRoundService.regenerate(userId, retry.getSessionId());
        assertThat(regenerated.getRound()).isEqualTo(3);
        assertThat(ticketService.getBalance(userId).balance()).isEqualTo(9);
        assertThat(questionTexts(userId, regenerated.getSessionId()))
                .doesNotContainAnyElementsOf(questionTexts(userId, retry.getSessionId()));
        // 끝나지 않은 면접은 다음 회차를 만들 수 없다
        assertThatThrownBy(() -> interviewRoundService.retry(userId, regenerated.getSessionId()))
                .extracting(error -> ((CustomException) error).getErrorCode()).isEqualTo(ErrorCode.ROUND_NOT_ALLOWED);
    }

    private Long createSessionWithAll(Long userId, com.example.jobpuzzle.analysis.dto.QuestionSetResponse set) {
        SessionCreateRequest request = new SessionCreateRequest();
        ReflectionTestUtils.setField(request, "questionSetId", set.getQuestionSetId());
        ReflectionTestUtils.setField(request, "selectedQuestionIds", set.getQuestions().stream().map(q -> q.getQuestionId()).toList());
        return interviewSessionService.createSession(userId, request).getSessionId();
    }

    private void answerFirstQuestion(Long userId, Long sessionId) {
        var question = interviewSessionService.getSessionQuestions(userId, sessionId).get(0);
        var answer = new com.example.jobpuzzle.interview.dto.AnswerSubmitRequest();
        ReflectionTestUtils.setField(answer, "messageText",
                "팀 프로젝트에서 익숙한 방식과 새 기술을 지연 시간과 부하 기준으로 비교해 골랐고, 시제품으로 측정해 근거를 남겼습니다.");
        ReflectionTestUtils.setField(answer, "answerType", com.example.jobpuzzle.interview.entity.AnswerType.ORIGINAL_ANSWER);
        assertThat(interviewSessionService.submitAnswer(userId, question.getSessionQuestionId(), answer).isEvaluationFailed()).isFalse();
    }

    private List<String> questionTexts(Long userId, Long sessionId) {
        return interviewSessionService.getSessionQuestions(userId, sessionId).stream().map(q -> q.getQuestionText()).toList();
    }

    // 전체 실행에 필요한 확정 문서, 활성 가이드, 활성 JSON-01·02·05 템플릿을 실제 DB에 준비한다.
    private Fixture fixture() throws IOException {
        return fixture(true);
    }

    private Fixture fixture(boolean withActiveGuide) throws IOException {
        String suffix = java.util.UUID.randomUUID().toString();
        JobCategory category = jobCategoryRepository.save(JobCategory.builder()
                .mainCategory("TEST").subCategory("BACKEND-" + suffix).careerLevel(JobCategoryCareerLevel.NEW).build());
        User user = userRepository.save(User.createLocalUser("pipeline-" + suffix, "encoded", suffix + "@example.test", "Pipeline", category));
        ticketService.charge(user.getUserId(), 10, "test-" + suffix);
        AnalysisCase analysisCase = analysisCaseRepository.save(AnalysisCase.builder().user(user).jobCategory(category).build());
        saveSource(user, analysisCase, UserDocumentType.JOB_POSTING, "Java와 Spring Boot 기반 백엔드 개발자를 찾습니다.");
        saveSource(user, analysisCase, UserDocumentType.RESUME, "Java와 Spring Boot로 REST API를 구현한 백엔드 개발 경험이 있습니다.");
        JobGuideDocument guide = withActiveGuide ? saveActiveCategoryGuide(user, category, suffix) : null;
        savePrompt("PT-JOB-" + suffix, "JSON-01", "prompts/json-01-v1.3.txt", "v1.0");
        savePrompt("PT-CAND-" + suffix, "JSON-02", "prompts/json-02-v1.6.txt", "v1.0");
        savePrompt("PT-SYN-" + suffix, "JSON-05", "prompts/json-05-v1.10.txt", "v1.10");
        return new Fixture(user, analysisCase, guide);
    }

    // CONFIRMED extraction을 case source로 연결해 confirmInput의 실제 검증 경로를 충족한다.
    private void saveSource(User user, AnalysisCase analysisCase, UserDocumentType type, String content) {
        UserDocument document = userDocumentRepository.save(UserDocument.builder().user(user).documentType(type)
                .sourceType(UserDocumentSourceType.TEXT).displayName(type.name() + " 자료").keepOriginal(false).build());
        DocumentExtraction extraction = extractionRepository.save(DocumentExtraction.builder().document(document)
                .extractionStatus(DocumentExtractionStatus.SUCCESS).versionStatus(DocumentVersionStatus.CONFIRMED)
                .content("[1페이지]\n" + content).pageCount(1).build());
        analysisCaseSourceRepository.save(AnalysisCaseSource.builder().analysisCase(analysisCase)
                .extraction(extraction).documentType(type).build());
    }

    // 현재 case와 정확히 일치하는 ACTIVE CATEGORY 가이드 및 최소 한 개의 context chunk를 생성한다.
    private JobGuideDocument saveActiveCategoryGuide(User user, JobCategory category, String suffix) {
        JobGuideDocument guide = JobGuideDocument.builder().guideCode("GUIDE-" + suffix).scopeType(GuideScopeType.CATEGORY)
                .jobCategory(category).title("Backend guide").sourceType(JobGuideDocumentSourceType.DIRECT_INPUT)
                .version("v1.0").createdBy(user).applicableScope("백엔드 개발")
                .evaluationFocus(List.of("요구사항 연결")).evidenceRules(List.of("근거 확인"))
                .questionDirection(List.of("구체 경험 확인")).avoidQuestions(List.of("추상 질문")).build();
        guide.activate();
        guide = guideDocumentRepository.saveAndFlush(guide);
        guideChunkRepository.saveAndFlush(JobGuideChunk.builder().guide(guide).chunkIndex(0).title("평가 기준")
                .content("Java와 Spring Boot 경험의 구체적 근거를 평가한다.").contentSummary("백엔드 평가 기준").build());
        return guide;
    }

    // 배포 정본 프롬프트를 테스트 DB에도 그대로 넣어 renderer와 MockAiClient 태그 계약을 함께 검증한다.
    private void savePrompt(String code, String targetJson, String resourcePath, String version) throws IOException {
        String template = new ClassPathResource(resourcePath).getContentAsString(StandardCharsets.UTF_8);
        promptTemplateRepository.saveAndFlush(PromptTemplate.builder().promptCode(code).name(targetJson + " pipeline test")
                .version(version).targetJson(targetJson).templateText(template).forbiddenRules("JSON 이외의 응답 금지").isActive(true).build());
    }

    private record Fixture(User user, AnalysisCase analysisCase, JobGuideDocument guide) { }
}
