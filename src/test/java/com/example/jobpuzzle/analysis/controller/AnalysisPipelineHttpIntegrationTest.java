package com.example.jobpuzzle.analysis.controller;

import com.example.jobpuzzle.ai.prompt.PromptTemplate;
import com.example.jobpuzzle.ai.prompt.PromptTemplateRepository;
import com.example.jobpuzzle.analysis.entity.AnalysisCase;
import com.example.jobpuzzle.analysis.entity.AnalysisCaseSource;
import com.example.jobpuzzle.analysis.repository.AnalysisCaseRepository;
import com.example.jobpuzzle.analysis.repository.AnalysisCaseSourceRepository;
import com.example.jobpuzzle.analysis.service.AnalysisCaseService;
import com.example.jobpuzzle.document.entity.DocumentExtraction;
import com.example.jobpuzzle.document.entity.DocumentExtractionStatus;
import com.example.jobpuzzle.document.entity.DocumentVersionStatus;
import com.example.jobpuzzle.document.entity.UserDocument;
import com.example.jobpuzzle.document.entity.UserDocumentSourceType;
import com.example.jobpuzzle.document.entity.UserDocumentType;
import com.example.jobpuzzle.document.repository.DocumentExtractionRepository;
import com.example.jobpuzzle.document.repository.UserDocumentRepository;
import com.example.jobpuzzle.global.error.ErrorCode;
import com.example.jobpuzzle.global.security.CustomUserDetails;
import com.example.jobpuzzle.guide.entity.GuideScopeType;
import com.example.jobpuzzle.guide.entity.JobGuideChunk;
import com.example.jobpuzzle.guide.entity.JobGuideDocument;
import com.example.jobpuzzle.guide.entity.JobGuideDocumentSourceType;
import com.example.jobpuzzle.guide.repository.JobGuideChunkRepository;
import com.example.jobpuzzle.guide.repository.JobGuideDocumentRepository;
import com.example.jobpuzzle.jobcategory.entity.JobCategory;
import com.example.jobpuzzle.jobcategory.entity.JobCategoryCareerLevel;
import com.example.jobpuzzle.jobcategory.repository.JobCategoryRepository;
import com.example.jobpuzzle.ticket.service.TicketService;
import com.example.jobpuzzle.user.entity.User;
import com.example.jobpuzzle.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 실제 보안 필터와 Controller를 거쳐 Mock 분석 파이프라인의 HTTP 계약을 검증한다. */
@SpringBootTest(properties = "app.rag.mode=fake")
@AutoConfigureMockMvc
@ActiveProfiles("db-integration")
@Testcontainers(disabledWithoutDocker = true)
class AnalysisPipelineHttpIntegrationTest {

