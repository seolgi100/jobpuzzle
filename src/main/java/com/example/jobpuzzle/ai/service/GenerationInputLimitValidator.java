package com.example.jobpuzzle.ai.service;

import com.example.jobpuzzle.ai.config.AiGenerationProperties;
import com.example.jobpuzzle.ai.log.AiCallLogErrorType;
import com.example.jobpuzzle.ai.log.AiExecutionStage;
import com.example.jobpuzzle.ai.validation.AiProcessingException;
import com.example.jobpuzzle.analysis.rag.dto.RetrievedEvidenceContextDto;
import org.springframework.stereotype.Component;

/** Provider 호출 전 입력 계약을 검사한다. 초과 자료를 임의 요약·절단하지 않고 명시적으로 실패시킨다. */
@Component
public class GenerationInputLimitValidator {

    private final AiGenerationProperties properties;

    public GenerationInputLimitValidator(AiGenerationProperties properties) {
        this.properties = properties;
    }

    public void validateRenderedPrompt(GenerationClientSelection selection, String renderedPrompt) {
        if (renderedPrompt == null) throw limit("rendered prompt is missing");
        int limit = properties.getInputLimits().renderedPromptLimit(selection.stage());
        requirePositive(limit, "rendered prompt limit");
        if (renderedPrompt.length() > limit) {
            throw limit(selection.stage() + " rendered prompt chars=" + renderedPrompt.length() + " exceeds limit=" + limit);
        }
    }

    private void requirePositive(int value, String name) {
        if (value < 1) throw limit(name + " must be positive");
    }

    private AiProcessingException limit(String message) {
        return new AiProcessingException(AiCallLogErrorType.INPUT_LIMIT_EXCEEDED, message);
    }
}
