package com.example.jobpuzzle;

import com.example.jobpuzzle.ai.client.MockAiClient;
import com.example.jobpuzzle.ai.log.AiCallLog;
import com.example.jobpuzzle.ai.log.AiCallLogRepository;
import com.example.jobpuzzle.ai.log.AiProvider;
import com.example.jobpuzzle.ai.prompt.PromptTemplate;
import com.example.jobpuzzle.ai.prompt.PromptTemplateRepository;
import com.example.jobpuzzle.ai.validation.AnalysisSourceMarkerParser;
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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 실제 호출(live) 전 점검: 앱은 live 모드로, 진짜 AnthropicClient로 분석을 돌리고 네트워크만 가짜 응답기로 바꾼다.
 * 요청 형식(모델·thinking·출력 스키마), 응답 봉투 해석, 입력 한도, 스키마 한도를 비용 없이 확인한다.
 * 가짜 응답기는 단계마다 stub 응답을 Anthropic 응답 모양으로 감싸 돌려주고, 공고 분석은 요구사항 수를 실제 AI처럼 늘린다.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:dryrun;MODE=MariaDB;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=USER,VALUE,YEAR,MONTH,DAY,KEY;DB_CLOSE_DELAY=-1",
        "spring.main.allow-bean-definition-overriding=true",
        "app.rag.mode=fake", "app.data-initializer.enabled=false",
        "app.ai.generation.mode=live",
        "app.ai.generation.anthropic.api-key=dry-run-key",
        "app.ai.generation.anthropic.model=dry-run-model",
        // 요청만 보고 어느 단계인지 알 수 있게 단계마다 다른 최대 토큰을 준다
        "app.ai.generation.anthropic.max-output-tokens.json01=4001",
        "app.ai.generation.anthropic.max-output-tokens.json02=4002",
        "app.ai.generation.anthropic.max-output-tokens.json05=4005"})
@AutoConfigureMockMvc
@ActiveProfiles("flow")
class LiveAnalysisDryRunTest {

    record Call(int maxTokens, String model, String thinking, boolean schema, int promptChars) { }

    static final List<Call> CALLS = new CopyOnWriteArrayList<>();
    static volatile int requirementCount;

    @TestConfiguration
    static class FakeAnthropic {
        @Bean("anthropicRestClient")
        RestClient anthropicRestClient(ObjectMapper mapper, AnalysisSourceMarkerParser markerParser) {
            MockAiClient stub = new MockAiClient(mapper, markerParser);
            return RestClient.builder().baseUrl("http://dry-run.invalid").requestFactory((uri, method) -> new MockClientHttpRequest(method, uri) {
                @Override
                protected ClientHttpResponse executeInternal() {
                    try {
                        assertThat(getHeaders().getFirst("x-api-key")).isEqualTo("dry-run-key");
                        JsonNode request = mapper.readTree(getBodyAsString());
                        String prompt = request.path("messages").get(0).path("content").asText();
                        JsonNode schema = request.path("output_config").path("format").path("schema");
                        int maxTokens = request.path("max_tokens").asInt();
                        CALLS.add(new Call(maxTokens, request.path("model").asText(), request.path("thinking").path("type").asText(null),
                                schema.isObject(), prompt.length()));
                        String text = switch (maxTokens) {
                            case 4001 -> jobPosting(mapper, stub.analyzeJobPosting(prompt));
                            case 4002 -> stub.analyzeCandidateMaterial(prompt);
                            case 4005 -> stub.generateCustomizedAnalysisV18(prompt, schema);
                            default -> throw new IllegalStateException("예상하지 못한 단계 max_tokens=" + maxTokens);
                        };
                        ObjectNode envelope = mapper.createObjectNode().put("id", "msg_dry").put("type", "message")
                                .put("role", "assistant").put("stop_reason", "end_turn");
                        envelope.putArray("content").addObject().put("type", "text").put("text", text);
                        envelope.putObject("usage").put("input_tokens", prompt.length()).put("output_tokens", text.length());
                        MockClientHttpResponse response = new MockClientHttpResponse(
                                mapper.writeValueAsBytes(envelope), HttpStatus.OK);
                        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                        response.getHeaders().set("request-id", "req_dry");
                        return response;
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    }
                }
            }).build();
        }

