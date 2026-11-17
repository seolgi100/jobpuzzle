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
import com.example.jobpuzzle.interview.repository.QuestionSetRepository;
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

        // mock 응답은 candidate evidence가 있으므로 충분한 준비도와 질문 세트를 저장해야 한다.
        assertThat(readinessResultRepository.findBySnapshot_SnapshotId(snapshot.getSnapshotId()).orElseThrow().getStatus())
                .isEqualTo(ReadinessResultStatus.SUFFICIENT);
        assertThat(questionSetRepository.existsBySnapshot_SnapshotIdAndInterviewMode(snapshot.getSnapshotId(),
                com.example.jobpuzzle.interview.entity.InterviewSessionMode.COMPANY_FIT)).isTrue();
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
