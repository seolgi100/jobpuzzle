package com.example.jobpuzzle;

import com.example.jobpuzzle.ai.client.MockAiClient;
import com.example.jobpuzzle.ai.log.AiCallLogErrorType;
import com.example.jobpuzzle.ai.log.AiCallLogRepository;
import com.example.jobpuzzle.ai.log.AiExecutionStage;
import com.example.jobpuzzle.ai.prompt.PromptTemplate;
import com.example.jobpuzzle.ai.validation.AiProcessingException;
import com.example.jobpuzzle.ai.prompt.PromptTemplateRepository;
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
import com.example.jobpuzzle.user.entity.UserRole;
import com.example.jobpuzzle.user.repository.EmailVerificationStore;
import com.example.jobpuzzle.user.repository.UserRepository;
import com.example.jobpuzzle.user.service.MailService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * 평가 명세 9-5의 3단계: stub 모드 전체 흐름.
 * 회원가입 → 자료 등록 → 분석 → 세 모드 면접 → 다회차 → 최종 보고서 → 조회 → 종합 평가 → 관리자 기능을 실제 API로 차례로 호출한다.
 * Docker 없이 돌도록 메모리 DB(H2)를 쓴다.
 */
@SpringBootTest(properties = {"app.rag.mode=fake", "app.ai.generation.mode=stub", "app.data-initializer.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("flow")
class FullFlowTest {

    private static final String LONG_ANSWER = "주문 조회 API가 느려서 실행 계획을 확인했고, 복합 인덱스를 추가해 응답 시간을 1.2초에서 0.3초로 줄였습니다.";
    private static final String SHORT_ANSWER = "인덱스를 추가했습니다.";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private EmailVerificationStore emailVerificationStore;
    @Autowired private UserRepository userRepository;
    @Autowired private JobCategoryRepository jobCategoryRepository;
    @Autowired private TicketService ticketService;
    @Autowired private PromptTemplateRepository promptTemplateRepository;
    @Autowired private JobGuideDocumentRepository guideDocumentRepository;
    @Autowired private JobGuideChunkRepository guideChunkRepository;
    @Autowired private AiCallLogRepository aiCallLogRepository;
    @MockitoBean private MailService mailService;
    // 평소에는 stub 그대로 응답하고, 시간 초과 테스트에서만 일부 호출이 시간 초과를 던지게 바꾼다
    @MockitoSpyBean private MockAiClient stubAi;

    private RequestPostProcessor auth;
    private JobCategory backend;
    private User admin;

    // 두 테스트가 같은 메모리 DB를 쓰므로 공통 준비는 한 번만 한다
    @BeforeEach
    void prepare() throws Exception {
        backend = jobCategoryRepository
                .findByMainCategoryAndSubCategoryAndCareerLevel("IT·개발", "백엔드 개발", JobCategoryCareerLevel.NEW).orElseThrow();
        admin = userRepository.findByLoginId("flowadmin").orElse(null);
        if (admin != null) return;
        admin = admin(backend);
        prepareAnalysisPrompts();
        prepareGuide(backend, admin);
    }

    @Test
    void signUpToAdminFeaturesInStubMode() throws Exception {
        // 1. 회원가입 → 로그인 (메일 발송만 막고, 인증 코드는 저장소에 직접 넣는다)
        signUp("flowuser");

        // 2~3. 자료 등록 → 분석
        Long caseId = analyzedCase();
        assertThat(data(get("/analysis/cases/" + caseId + "/status"), null, 200).path("analysisCaseStatus").asText()).isEqualTo("COMPLETED");

        // 4. 세 모드 면접: 질문 생성 → 면접 → 최종 보고서
        Long material = interview(data(post("/question-sets/material"), Map.of("analysisCaseId", caseId), 201));
        Long knowledge = interview(data(post("/question-sets/knowledge"), Map.of("analysisCaseId", caseId), 201));
        Long personality = interview(data(post("/question-sets/personality"), null, 201));

        JsonNode report = data(get("/interview-sessions/" + material + "/report"), null, 200);
        assertThat(report.path("completed").asBoolean()).isTrue();
        assertThat(report.path("round").asInt()).isEqualTo(1);
        assertThat(report.path("criteria")).hasSize(6);
        assertThat(report.path("totalScore").isNumber()).isTrue();
        assertThat(report.path("questions").get(0).path("comparison").isNull()).isTrue();
        assertThat(data(get("/interview-sessions/" + personality + "/report"), null, 200).path("mode").asText()).isEqualTo("PERSONALITY");

        // 5. 다회차: 같은 질문으로 다시 → 2회차 보고서에 이전 점수와 비교가 붙는다
        Long round2 = answerAll(data(post("/interview-sessions/" + material + "/retry"), null, 201).path("sessionId").asLong());
        JsonNode report2 = data(get("/interview-sessions/" + round2 + "/report"), null, 200);
        assertThat(report2.path("round").asInt()).isEqualTo(2);
        assertThat(report2.path("previousSessionId").asLong()).isEqualTo(material);
        assertThat(report2.path("previousTotalScore").isNumber()).isTrue();
        assertThat(report2.path("questions").get(0).path("comparison").path("improved").asText()).isNotBlank();
        // 다른 면접(기초 지식)의 다시 하기는 기초 지식 회차로만 이어진다
        Long knowledge2 = answerAll(data(post("/interview-sessions/" + knowledge + "/retry"), null, 201).path("sessionId").asLong());
        assertThat(data(get("/interview-sessions/" + knowledge2 + "/report"), null, 200).path("previousSessionId").asLong()).isEqualTo(knowledge);

        // 6. 조회: 기록 목록에 끝난 면접 5개
        assertThat(data(get("/interview-sessions/history"), null, 200)).hasSize(5);

        // 7. 종합 평가: 보고서 5개 이상이면 강점·아쉬운 점이 나온다
        JsonNode summary = data(get("/user/me/summary"), null, 200);
        assertThat(summary.path("available").asBoolean()).isTrue();
        assertThat(summary.path("reportCount").asInt()).isEqualTo(5);
        assertThat(summary.path("preferredJob").asText()).contains("백엔드 개발");
        assertThat(summary.path("strengths")).isNotEmpty();
        assertThat(summary.path("weaknesses")).isNotEmpty();

        // 8. 관리자: 일반 회원은 막히고, 관리자는 역량 태그·인성 질문을 추가·수정·삭제한다
        call(get("/admin-api/competency/jobs"), null, 403);
        auth = user(new CustomUserDetails(admin));
        Map<String, Object> tag = Map.of("mainCategory", "IT·개발", "subCategory", "백엔드 개발", "code", "FLOW_TAG",
                "name", "흐름 태그", "description", "전체 흐름 테스트용 태그");
        Long tagId = data(post("/admin-api/competency/tags"), tag, 200).path("tagId").asLong();
        assertThat(data(put("/admin-api/competency/tags/" + tagId), Map.of("mainCategory", "IT·개발", "subCategory", "백엔드 개발",
                "code", "FLOW_TAG", "name", "바뀐 태그", "description", "수정"), 200).path("name").asText()).isEqualTo("바뀐 태그");
        Map<String, Object> question = Map.of("mainCategory", "IT·개발", "subCategory", "백엔드 개발", "question", "갈등을 해결한 경험은?",
                "intent", "협업 방식 확인", "competencyTags", List.of("FLOW_TAG"), "careerLevel", "NEW");
        Long questionId = data(post("/admin-api/competency/questions"), question, 200).path("questionId").asLong();
        JsonNode job = data(get("/admin-api/competency?mainCategory=IT·개발&subCategory=백엔드 개발"), null, 200);
        assertThat(job.path("jobTags").findValuesAsText("code")).contains("FLOW_TAG");
        assertThat(job.path("personalityQuestions").findValuesAsText("question")).contains("갈등을 해결한 경험은?");
        data(delete("/admin-api/competency/questions/" + questionId), null, 200);
        data(delete("/admin-api/competency/tags/" + tagId), null, 200);
    }

    /**
     * 평가 명세 9-5의 2단계 "타임아웃 → 실패 처리": AI가 매번 시간 초과를 내면
     * 정해진 횟수만큼 다시 부른 뒤 실패로 끝나고, 티켓을 쓰는 작업은 티켓을 돌려준다. 답변 평가는 그 답변만 다시 낼 수 있다.
     */
    @Test
    void aiTimeoutsFailCleanlyAndRefundTickets() throws Exception {
        signUp("timeoutuser");
        AiProcessingException timeout = new AiProcessingException(AiCallLogErrorType.TIMEOUT, "stub timeout");

        // 분석: 공고 분석이 시간 초과 → 분석 FAILED, 티켓 그대로, 다시 실행하면 끝난다
        doThrow(timeout).when(stubAi).analyzeJobPosting(anyString());
        Long caseId = preparedCase();
        int balance = balance();
        assertThat(call(post("/analysis/cases/" + caseId + "/run"), null, -1).path("success").asBoolean()).isFalse();
        assertThat(data(get("/analysis/cases/" + caseId + "/status"), null, 200).path("analysisCaseStatus").asText()).isEqualTo("FAILED");
        assertThat(balance()).isEqualTo(balance);
        assertThat(timeoutLogs(AiExecutionStage.JOB_POSTING_ANALYSIS)).isPositive();
        reset(stubAi);
        assertThat(data(post("/analysis/cases/" + caseId + "/run"), null, 200).path("status").asText()).isEqualTo("COMPLETED");

        // 질문 생성: 3번(처음 + 다시 2번) 모두 시간 초과 → 실패, 질문 세트 없음, 티켓 그대로
        doThrow(timeout).when(stubAi).generateQuestionsV2(anyString());
        balance = balance();
        assertThat(call(post("/question-sets/material"), Map.of("analysisCaseId", caseId), -1).path("success").asBoolean()).isFalse();
        verify(stubAi, times(3)).generateQuestionsV2(anyString());
        assertThat(balance()).isEqualTo(balance);
        assertThat(data(get("/question-sets/prepared"), null, 200)).isEmpty();
        assertThat(timeoutLogs(AiExecutionStage.QUESTION_GENERATION)).isEqualTo(1);
        reset(stubAi);

        // 답변 평가: 시간 초과 → 그 답변만 실패, 같은 답을 다시 내면 평가된다
        JsonNode set = data(post("/question-sets/material"), Map.of("analysisCaseId", caseId), 201);
        Long questionId = data(get("/question-sets/" + set.path("questionSetId").asLong() + "/questions"), null, 200).get(0).path("questionId").asLong();
        Long sessionId = data(post("/interview-sessions"), Map.of("questionSetId", set.path("questionSetId").asLong(),
                "selectedQuestionIds", List.of(questionId)), 201).path("sessionId").asLong();
        JsonNode question = nextQuestion(sessionId);
        Map<String, Object> answer = Map.of("messageText", LONG_ANSWER, "answerType", "ORIGINAL_ANSWER",
                "parentQuestionMessageId", question.path("conversation").get(0).path("messageId").asLong());
        String answerPath = "/interview-session-questions/" + question.path("sessionQuestionId").asLong() + "/answers";

        doThrow(timeout).when(stubAi).evaluateAnswer(anyString());
        JsonNode failed = data(post(answerPath), answer, 200);
        assertThat(failed.path("evaluationFailed").asBoolean()).isTrue();
        assertThat(failed.path("evaluationRetryRequired").asBoolean()).isTrue();
        verify(stubAi, times(3)).evaluateAnswer(anyString());
        assertThat(timeoutLogs(AiExecutionStage.ANSWER_EVALUATION)).isEqualTo(1);
        reset(stubAi);

        JsonNode evaluated = data(post(answerPath), answer, 200);
        assertThat(evaluated.path("evaluationFailed").asBoolean()).isFalse();
        assertThat(evaluated.path("score").isNumber()).isTrue();
    }

    private Long signUp(String loginId) throws Exception {
        auth = null;
        String email = loginId + "@example.test";
        emailVerificationStore.save(email, 123456);
        call(post("/user/join"), Map.of("loginId", loginId, "password", "flowpass123", "email", email,
                "name", "흐름", "defaultJobCategoryId", backend.getJobCategoryId(), "code", 123456), 200);
        MockHttpSession session = new MockHttpSession();
        call(post("/user/login").session(session), Map.of("loginId", loginId, "password", "flowpass123"), 200);
        auth = request -> { request.setSession(session); return request; };
        Long userId = data(get("/user/me"), null, 200).path("userId").asLong();
        assertThat(userId).isEqualTo(userRepository.findByLoginId(loginId).orElseThrow().getUserId());
        ticketService.charge(userId, 100, loginId + "-order");
        return userId;
    }

    private Long analyzedCase() throws Exception {
        Long caseId = preparedCase();
        assertThat(data(post("/analysis/cases/" + caseId + "/run"), null, 200).path("status").asText()).isEqualTo("COMPLETED");
        return caseId;
    }

    /** 자료 등록(직접 입력 → 확정) → 분석 케이스 생성 → 자료 연결 → 확정 → 색인까지. run은 부르는 쪽이 한다. */
    private Long preparedCase() throws Exception {
        List<Long> extractionIds = new ArrayList<>();
        extractionIds.add(document("JOB_POSTING", "Java와 Spring Boot 기반 백엔드 개발자를 찾습니다. RDB 설계 경험을 우대합니다."));
        extractionIds.add(document("RESUME", "Java와 Spring Boot로 주문 REST API를 구현했고 MariaDB 인덱스로 조회 속도를 개선했습니다."));
        extractionIds.add(document("COVER_LETTER", "사용자가 기다리지 않는 서비스를 만들고 싶어 성능 개선에 집중해 왔습니다."));
        Long caseId = data(post("/analysis-cases"), Map.of("jobCategoryId", backend.getJobCategoryId()), 201).path("analysisCaseId").asLong();
        for (Long id : extractionIds) data(post("/analysis-cases/" + caseId + "/sources"), Map.of("extractionId", id), 201);
        data(post("/analysis-cases/" + caseId + "/confirm"), null, 201);
        data(post("/analysis-cases/" + caseId + "/index"), null, 200);
        return caseId;
    }

    private int balance() throws Exception {
        return data(get("/tickets/me"), null, 200).path("balance").asInt();
    }

    private long timeoutLogs(AiExecutionStage stage) {
        return aiCallLogRepository.findAll().stream()
                .filter(log -> log.getExecutionStage() == stage && log.getErrorType() == AiCallLogErrorType.TIMEOUT).count();
    }

    /** 질문 세트의 모든 질문으로 면접을 만들고 끝까지 답한다. */
    private Long interview(JsonNode questionSet) throws Exception {
        Long setId = questionSet.path("questionSetId").asLong();
        List<Long> ids = data(get("/question-sets/" + setId + "/questions"), null, 200).findValuesAsText("questionId")
                .stream().map(Long::valueOf).toList();
        Long sessionId = data(post("/interview-sessions"), Map.of("questionSetId", setId, "selectedQuestionIds", ids), 201)
                .path("sessionId").asLong();
        return answerAll(sessionId);
    }

    /** 화면(interview-live.js)처럼 질문 목록에서 아직 끝나지 않은 첫 질문을 고른다. */
    private JsonNode nextQuestion(Long sessionId) throws Exception {
        for (JsonNode q : data(get("/interview-sessions/" + sessionId + "/questions"), null, 200)) {
            String status = q.path("status").asText();
            if (!"COMPLETED".equals(status) && !"SKIPPED".equals(status)) return q;
        }
        return null;
    }

    /** 첫 질문은 짧게 답해 꼬리질문까지 거치고, 나머지는 충분히 답한 뒤 면접을 끝낸다. */
    private Long answerAll(Long sessionId) throws Exception {
        JsonNode question = nextQuestion(sessionId);
        boolean first = true;
        while (question != null && !question.isNull() && !question.isMissingNode()) {
            JsonNode result = data(post("/interview-session-questions/" + question.path("sessionQuestionId").asLong() + "/answers"),
                    Map.of("messageText", first ? SHORT_ANSWER : LONG_ANSWER, "answerType", "ORIGINAL_ANSWER",
                            "parentQuestionMessageId", question.path("conversation").get(0).path("messageId").asLong()), 200);
            assertThat(result.path("evaluationFailed").asBoolean()).isFalse();
            if (first) {
                assertThat(result.path("followUpQuestion").asText()).isNotBlank();
                result = data(post("/interview-session-questions/" + question.path("sessionQuestionId").asLong() + "/answers"),
                        Map.of("messageText", LONG_ANSWER, "answerType", "FOLLOW_UP_ANSWER",
                                "parentQuestionMessageId", result.path("followUpQuestionMessageId").asLong()), 200);
                first = false;
            }
            question = result.path("nextQuestion");
        }
        JsonNode session = data(get("/interview-sessions/" + sessionId), null, 200);
        if (!"COMPLETED".equals(session.path("status").asText())) {
            session = data(post("/interview-sessions/" + sessionId + "/complete"), null, 200);
        }
        assertThat(session.path("status").asText()).isEqualTo("COMPLETED");
        return sessionId;
    }

    private Long document(String type, String content) throws Exception {
        JsonNode draft = data(post("/documents/text"), Map.of("documentType", type, "content", content, "displayName", type + " 자료"), 201);
        return data(post("/document-extractions/" + draft.path("extractionId").asLong() + "/confirm"), null, 200)
                .path("extractionId").asLong();
    }

    private JsonNode data(MockHttpServletRequestBuilder request, Object body, int status) throws Exception {
        return call(request, body, status).path("data");
    }

    private JsonNode call(MockHttpServletRequestBuilder request, Object body, int status) throws Exception {
        if (auth != null) request.with(auth);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        var response = mockMvc.perform(request).andReturn().getResponse();
        String text = response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        // status -1: 실패 응답이면 무엇이든 (오류 코드는 이 테스트가 확인할 대상이 아님)
        if (status == -1) assertThat(response.getStatus()).as(text).isGreaterThanOrEqualTo(400);
        else assertThat(response.getStatus()).as(text).isEqualTo(status);
        return text.isBlank() ? objectMapper.nullNode() : objectMapper.readTree(text);
    }

    // 관리자 승격 API는 없어서(운영에서는 DB로 지정) 테스트에서 직접 지정한다
    private User admin(JobCategory category) {
        User admin = User.createLocalUser("flowadmin", "encoded", "admin@example.test", "관리자", category);
        ReflectionTestUtils.setField(admin, "role", UserRole.ADMIN);
        return userRepository.save(admin);
    }

    // 분석 프롬프트(JSON-01·02·05)는 운영에서 db/manual SQL로 넣으므로 테스트에서 같은 파일로 넣는다
    private void prepareAnalysisPrompts() throws Exception {
        prompt("JSON-01", "prompts/json-01-v1.3.txt", "v1.3");
        prompt("JSON-02", "prompts/json-02-v1.6.txt", "v1.6");
        prompt("JSON-05", "prompts/json-05-v1.10.txt", "v1.10");
    }

    private void prompt(String targetJson, String path, String version) throws Exception {
        promptTemplateRepository.save(PromptTemplate.builder().promptCode("FLOW-" + targetJson).name(targetJson + " flow")
                .version(version).targetJson(targetJson).forbiddenRules("JSON 이외의 응답 금지").isActive(true)
                .templateText(new ClassPathResource(path).getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).build());
    }

    // 직무 가이드도 운영에서는 관리자가 등록·활성화한다
    private void prepareGuide(JobCategory category, User admin) {
        JobGuideDocument guide = JobGuideDocument.builder().guideCode("FLOW-GUIDE").scopeType(GuideScopeType.CATEGORY)
                .jobCategory(category).title("백엔드 가이드").sourceType(JobGuideDocumentSourceType.DIRECT_INPUT)
                .version("v1.0").createdBy(admin).applicableScope("백엔드 개발")
                .evaluationFocus(List.of("요구사항 연결")).evidenceRules(List.of("근거 확인"))
                .questionDirection(List.of("구체 경험 확인")).avoidQuestions(List.of("추상 질문")).build();
        guide.activate();
        guide = guideDocumentRepository.save(guide);
        guideChunkRepository.save(JobGuideChunk.builder().guide(guide).chunkIndex(0).title("평가 기준")
                .content("Java와 Spring Boot 경험의 구체적 근거를 평가한다.").contentSummary("백엔드 평가 기준").build());
    }
}
