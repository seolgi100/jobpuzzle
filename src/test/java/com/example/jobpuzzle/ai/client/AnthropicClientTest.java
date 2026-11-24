package com.example.jobpuzzle.ai.client;

import com.example.jobpuzzle.ai.config.AiGenerationProperties;
import com.example.jobpuzzle.ai.log.AiCallLogErrorType;
import com.example.jobpuzzle.ai.validation.AiProcessingException;
import com.example.jobpuzzle.document.entity.UserDocumentType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.test.web.client.MockRestServiceServer;

import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AnthropicClientTest {

    private static final String BASE_URL = "http://anthropic.test";
    private static final String DUMMY_KEY = "test-key-not-a-real-secret";

    private AiGenerationProperties properties;
    private MockRestServiceServer server;
    private AnthropicClient client;

    @BeforeEach
    void setUp() {
        properties = properties();
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new AnthropicClient(properties, builder.build(), new ObjectMapper());
    }

    @Test
    void sendsMessagesContractWithStageSpecificModelOutputTokensAndUserPrompt() {
        properties.getAnthropic().getMaxOutputTokens().setJson01(111);
        properties.getAnthropic().getMaxOutputTokens().setJson02(222);
        properties.getAnthropic().getMaxOutputTokens().setJson05(333);

        properties.getThinkingByStage().put(com.example.jobpuzzle.ai.log.AiExecutionStage.CANDIDATE_MATERIAL_ANALYSIS,
                AiGenerationProperties.ThinkingMode.DISABLED);
        expectMessage("claude-test-sonnet", 111, "JSON-01 prompt", false);
        expectMessage("claude-test-sonnet", 222, "JSON-02 prompt", true);
        expectMessage("claude-test-sonnet", 333, "JSON-05 prompt", false);

        assertThat(client.analyzeJobPosting("JSON-01 prompt")).isEqualTo("{}");
        assertThat(client.analyzeCandidateMaterial("JSON-02 prompt")).isEqualTo("{}");
        assertThat(client.generateCustomizedAnalysisV18("JSON-05 prompt", new ObjectMapper().createObjectNode())).isEqualTo("{}");
        server.verify();
    }

    @Test
    void concatenatesOnlyTextBlocksInResponseOrder() {
        server.expect(once(), requestTo(BASE_URL + "/v1/messages"))
                .andRespond(withSuccess("""
                        {"content":[
                          {"type":"text","text":"first"},
                          {"type":"tool_use","id":"ignored"},
                          {"type":"text","text":"-second"}
                        ],"stop_reason":"end_turn","usage":{"input_tokens":12,"output_tokens":34}}
                        """, MediaType.APPLICATION_JSON).header("request-id", "req_safe_123"));

        assertThat(client.analyzeJobPosting("prompt")).isEqualTo("first-second");
        var metadata = client.consumeCompletionMetadata();
        assertThat(metadata.stopReason()).isEqualTo("end_turn");
        assertThat(metadata.contentBlockTypes()).containsExactly("text", "tool_use");
        assertThat(metadata.textBlockPresent()).isTrue();
        assertThat(metadata.inputTokens()).isEqualTo(12);
        assertThat(metadata.outputTokens()).isEqualTo(34);
        assertThat(metadata.requestId()).isEqualTo("req_safe_123");
        assertThat(client.consumeCompletionMetadata()).isNull();
        server.verify();
    }

    @Test
    void responseWithoutTextBlockFailsAsEmptyResponse() {
        server.expect(once(), requestTo(BASE_URL + "/v1/messages"))
                .andRespond(withSuccess("{" + "\"content\":[{\"type\":\"tool_use\"}],\"stop_reason\":\"end_turn\"}" , MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.analyzeJobPosting("prompt"))
                .isInstanceOf(AiProcessingException.class)
                .satisfies(error -> {
                    AiProcessingException value = (AiProcessingException) error;
                    assertThat(value.getErrorType()).isEqualTo(AiCallLogErrorType.EMPTY_RESPONSE);
                    assertThat(value.getMessage()).contains("contentTypes=tool_use", "stopReason=end_turn")
                            .doesNotContain(DUMMY_KEY);
                });
        server.verify();
    }

    @Test
    void addsStructuredOutputOnlyForDocumentTypedJson02Request() {
        server.expect(once(), requestTo(BASE_URL + "/v1/messages"))
                .andExpect(jsonPath("$.output_config.format.type").value("json_schema"))
                .andExpect(jsonPath("$.output_config.format.schema.additionalProperties").value(false))
                .andExpect(jsonPath("$.output_config.format.schema.properties.availableDocumentTypes.items.enum[0]").value("RESUME"))
                .andExpect(jsonPath("$.output_config.format.schema.properties.coverLetter.type").value("null"))
                .andExpect(jsonPath("$.output_config.format.schema.properties.resume.type").value("object"))
                .andExpect(jsonPath("$.output_config.format.schema").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasKey("evidenceText"))))
                .andRespond(withSuccess("{\"content\":[{\"type\":\"text\",\"text\":\"{}\"}]}", MediaType.APPLICATION_JSON));
        assertThat(client.analyzeCandidateMaterial("prompt", UserDocumentType.RESUME)).isEqualTo("{}");
        server.verify();
    }

    @Test
    void usesCallerSuppliedJson05Schema() {
        var schema = new ObjectMapper().createObjectNode()
                .put("type", "object")
                .put("additionalProperties", false);
        schema.set("properties", new ObjectMapper().createObjectNode());
        schema.set("required", new ObjectMapper().createArrayNode());
        server.expect(once(), requestTo(BASE_URL + "/v1/messages"))
                .andExpect(jsonPath("$.output_config.format.type").value("json_schema"))
                .andExpect(jsonPath("$.output_config.format.schema.type").value("object"))
                .andExpect(jsonPath("$.output_config.format.schema.additionalProperties").value(false))
                .andExpect(jsonPath("$.output_config.format.schema.$defs").doesNotExist())
                .andRespond(withSuccess("{\"content\":[{\"type\":\"text\",\"text\":\"{}\"}]}", MediaType.APPLICATION_JSON));

        assertThat(client.generateCustomizedAnalysisV18("v1.3 prompt", schema)).isEqualTo("{}");
        server.verify();
    }

    @Test
    void responseStoppedAtMaxTokensFailsBeforeJsonContractProcessing() {
        server.expect(once(), requestTo(BASE_URL + "/v1/messages"))
                .andRespond(withSuccess("{" + "\"content\":[{\"type\":\"text\",\"text\":\"partial\"}],\"stop_reason\":\"max_tokens\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.analyzeCandidateMaterial("prompt"))
                .isInstanceOf(AiProcessingException.class)
                .satisfies(error -> {
                    AiProcessingException value = (AiProcessingException) error;
                    assertThat(value.getErrorType()).isEqualTo(AiCallLogErrorType.OUTPUT_LIMIT_EXCEEDED);
                    assertThat(value.getMessage()).contains("reached max_tokens", "contentTypes=text", "stopReason=max_tokens")
                            .doesNotContain(DUMMY_KEY);
                    assertThat(value.getCompletionMetadata()).isNotNull();
                    assertThat(value.getCompletionMetadata().stopReason()).isEqualTo("max_tokens");
                    assertThat(value.getCompletionMetadata().failureKind())
                            .isEqualTo(com.example.jobpuzzle.ai.log.AiFailureKind.OUTPUT_LIMIT_EXCEEDED);
                });
        server.verify();
    }

    @Test
    void refusalIsRecordedAsDedicatedInternalFailureKind() {
        server.expect(once(), requestTo(BASE_URL + "/v1/messages"))
                .andRespond(withSuccess("{\"content\":[{\"type\":\"text\",\"text\":\"refused\"}],\"stop_reason\":\"refusal\"}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.analyzeCandidateMaterial("prompt", UserDocumentType.RESUME))
                .isInstanceOf(AiProcessingException.class)
                .satisfies(error -> assertThat(((AiProcessingException) error).getCompletionMetadata().failureKind())
                        .isEqualTo(com.example.jobpuzzle.ai.log.AiFailureKind.REFUSAL));
        server.verify();
    }

    @Test
    void invalidEnvelopeUsesResponseParseFailureWithoutLeakingKey() {
        server.expect(once(), requestTo(BASE_URL + "/v1/messages"))
                .andRespond(withSuccess("not-json", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.analyzeJobPosting("prompt"))
                .isInstanceOf(AiProcessingException.class)
                .satisfies(error -> {
                    AiProcessingException value = (AiProcessingException) error;
                    assertThat(value.getErrorType()).isEqualTo(AiCallLogErrorType.RESPONSE_PARSE_FAILED);
                    assertThat(value.getMessage()).doesNotContain(DUMMY_KEY);
                });
        server.verify();
    }

    @Test
    void mapsHttpErrorsWithoutLeakingAuthenticationValue() {
        assertHttpFailure(HttpStatus.UNAUTHORIZED, AiCallLogErrorType.PROVIDER_ERROR);
        assertHttpFailure(HttpStatus.FORBIDDEN, AiCallLogErrorType.PROVIDER_ERROR);
        assertHttpFailure(HttpStatus.BAD_REQUEST, AiCallLogErrorType.PROVIDER_ERROR);
        assertHttpFailure(HttpStatus.NOT_FOUND, AiCallLogErrorType.PROVIDER_ERROR);
        assertHttpFailure(HttpStatus.CONFLICT, AiCallLogErrorType.PROVIDER_ERROR);
        assertHttpFailure(HttpStatus.UNPROCESSABLE_ENTITY, AiCallLogErrorType.PROVIDER_ERROR);
        assertHttpFailure(HttpStatus.TOO_MANY_REQUESTS, AiCallLogErrorType.RATE_LIMIT);
        assertHttpFailure(HttpStatus.INTERNAL_SERVER_ERROR, AiCallLogErrorType.PROVIDER_ERROR);
    }

    @Test
    void recordsOnlySafeProviderDiagnosticFields() {
        server.expect(once(), requestTo(BASE_URL + "/v1/messages"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .header("request-id", "req_safe_123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"error":{"type":"invalid_request_error","message":"request body is invalid"},
                                "untrusted":"%s"}
                                """.formatted(DUMMY_KEY)));

        assertThatThrownBy(() -> client.analyzeJobPosting("prompt"))
                .isInstanceOf(AiProcessingException.class)
                .satisfies(error -> {
                    AiProcessingException value = (AiProcessingException) error;
                    assertThat(value.getErrorType()).isEqualTo(AiCallLogErrorType.PROVIDER_ERROR);
                    assertThat(value.getMessage()).contains("httpStatus=400", "providerType=invalid_request_error",
                            "providerMessage=request body is invalid", "requestId=req_safe_123");
                    assertThat(value.getMessage()).doesNotContain(DUMMY_KEY);
                });
        server.verify();
    }

    @Test
    void mapsReadTimeoutToTimeoutWithoutRealNetworkCall() {
        ClientHttpRequestFactory timeoutFactory = (uri, method) -> {
            throw new SocketTimeoutException("dummy timeout");
        };
        AnthropicClient timeoutClient = new AnthropicClient(properties,
                RestClient.builder().baseUrl(BASE_URL).requestFactory(timeoutFactory).build(), new ObjectMapper());

        assertThatThrownBy(() -> timeoutClient.analyzeJobPosting("prompt"))
                .isInstanceOf(AiProcessingException.class)
                .satisfies(error -> {
                    AiProcessingException value = (AiProcessingException) error;
                    assertThat(value.getErrorType()).isEqualTo(AiCallLogErrorType.TIMEOUT);
                    assertThat(value.getMessage()).contains("exception=ResourceAccessException", "cause=SocketTimeoutException");
                    assertThat(value.getMessage()).doesNotContain(DUMMY_KEY);
                });
    }

    @Test
    void recordsOnlyExceptionClassForUnexpectedTransportRuntimeFailure() {
        ClientHttpRequestFactory failureFactory = (uri, method) -> new ClientHttpRequest() {
            @Override public org.springframework.http.HttpMethod getMethod() { return method; }
            @Override public java.net.URI getURI() { return uri; }
            @Override public org.springframework.http.HttpHeaders getHeaders() { return new org.springframework.http.HttpHeaders(); }
            @Override public java.util.Map<String, Object> getAttributes() { return java.util.Map.of(); }
            @Override public java.io.OutputStream getBody() { return java.io.OutputStream.nullOutputStream(); }
            @Override public ClientHttpResponse execute() { throw new IllegalStateException("must not be logged"); }
        };
        AnthropicClient failureClient = new AnthropicClient(properties,
                RestClient.builder().baseUrl(BASE_URL).requestFactory(failureFactory).build(), new ObjectMapper());

        assertThatThrownBy(() -> failureClient.analyzeJobPosting("prompt"))
                .isInstanceOf(AiProcessingException.class)
                .satisfies(error -> {
                    AiProcessingException value = (AiProcessingException) error;
                    assertThat(value.getMessage()).contains("exception=UnsupportedOperationException");
                    assertThat(value.getMessage()).doesNotContain("must not be logged");
                });
    }

    private void expectMessage(String model, int maxTokens, String prompt, boolean thinkingDisabled) {
        server.expect(once(), requestTo(BASE_URL + "/v1/messages"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header("x-api-key", DUMMY_KEY))
                .andExpect(header("anthropic-version", "2023-06-01"))
                .andExpect(jsonPath("$.model").value(model))
                .andExpect(jsonPath("$.max_tokens").value(maxTokens))
                .andExpect(jsonPath("$.messages[0].role").value("user"))
                .andExpect(jsonPath("$.messages[0].content").value(prompt))
                .andExpect(thinkingDisabled
                        ? jsonPath("$.thinking.type").value("disabled")
                        : jsonPath("$.thinking").doesNotExist())
                .andRespond(withSuccess("{\"content\":[{\"type\":\"text\",\"text\":\"{}\"}]}", MediaType.APPLICATION_JSON));
    }

    private void assertHttpFailure(HttpStatus status, AiCallLogErrorType expected) {
        server.reset();
        server.expect(once(), requestTo(BASE_URL + "/v1/messages"))
                // Dummy key is deliberately returned by the mock body; adapter errors must not expose it.
                .andRespond(withStatus(status).contentType(MediaType.APPLICATION_JSON).body(DUMMY_KEY));

        assertThatThrownBy(() -> client.analyzeJobPosting("prompt"))
                .isInstanceOf(AiProcessingException.class)
                .satisfies(error -> {
                    AiProcessingException value = (AiProcessingException) error;
                    assertThat(value.getErrorType()).isEqualTo(expected);
                    assertThat(value.getMessage()).doesNotContain(DUMMY_KEY);
                });
        server.verify();
    }

    private void assertFailure(Runnable action, AiCallLogErrorType expected) {
        assertThatThrownBy(action::run)
                .isInstanceOf(AiProcessingException.class)
                .extracting(error -> ((AiProcessingException) error).getErrorType())
                .isEqualTo(expected);
    }

    private AiGenerationProperties properties() {
        AiGenerationProperties value = new AiGenerationProperties();
        value.getAnthropic().setApiKey(DUMMY_KEY);
        value.getAnthropic().setModel("claude-test-sonnet");
        value.getAnthropic().setApiVersion("2023-06-01");
        return value;
    }
}