    @Container
    static final MariaDBContainer<?> MARIADB = new MariaDBContainer<>("mariadb:11.4")
            .withDatabaseName("jobpuzzle_http_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void mariaDbProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MARIADB::getJdbcUrl);
        registry.add("spring.datasource.username", MARIADB::getUsername);
        registry.add("spring.datasource.password", MARIADB::getPassword);
        registry.add("spring.datasource.driver-class-name", MARIADB::getDriverClassName);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private JobCategoryRepository jobCategoryRepository;
    @Autowired private AnalysisCaseRepository analysisCaseRepository;
    @Autowired private AnalysisCaseSourceRepository analysisCaseSourceRepository;
    @Autowired private AnalysisCaseService analysisCaseService;
    @Autowired private UserDocumentRepository userDocumentRepository;
    @Autowired private DocumentExtractionRepository extractionRepository;
    @Autowired private PromptTemplateRepository promptTemplateRepository;
    @Autowired private JobGuideDocumentRepository guideDocumentRepository;
    @Autowired private JobGuideChunkRepository guideChunkRepository;
    @Autowired private TicketService ticketService;

    @Test
    void runsThePipelineAndExposesCompletedStatusAndResultOverHttp() throws Exception {
        Fixture fixture = fixture(true);
        confirm(fixture);

        // /run은 분석을 백그라운드에 넘기고 바로 202(ANALYZING)로 응답한다.
        mockMvc.perform(post("/analysis/cases/{caseId}/run", fixture.analysisCase().getAnalysisCaseId())
                        .with(authenticated(fixture.user())))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("ANALYZING"));
        waitUntilAnalysisFinishes(fixture);

        mockMvc.perform(get("/analysis/cases/{caseId}/status", fixture.analysisCase().getAnalysisCaseId())
                        .with(authenticated(fixture.user())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.analysisCaseStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.data.canGenerateQuestions").value(true));

        // v2: 분석은 질문 세트를 만들지 않는다. 자료 기반 질문은 면접 생성 때 만든다(/question-sets).
        mockMvc.perform(get("/analysis/cases/{caseId}/result", fixture.analysisCase().getAnalysisCaseId())
                        .with(authenticated(fixture.user())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.readiness.canGenerateQuestions").value(true));
    }

    @Test
    void returnsGuideMissingOverHttpAndKeepsTheCaseFailedForStatusLookup() throws Exception {
        Fixture fixture = fixture(false);
        confirm(fixture);

        mockMvc.perform(post("/analysis/cases/{caseId}/run", fixture.analysisCase().getAnalysisCaseId())
                        .with(authenticated(fixture.user())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(ErrorCode.GUIDE_ACTIVE_NOT_FOUND.getCode()))
                .andExpect(jsonPath("$.message").value(ErrorCode.GUIDE_ACTIVE_NOT_FOUND.getMessage()));

        mockMvc.perform(get("/analysis/cases/{caseId}/status", fixture.analysisCase().getAnalysisCaseId())
                        .with(authenticated(fixture.user())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.analysisCaseStatus").value("FAILED"));
    }

    private void waitUntilAnalysisFinishes(Fixture fixture) throws Exception {
        // 백그라운드 분석이 끝날 때까지 화면과 같은 방식으로 /status를 확인한다.
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            String body = mockMvc.perform(get("/analysis/cases/{caseId}/status", fixture.analysisCase().getAnalysisCaseId())
                    .with(authenticated(fixture.user()))).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            if (!body.contains("\"analysisCaseStatus\":\"ANALYZING\"")) return;
            Thread.sleep(200);
        }
        throw new AssertionError("analysis did not finish within 60 seconds");
    }

    private void confirm(Fixture fixture) throws Exception {
        // 입력 확정 API는 별도 Controller 테스트 범위이므로, HTTP 파이프라인 시작 상태만 서비스로 준비한다.
        analysisCaseService.confirmInput(fixture.user().getUserId(), fixture.analysisCase().getAnalysisCaseId());
    }

    private Fixture fixture(boolean withGuide) throws IOException {
        String suffix = java.util.UUID.randomUUID().toString();
        JobCategory category = jobCategoryRepository.save(JobCategory.builder()
                .mainCategory("HTTP").subCategory("BACKEND-" + suffix).careerLevel(JobCategoryCareerLevel.NEW).build());
        User user = userRepository.save(User.createLocalUser("http-" + suffix, "encoded", suffix + "@example.test", "HTTP", category));
        ticketService.charge(user.getUserId(), 10, "test-" + suffix);
        AnalysisCase analysisCase = analysisCaseRepository.save(AnalysisCase.builder().user(user).jobCategory(category).build());
        saveSource(user, analysisCase, UserDocumentType.JOB_POSTING, "Java와 Spring Boot 기반 백엔드 개발자를 찾습니다.");
        saveSource(user, analysisCase, UserDocumentType.RESUME, "Java와 Spring Boot로 REST API를 구현한 백엔드 개발 경험이 있습니다.");
        if (withGuide) saveActiveCategoryGuide(user, category, suffix);
        savePrompt("PT-JOB-" + suffix, "JSON-01", "prompts/json-01-v1.3.txt", "v1.0");
        savePrompt("PT-CAND-" + suffix, "JSON-02", "prompts/json-02-v1.6.txt", "v1.0");
        savePrompt("PT-SYN-" + suffix, "JSON-05", "prompts/json-05-v1.10.txt", "v1.10");
        return new Fixture(user, analysisCase);
    }

    private void saveSource(User user, AnalysisCase analysisCase, UserDocumentType type, String content) {
        UserDocument document = userDocumentRepository.save(UserDocument.builder().user(user).documentType(type)
                .sourceType(UserDocumentSourceType.TEXT).displayName(type.name() + " 자료").keepOriginal(false).build());
        DocumentExtraction extraction = extractionRepository.save(DocumentExtraction.builder().document(document)
                .extractionStatus(DocumentExtractionStatus.SUCCESS).versionStatus(DocumentVersionStatus.CONFIRMED)
                .content("[1페이지]\n" + content).pageCount(1).build());
        analysisCaseSourceRepository.save(AnalysisCaseSource.builder().analysisCase(analysisCase)
                .extraction(extraction).documentType(type).build());
    }

    private void saveActiveCategoryGuide(User user, JobCategory category, String suffix) {
        JobGuideDocument guide = JobGuideDocument.builder().guideCode("GUIDE-" + suffix).scopeType(GuideScopeType.CATEGORY)
                .jobCategory(category).title("Backend guide").sourceType(JobGuideDocumentSourceType.DIRECT_INPUT)
                .version("v1.0").createdBy(user).applicableScope("백엔드 개발")
                .evaluationFocus(List.of("요구사항 연결")).evidenceRules(List.of("근거 확인"))
                .questionDirection(List.of("구체 경험 확인")).avoidQuestions(List.of("추상 질문")).build();
        guide.activate();
        guide = guideDocumentRepository.saveAndFlush(guide);
        guideChunkRepository.saveAndFlush(JobGuideChunk.builder().guide(guide).chunkIndex(0).title("평가 기준")
                .content("Java와 Spring Boot 경험의 구체적 근거를 평가한다.").contentSummary("백엔드 평가 기준").build());
    }

    private void savePrompt(String code, String targetJson, String resourcePath, String version) throws IOException {
        String template = new ClassPathResource(resourcePath).getContentAsString(StandardCharsets.UTF_8);
        promptTemplateRepository.saveAndFlush(PromptTemplate.builder().promptCode(code).name(targetJson + " HTTP test")
                .version(version).targetJson(targetJson).templateText(template).forbiddenRules("JSON 이외의 응답 금지").isActive(true).build());
    }

    private RequestPostProcessor authenticated(User user) {
        return SecurityMockMvcRequestPostProcessors.user(new CustomUserDetails(user));
    }

    private record Fixture(User user, AnalysisCase analysisCase) { }
}
