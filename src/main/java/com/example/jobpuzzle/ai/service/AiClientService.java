package com.example.jobpuzzle.ai.service;

import com.example.jobpuzzle.ai.client.AiClient;
import com.example.jobpuzzle.ai.client.AiProviderCompletionMetadata;
import com.example.jobpuzzle.ai.config.AiGenerationProperties;
import com.example.jobpuzzle.ai.dto.QuestionGenerationResult;
import com.example.jobpuzzle.ai.log.AiExecutionStage;
import com.example.jobpuzzle.ai.log.AiProvider;
import com.example.jobpuzzle.document.entity.UserDocumentType;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
//@RequiredArgsConstructor
//@Transactional
public class AiClientService {

    private final AiClient mockAiClient;
    private final AiClient anthropicClient;
    private final AiGenerationProperties properties;

    // app.ai.generation.mode: stub 또는 live. 코드 수정 없이 설정만으로 전환
    public AiClientService(
            @Qualifier("mockAiClient") AiClient mockAiClient,
            @Qualifier("anthropicClient") AiClient anthropicClient,
            AiGenerationProperties properties
    ) {
        this.mockAiClient = mockAiClient;
        this.anthropicClient = anthropicClient;
        this.properties = properties;
    }

    /** stage별로 한 번 선택한 client/provider/model을 이후 log·호출에서 함께 사용한다. */
    public GenerationClientSelection resolve(AiExecutionStage stage) {
        AiProvider provider = properties.providerFor(stage);
        return switch (provider) {
            case MOCK -> new GenerationClientSelection(stage, mockAiClient, AiProvider.MOCK,
                    mockAiClient.getModel(), properties.getAnthropic().getMaxOutputTokens().forStage(stage));
            case ANTHROPIC -> anthropicSelection(stage);
        };
    }

    // Provider의 원시 JSON 응답을 호출 계층에 그대로 전달한다.
    public String analyzeJobPosting(GenerationClientSelection selection, String prompt){
        requireStage(selection, AiExecutionStage.JOB_POSTING_ANALYSIS);
        return selection.client().analyzeJobPosting(prompt);
    }

    public String analyzeCandidateMaterial(GenerationClientSelection selection, String prompt) {
        requireStage(selection, AiExecutionStage.CANDIDATE_MATERIAL_ANALYSIS);
        return selection.client().analyzeCandidateMaterial(prompt);
    }

    public String generateCustomizedAnalysisV18(
            GenerationClientSelection selection, String renderedPrompt, JsonNode outputSchema
    ) {
        requireStage(selection, AiExecutionStage.CUSTOMIZED_SYNTHESIS);
        return selection.client().generateCustomizedAnalysisV18(renderedPrompt, outputSchema);
    }

    public String analyzeCandidateMaterial(GenerationClientSelection selection, String prompt, UserDocumentType documentType) {
        requireStage(selection, AiExecutionStage.CANDIDATE_MATERIAL_ANALYSIS);
        return selection.client().analyzeCandidateMaterial(prompt, documentType);
    }

    // provider 호출 직후 같은 스레드에서 한 번만 소비한다. 이전 호출 metadata가 다음 단계에 섞이지 않게 한다.
    public AiProviderCompletionMetadata consumeCompletionMetadata(GenerationClientSelection selection) {
        if (selection == null) throw new IllegalArgumentException("generation client selection is required");
        return selection.client().consumeCompletionMetadata();
    }

    private GenerationClientSelection anthropicSelection(AiExecutionStage stage) {
        AiGenerationProperties.Anthropic anthropic = properties.getAnthropic();
        if (anthropic.getApiKey() == null || anthropic.getApiKey().isBlank()) {
            throw new IllegalStateException("Anthropic API key is required when Anthropic is selected");
        }
        if (anthropic.getModel() == null || anthropic.getModel().isBlank()) {
            throw new IllegalStateException("Anthropic model is required when Anthropic is selected");
        }
        if (anthropic.getApiVersion() == null || anthropic.getApiVersion().isBlank()) {
            throw new IllegalStateException("Anthropic API version is required when Anthropic is selected");
        }
        if (anthropic.getRequestTimeout() == null || anthropic.getRequestTimeout().isNegative() || anthropic.getRequestTimeout().isZero()) {
            throw new IllegalStateException("Anthropic request timeout must be positive when Anthropic is selected");
        }
        return new GenerationClientSelection(stage, anthropicClient, AiProvider.ANTHROPIC,
                anthropic.getModel(), anthropic.getMaxOutputTokens().forStage(stage));
    }

    public String generateQuestionsV2(GenerationClientSelection selection, String renderedPrompt) {
        requireStage(selection, AiExecutionStage.QUESTION_GENERATION);
        return selection.client().generateQuestionsV2(renderedPrompt);
    }

    public String evaluateAnswer(GenerationClientSelection selection, String renderedPrompt) {
        requireStage(selection, AiExecutionStage.ANSWER_EVALUATION);
        return selection.client().evaluateAnswer(renderedPrompt);
    }

    private void requireStage(GenerationClientSelection selection, AiExecutionStage expected) {
        if (selection == null || selection.stage() != expected) {
            throw new IllegalArgumentException("generation client selection stage does not match call");
        }
    }

    // 아래 두 API는 분석 외 모듈의 기존 호출 표면을 보존한다. stage 기반 분석 실행에는 사용하지 않는다.
    public QuestionGenerationResult generateQuestions(String prompt) { return mockAiClient.generateQuestions(prompt); }
    public AiProvider getProvider() {
        return resolve(AiExecutionStage.FINAL_REPORT).provider();
    }

    public String getModel() {
        return resolve(AiExecutionStage.FINAL_REPORT).model();
    }
}