        // stub은 요구사항을 1+1개만 돌려준다. 실제 AI처럼 requirementCount개(자격 절반, 우대 나머지)로 늘린다.
        private static String jobPosting(ObjectMapper mapper, String stubJson) throws Exception {
            ObjectNode result = (ObjectNode) mapper.readTree(stubJson);
            JsonNode template = result.path("requirements").get(0);
            int required = (requirementCount + 1) / 2;
            ArrayNode requirements = result.putArray("requirements");
            ArrayNode preferred = result.putArray("preferred");
            for (int i = 1; i <= requirementCount; i++) {
                ObjectNode item = template.deepCopy();
                boolean isRequired = i <= required;
                item.put("requirementId", (isRequired ? "requirement-" : "preferred-") + String.format("%03d", i));
                item.put("text", "요구사항 " + i + ": Java·Spring Boot 기반 기능 " + i + " 개발 경험");
                (isRequired ? requirements : preferred).add(item);
            }
            return mapper.writeValueAsString(result);
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private JobCategoryRepository jobCategoryRepository;
    @Autowired private TicketService ticketService;
    @Autowired private PromptTemplateRepository promptTemplateRepository;
    @Autowired private JobGuideDocumentRepository guideDocumentRepository;
    @Autowired private JobGuideChunkRepository guideChunkRepository;
    @Autowired private AiCallLogRepository aiCallLogRepository;

    private CustomUserDetails principal;

    @Test
    void liveAnalysisRequestsAndLimitsWithoutNetwork() throws Exception {
        JobCategory backend = jobCategoryRepository
                .findByMainCategoryAndSubCategoryAndCareerLevel("IT·개발", "백엔드 개발", JobCategoryCareerLevel.NEW).orElseThrow();
        User user = userRepository.save(User.createLocalUser("dryrun", "encoded", "dryrun@example.test", "점검", backend));
        principal = new CustomUserDetails(user);
        ticketService.charge(user.getUserId(), 100, "dryrun-order");
        prepare(backend, user);

        // 계획한 자료 분량(공고 1,800자, 이력서 2,000자, 자소서 2,500자, 포트폴리오 3,000자)과 비슷하게 만든다
        List<Long> extractions = List.of(
                document("JOB_POSTING", body("자격 요건: Java, Spring Boot, JPA, MariaDB, REST API, Git 협업 경험을 갖춘 분. ", 1_800)),
                document("RESUME", body("주문 API를 Spring Boot로 구현하고 MariaDB 인덱스로 조회 시간을 1.2초에서 0.3초로 줄였습니다. ", 2_000)),
                document("COVER_LETTER", body("사용자가 기다리지 않는 서비스를 만들기 위해 성능 개선과 테스트 코드 작성에 집중했습니다. ", 2_500)),
                document("PORTFOLIO", body("트러블슈팅: N+1 문제를 fetch join으로 해결해 쿼리 수를 101개에서 2개로 줄였습니다. ", 3_000)));

        for (int count : List.of(10, 17)) {
            Run run = analyze(extractions, backend, count);
            assertThat(run.status()).as("요구사항 %d개", count).isEqualTo(200);
            assertThat(run.stages(4005)).as("JSON-05 호출").isEqualTo(1);
            run.calls().forEach(call -> {
                assertThat(call.model()).isEqualTo("dry-run-model");
                assertThat(call.thinking()).isEqualTo("disabled");
            });
            assertThat(run.calls()).filteredOn(c -> c.maxTokens() != 4001).allMatch(Call::schema);
        }

        // 요구사항 18개: 입력 검사(20개)는 통과하지만 JSON-05 스키마 속성 한도(44)에 걸린다. 앞 단계는 이미 호출된 뒤다
        Run over = analyze(extractions, backend, 18);
        assertThat(over.status()).isNotEqualTo(200);
        assertThat(over.stages(4005)).as("JSON-05는 부르지 않는다").isZero();
        assertThat(over.stages(4001) + over.stages(4002)).as("이미 부른 앞 단계").isPositive();
    }

    record Run(int count, int status, String body, List<Call> calls) {
        long stages(int maxTokens) { return calls.stream().filter(c -> c.maxTokens() == maxTokens).count(); }
    }

    private Run analyze(List<Long> extractions, JobCategory category, int count) throws Exception {
        requirementCount = count;
        CALLS.clear();
        Long caseId = data(post("/analysis-cases"), Map.of("jobCategoryId", category.getJobCategoryId())).path("analysisCaseId").asLong();
        for (Long id : extractions) data(post("/analysis-cases/" + caseId + "/sources"), Map.of("extractionId", id));
        data(post("/analysis-cases/" + caseId + "/confirm"), null);
        data(post("/analysis-cases/" + caseId + "/index"), null);
        var response = mockMvc.perform(post("/analysis/cases/" + caseId + "/run").with(user(principal))).andReturn().getResponse();
        Run run = new Run(count, response.getStatus(), response.getContentAsString(StandardCharsets.UTF_8), List.copyOf(CALLS));
        report(run);
        return run;
    }

    // 사람이 읽을 점검표: 단계별 호출 수·가장 긴 프롬프트 길이·실패 이유
    private void report(Run run) {
        StringBuilder out = new StringBuilder("\n[DRY-RUN] 요구사항 ").append(run.count()).append("개 → HTTP ").append(run.status());
        for (int stage : List.of(4001, 4002, 4005)) {
            int longest = run.calls().stream().filter(c -> c.maxTokens() == stage).mapToInt(Call::promptChars).max().orElse(0);
            out.append("\n  ").append(Map.of(4001, "JSON-01", 4002, "JSON-02", 4005, "JSON-05").get(stage))
                    .append(" 호출 ").append(run.stages(stage)).append("번, 가장 긴 프롬프트 ").append(longest).append("자");
        }
        if (run.status() != 200) out.append("\n  응답: ").append(run.body());
        aiCallLogRepository.findAll().stream().filter(l -> l.getProvider() == AiProvider.ANTHROPIC && l.getErrorMessage() != null)
                .map(AiCallLog::getErrorMessage).distinct().forEach(m -> out.append("\n  호출 기록 오류: ").append(m));
        System.out.println(out);
    }

    private static String body(String sentence, int chars) {
        StringBuilder text = new StringBuilder();
        for (int line = 1; text.length() < chars; line++) text.append(line).append(". ").append(sentence).append('\n');
        return text.toString();
    }

    private Long document(String type, String content) throws Exception {
        JsonNode draft = data(post("/documents/text"), Map.of("documentType", type, "content", content, "displayName", type));
        return data(post("/document-extractions/" + draft.path("extractionId").asLong() + "/confirm"), null).path("extractionId").asLong();
    }

    private JsonNode data(MockHttpServletRequestBuilder request, Object body) throws Exception {
        request.with(user(principal));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        var response = mockMvc.perform(request).andReturn().getResponse();
        String text = response.getContentAsString(StandardCharsets.UTF_8);
        assertThat(response.getStatus()).as(text).isLessThan(300);
        return objectMapper.readTree(text).path("data");
    }

    private void prepare(JobCategory category, User user) throws Exception {
        for (String[] p : List.of(new String[]{"JSON-01", "prompts/json-01-v1.3.txt", "v1.3"},
                new String[]{"JSON-02", "prompts/json-02-v1.6.txt", "v1.6"}, new String[]{"JSON-05", "prompts/json-05-v1.10.txt", "v1.10"})) {
            promptTemplateRepository.save(PromptTemplate.builder().promptCode("DRY-" + p[0]).name(p[0]).version(p[2]).targetJson(p[0])
                    .forbiddenRules("JSON 이외의 응답 금지").isActive(true)
                    .templateText(new ClassPathResource(p[1]).getContentAsString(StandardCharsets.UTF_8)).build());
        }
        JobGuideDocument guide = JobGuideDocument.builder().guideCode("DRY-GUIDE").scopeType(GuideScopeType.CATEGORY)
                .jobCategory(category).title("백엔드 가이드").sourceType(JobGuideDocumentSourceType.DIRECT_INPUT)
                .version("v1.0").createdBy(user).applicableScope("백엔드 개발")
                .evaluationFocus(List.of("요구사항 연결")).evidenceRules(List.of("근거 확인"))
                .questionDirection(List.of("구체 경험 확인")).avoidQuestions(List.of("추상 질문")).build();
        guide.activate();
        guide = guideDocumentRepository.save(guide);
        guideChunkRepository.save(JobGuideChunk.builder().guide(guide).chunkIndex(0).title("평가 기준")
                .content("Java와 Spring Boot 경험의 구체적 근거를 평가한다.").contentSummary("백엔드 평가 기준").build());
    }
}
