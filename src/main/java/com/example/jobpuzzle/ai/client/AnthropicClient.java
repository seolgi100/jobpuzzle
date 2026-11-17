package com.example.jobpuzzle.ai.client;

import com.example.jobpuzzle.ai.dto.FinalReportResult;
import com.example.jobpuzzle.ai.dto.QuestionGenerationResult;
import com.example.jobpuzzle.ai.config.AiGenerationProperties;
import com.example.jobpuzzle.ai.log.AiCallLogErrorType;
import com.example.jobpuzzle.ai.log.AiFailureKind;
import com.example.jobpuzzle.ai.log.AiExecutionStage;
import com.example.jobpuzzle.ai.log.AiProvider;
import com.example.jobpuzzle.ai.validation.AiProcessingException;
import com.example.jobpuzzle.analysis.service.CandidateMaterialPartitionSchemaFactory;
import com.example.jobpuzzle.analysis.service.CustomizedSynthesisSchemaFactory;
import com.example.jobpuzzle.analysis.service.InterviewQuestionGenerationSchemaFactory;
import com.example.jobpuzzle.document.entity.UserDocumentType;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.Objects;
import java.util.regex.Pattern;

@Component
public class AnthropicClient implements AiClient {

    private static final Logger log = LoggerFactory.getLogger(AnthropicClient.class);
    private static final int PROVIDER_ERROR_DETAIL_LIMIT = 500;
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9_-]{1,100}");
    private static final Pattern SAFE_ENVELOPE_VALUE = Pattern.compile("[A-Za-z0-9_-]{1,100}");
    private static final Pattern JSON_CODE_BLOCK = Pattern.compile("^```(?:json)?\\s*\\n([\\s\\S]*)\\n?```$");

    private final AiGenerationProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final CandidateMaterialPartitionSchemaFactory partitionSchemas;
    private final CustomizedSynthesisSchemaFactory customizedSynthesisSchema;
    private final InterviewQuestionGenerationSchemaFactory interviewQuestionGenerationSchema;
    private final ThreadLocal<AiProviderCompletionMetadata> completionMetadata = new ThreadLocal<>();

    public AnthropicClient(
            AiGenerationProperties properties, @Qualifier("anthropicRestClient") RestClient restClient, ObjectMapper objectMapper
    ) {
        this(
                properties,
                restClient,
                objectMapper,
                new CandidateMaterialPartitionSchemaFactory(objectMapper),
                new CustomizedSynthesisSchemaFactory(objectMapper),
                new InterviewQuestionGenerationSchemaFactory(objectMapper)
        );
    }

    @Autowired
    public AnthropicClient(
            AiGenerationProperties properties,
            @Qualifier("anthropicRestClient") RestClient restClient,
            ObjectMapper objectMapper, CandidateMaterialPartitionSchemaFactory partitionSchemas,
            CustomizedSynthesisSchemaFactory customizedSynthesisSchema,
            InterviewQuestionGenerationSchemaFactory interviewQuestionGenerationSchema
    ) {
        this.properties = properties;
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.partitionSchemas = partitionSchemas;
        this.customizedSynthesisSchema = customizedSynthesisSchema;
        this.interviewQuestionGenerationSchema = interviewQuestionGenerationSchema;
    }

    @Override
    public String analyzeJobPosting(String prompt) {
        return message(AiExecutionStage.JOB_POSTING_ANALYSIS, prompt);
    }

    @Override
    public String analyzeCandidateMaterial(String prompt) {
        return message(AiExecutionStage.CANDIDATE_MATERIAL_ANALYSIS, prompt);
    }

    @Override
    public String analyzeCandidateMaterial(String prompt, UserDocumentType documentType) {
        return message(AiExecutionStage.CANDIDATE_MATERIAL_ANALYSIS, prompt, documentType);
    }

    @Override
    public String generateCustomizedAnalysis(String renderedPrompt) {
        return message(AiExecutionStage.CUSTOMIZED_SYNTHESIS, renderedPrompt);
    }

    // interview 추가: 실제 Claude HTTP 연동 시 이 두 메서드의 반환값만 JSON-11/JSON-09로 교체한다.
    @Override
    public String generateBasicQuestions(String renderedPrompt) {
        return message(AiExecutionStage.BASIC_QUESTION_GENERATION, renderedPrompt);
    }

    @Override
    public String generateQuestionsV2(String renderedPrompt) {
        return message(AiExecutionStage.QUESTION_GENERATION, renderedPrompt);
    }

    @Override
    public String evaluateAnswer(String renderedPrompt) {
        return message(AiExecutionStage.ANSWER_EVALUATION, renderedPrompt);
    }

    @Override
    public String generateCustomizedAnalysisV13(String renderedPrompt, com.fasterxml.jackson.databind.JsonNode outputSchema) {
        if (outputSchema == null || !outputSchema.isObject()) {
            throw new IllegalArgumentException("JSON-05 v1.3 output schema is required");
        }
        return message(AiExecutionStage.CUSTOMIZED_SYNTHESIS, renderedPrompt, null, outputSchema);
    }

    @Override
    public QuestionGenerationResult generateQuestions(String prompt) {
        return null;
    }

    @Override
    public FinalReportResult finalReport(String prompt) {
        return parseJson(message(AiExecutionStage.FINAL_REPORT, prompt), FinalReportResult.class);
    }

    @Override
    public AiProvider getProvider() {
        return AiProvider.ANTHROPIC;
    }

    @Override
    public String getModel() {
        return properties.getAnthropic().getModel();
    }

    @Override
    public String call(String prompt) {
        return message(AiExecutionStage.JOB_POSTING_ANALYSIS, prompt);
    }

    /**
     * Messages API의 text block만 기존 parser에 전달한다. JSON 파싱과 DTO/근거 검증은 이 adapter가 담당하지 않는다.
     * 인증값과 Provider 원시 오류 본문은 예외 메시지·로그에 포함하지 않는다.
     */
    private String message(AiExecutionStage stage, String prompt) {
        return message(stage, prompt, null);
    }
    private String message(AiExecutionStage stage, String prompt, UserDocumentType documentType) {
        return message(stage, prompt, documentType, null);
    }
    private String message(AiExecutionStage stage, String prompt, UserDocumentType documentType,
                           com.fasterxml.jackson.databind.JsonNode explicitOutputSchema) {
        completionMetadata.remove();
        validateSelectedConfiguration();
        if (prompt == null || prompt.isBlank()) throw failure(AiCallLogErrorType.EMPTY_RESPONSE, "AI prompt is empty");

        AiGenerationProperties.Anthropic anthropic = properties.getAnthropic();
        Request request = new Request(
                anthropic.getModel(),
                anthropic.getMaxOutputTokens().forStage(stage),
                List.of(new Message("user", prompt)),
                properties.thinkingFor(stage) == AiGenerationProperties.ThinkingMode.DISABLED
                        ? new Thinking("disabled") : null,
                explicitOutputSchema != null
                        ? new OutputConfig(new Format("json_schema", explicitOutputSchema))
                        : stage == AiExecutionStage.CANDIDATE_MATERIAL_ANALYSIS && documentType != null
                        ? new OutputConfig(new Format("json_schema", partitionSchemas.schema(documentType)))
                        : stage == AiExecutionStage.CUSTOMIZED_SYNTHESIS
                        ? new OutputConfig(new Format("json_schema", customizedSynthesisSchema.schemaForPrompt(prompt)))
                        : stage == AiExecutionStage.BASIC_QUESTION_GENERATION
                        ? new OutputConfig(new Format("json_schema", interviewQuestionGenerationSchema.schema()))
                        : null
        );

        final String rawEnvelope;
        final String requestId;
        try {
            ResponseEntity<String> providerResponse = restClient.post()
                    .uri("/v1/messages")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("x-api-key", anthropic.getApiKey())
                    .header("anthropic-version", anthropic.getApiVersion())
                    .body(request)
                    .retrieve()
                    .toEntity(String.class);
            rawEnvelope = providerResponse.getBody();
            requestId = safeRequestId(providerResponse.getHeaders().getFirst("request-id"));
        } catch (RestClientResponseException exception) {
            // Provider 원문은 저장하지 않는다. 재현에 필요한 상태·안전한 error 필드·request-id만 AiCallLog에 남긴다.
            throw failure(httpErrorType(exception.getStatusCode().value()), providerFailureMessage(exception));
        } catch (ResourceAccessException exception) {
            throw failure(isTimeout(exception) ? AiCallLogErrorType.TIMEOUT : AiCallLogErrorType.PROVIDER_ERROR,
                    transportFailureMessage(exception));
        } catch (RuntimeException exception) {
            // RestClient 구현체별로 transport 예외가 ResourceAccessException이 아닐 수 있다. 원문 메시지는 남기지 않는다.
            throw failure(AiCallLogErrorType.PROVIDER_ERROR, unexpectedFailureMessage(exception));
        }

        if (rawEnvelope == null || rawEnvelope.isBlank()) {
            throw failure(AiCallLogErrorType.EMPTY_RESPONSE, "Anthropic response is empty");
        }
        try {
            Response response = objectMapper.readValue(rawEnvelope, Response.class);
            String text = response.content() == null ? "" : response.content().stream()
                    // tool_use 등 비text block은 실행하거나 해석하지 않는다. text가 없으면 EMPTY_RESPONSE로 끝낸다.
                    .filter(block -> block != null && "text".equals(block.type()) && block.text() != null)
                    .map(ContentBlock::text)
                    .reduce("", String::concat);
            AiProviderCompletionMetadata metadata = metadataOf(response, requestId, !text.isBlank());
            if ("refusal".equals(response.stopReason())) {
                throw failure(AiCallLogErrorType.RESPONSE_VALIDATION_FAILED,
                        "Anthropic response was refused; " + responseShape(response),
                        metadata.withPostProcessing(null, null, null, false, AiFailureKind.REFUSAL));
            }
            // max_tokens라도 JSON 객체가 완결되면 기존 parser/validator가 계약 검증을 계속 수행한다.
            // 미완결일 때만 adaptive split 후보로 구조화해 기록한다.
            if ("max_tokens".equals(response.stopReason())) {
                boolean jsonComplete = isCompleteJsonObject(text);
                metadata = metadata.withPostProcessing(jsonComplete, null, null, !jsonComplete,
                        jsonComplete ? AiFailureKind.NONE : AiFailureKind.OUTPUT_LIMIT_EXCEEDED);
                if (!jsonComplete) {
                    throw failure(AiCallLogErrorType.OUTPUT_LIMIT_EXCEEDED,
                            "Anthropic response reached max_tokens; " + responseShape(response), metadata);
                }
            }
            if (text.isBlank()) {
                // 원문 content나 입력 prompt는 저장하지 않는다. 응답 형태만 남겨 실제 Provider의
                // 빈 응답·비text 응답을 안전하게 구분할 수 있게 한다.
                throw failure(AiCallLogErrorType.EMPTY_RESPONSE, emptyResponseMessage(response), metadata);
            }
            completionMetadata.set(metadata);
            return text;
        } catch (AiProcessingException exception) {
            throw exception;
        } catch (JsonProcessingException exception) {
            throw failure(AiCallLogErrorType.RESPONSE_PARSE_FAILED, "Anthropic response envelope is invalid");
        }
    }

    private void validateSelectedConfiguration() {
        AiGenerationProperties.Anthropic anthropic = properties.getAnthropic();
        if (anthropic.getApiKey() == null || anthropic.getApiKey().isBlank()
                || anthropic.getModel() == null || anthropic.getModel().isBlank()
                || anthropic.getApiVersion() == null || anthropic.getApiVersion().isBlank()) {
            throw failure(AiCallLogErrorType.PROVIDER_ERROR, "Anthropic configuration is incomplete");
        }
    }

    private AiCallLogErrorType httpErrorType(int status) {
        if (status == 429) return AiCallLogErrorType.RATE_LIMIT;
        // 400·401·403·404·409·422와 5xx 모두 공개 API 세분화 전에는 provider 오류로 기록한다.
        return AiCallLogErrorType.PROVIDER_ERROR;
    }

    private String providerFailureMessage(RestClientResponseException exception) {
        String requestId = exception.getResponseHeaders() == null ? null
                : exception.getResponseHeaders().getFirst("request-id");
        SafeErrorEnvelope envelope = null;
        try {
            envelope = objectMapper.readValue(exception.getResponseBodyAsString(), SafeErrorEnvelope.class);
        } catch (JsonProcessingException ignored) {
            // JSON이 아닌 오류 본문은 기록하지 않는다. provider 원문에는 인증값이나 입력값이 포함될 수 있다.
        }
        String type = sanitize(envelope == null || envelope.error() == null ? null : envelope.error().type());
        String message = sanitize(envelope == null || envelope.error() == null ? null : envelope.error().message());
        String safeRequestId = safeRequestId(requestId);

        StringBuilder detail = new StringBuilder("Anthropic request failed; httpStatus=")
                .append(exception.getStatusCode().value());
        if (type != null) detail.append("; providerType=").append(type);
        if (message != null) detail.append("; providerMessage=").append(message);
        if (safeRequestId != null) detail.append("; requestId=").append(safeRequestId);
        return detail.toString();
    }

    @Override
    public AiProviderCompletionMetadata consumeCompletionMetadata() {
        AiProviderCompletionMetadata value = completionMetadata.get();
        completionMetadata.remove();
        return value;
    }

    private AiProviderCompletionMetadata metadataOf(Response response, String requestId, boolean textPresent) {
        List<String> types = response.content() == null ? List.of() : response.content().stream()
                .filter(Objects::nonNull).map(ContentBlock::type).map(this::safeEnvelopeValue)
                .filter(Objects::nonNull).distinct().toList();
        return new AiProviderCompletionMetadata(safeEnvelopeValue(response.stopReason()), types, textPresent,
                response.usage() == null ? null : response.usage().inputTokens(),
                response.usage() == null ? null : response.usage().outputTokens(), requestId,
                null, null, null, null, AiFailureKind.NONE);
    }

    // DTO와 source reference 검증은 기존 AiResponseProcessor의 책임이다. 여기서는 JSON 문법 완결만 판단한다.
    private boolean isCompleteJsonObject(String text) {
        if (text == null || text.isBlank()) return false;
        try { return objectMapper.readTree(text).isObject(); }
        catch (JsonProcessingException exception) { return false; }
    }

    private String safeRequestId(String value) {
        return value != null && REQUEST_ID.matcher(value).matches() ? value : null;
    }

    private String sanitize(String value) {
        if (value == null || value.isBlank()) return null;
        String safe = value.replaceAll("[\\r\\n\\t]", " ").trim();
        String apiKey = properties.getAnthropic().getApiKey();
        if (apiKey != null && !apiKey.isBlank()) safe = safe.replace(apiKey, "[REDACTED]");
        return safe.isBlank() ? null : safe.substring(0, Math.min(safe.length(), PROVIDER_ERROR_DETAIL_LIMIT));
    }

    private String transportFailureMessage(ResourceAccessException exception) {
        // 네트워크 예외의 원문 메시지는 프록시·인증 정보를 포함할 수 있어 저장하지 않는다.
        Throwable cause = exception.getCause();
        String causeType = cause == null ? null : cause.getClass().getSimpleName();
        return causeType == null || causeType.isBlank()
                ? "Anthropic transport request failed; exception=" + exception.getClass().getSimpleName()
                : "Anthropic transport request failed; exception=" + exception.getClass().getSimpleName()
                + "; cause=" + causeType;
    }

    private String unexpectedFailureMessage(RuntimeException exception) {
        Throwable cause = exception.getCause();
        String detail = "Anthropic request failed; exception=" + exception.getClass().getSimpleName();
        return cause == null ? detail : detail + "; cause=" + cause.getClass().getSimpleName();
    }

    private String emptyResponseMessage(Response response) {
        return "Anthropic response has no text block; " + responseShape(response);
    }

    private String responseShape(Response response) {
        String stopReason = safeEnvelopeValue(response.stopReason());
        String blockTypes = response.content() == null ? "none" : response.content().stream()
                .filter(java.util.Objects::nonNull)
                .map(ContentBlock::type)
                .map(this::safeEnvelopeValue)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .reduce((left, right) -> left + "," + right)
                .orElse("none");
        StringBuilder message = new StringBuilder("contentTypes=").append(blockTypes);
        if (stopReason != null) message.append("; stopReason=").append(stopReason);
        return message.toString();
    }

    private String safeEnvelopeValue(String value) {
        return value != null && SAFE_ENVELOPE_VALUE.matcher(value).matches() ? value : null;
    }

    private boolean isTimeout(Throwable value) {
        Throwable current = value;
        while (current != null) {
            if (current instanceof SocketTimeoutException || current instanceof InterruptedIOException) return true;
            current = current.getCause();
        }
        return false;
    }

    private AiProcessingException failure(AiCallLogErrorType errorType, String message) {
        return failure(errorType, message, null);
    }

    private AiProcessingException failure(AiCallLogErrorType errorType, String message,
                                          AiProviderCompletionMetadata metadata) {
        return new AiProcessingException(errorType, message, metadata);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record Request(String model, @JsonProperty("max_tokens") int maxTokens, List<Message> messages, Thinking thinking,
                           @JsonProperty("output_config") OutputConfig outputConfig) { }
    private record OutputConfig(Format format) { }
    private record Format(String type, com.fasterxml.jackson.databind.JsonNode schema) { }
    // finalReport()처럼 typed 반환 메서드용 - message()가 돌려준 텍스트를 JSON으로 파싱
    // Provider가 앞뒤에 설명 문장이나 ```json 코드블록을 붙이는 경우를 대비해 코드블록만 제거함
    private <T> T parseJson(String text, Class<T> type) {
        String normalized = stripCodeFence(text.trim());
        try {
            return objectMapper.readValue(normalized, type);
        } catch (JsonProcessingException exception) {
            // 코드펜스가 명확하지 않거나 앞뒤에 설명 문장이 붙는 경우를 대비해, 첫 '{'~마지막 '}' 구간만 다시 시도한다.
            String extracted = extractJsonObject(normalized);
            if (extracted != null) {
                try {
                    return objectMapper.readValue(extracted, type);
                } catch (JsonProcessingException retryException) {
                    throw debugFailure(normalized, retryException);
                }
            }
            throw debugFailure(normalized, exception);
        }
    }

    private String stripCodeFence(String text) {
        Matcher matcher = JSON_CODE_BLOCK.matcher(text);
        return matcher.matches() ? matcher.group(1).trim() : text;
    }

    private String extractJsonObject(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return text.substring(start, end + 1);
    }

    // TEMP-DEBUG: 원인 확인되면 이 로그 라인 제거 예정. DB error_message에는 원문을 남기지 않고 서버 로그에만 전체 원문을 남긴다.
    private AiProcessingException debugFailure(String normalized, JsonProcessingException exception) {
        log.warn("JSON 파싱 실패 [{}] 원문 전체:\n{}", exception.getClass().getSimpleName(), normalized);
        return failure(AiCallLogErrorType.JSON_PARSE_FAIL, "Claude 응답 JSON 파싱 실패: " + exception.getClass().getSimpleName());
    }

    private record Message(String role, String content) { }
    private record Thinking(String type) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SafeErrorEnvelope(SafeError error) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SafeError(String type, String message) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Response(List<ContentBlock> content, @JsonProperty("stop_reason") String stopReason, Usage usage) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Usage(@JsonProperty("input_tokens") Long inputTokens, @JsonProperty("output_tokens") Long outputTokens) { }

    // Anthropic envelope에는 id·usage·tool input 등 adapter가 소유하지 않는 필드가 있으므로 text 추출에 필요한 값만 읽는다.
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ContentBlock(String type, String text) { }
}
