package com.example.jobpuzzle.ai.service;

import com.example.jobpuzzle.ai.client.AiClient;
import com.example.jobpuzzle.ai.config.AiGenerationProperties;
import com.example.jobpuzzle.ai.log.AiCallLogErrorType;
import com.example.jobpuzzle.ai.log.AiExecutionStage;
import com.example.jobpuzzle.ai.log.AiProvider;
import com.example.jobpuzzle.ai.validation.AiProcessingException;
import com.example.jobpuzzle.analysis.entity.RequirementType;
import com.example.jobpuzzle.analysis.rag.dto.RetrievedEvidenceContextDto;
import com.example.jobpuzzle.analysis.rag.entity.RetrievalStatus;
import com.example.jobpuzzle.document.entity.UserDocumentType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class GenerationInputLimitValidatorTest {

    @Test
    void renderedPromptOverLimitFailsBeforeProviderCallWithDedicatedInternalType() {
        AiGenerationProperties properties = new AiGenerationProperties();
        properties.getInputLimits().setJson01RenderedPromptChars(3);
        GenerationInputLimitValidator validator = new GenerationInputLimitValidator(properties);
        GenerationClientSelection selection = selection(AiExecutionStage.JOB_POSTING_ANALYSIS);

        assertThatThrownBy(() -> validator.validateRenderedPrompt(selection, "1234"))
                .isInstanceOf(AiProcessingException.class)
                .extracting(error -> ((AiProcessingException) error).getErrorType())
                .isEqualTo(AiCallLogErrorType.INPUT_LIMIT_EXCEEDED);
    }

    @Test
    void defaultLimitsAcceptFixtureScalePrompts() {
        GenerationInputLimitValidator validator = new GenerationInputLimitValidator(new AiGenerationProperties());

        validator.validateRenderedPrompt(selection(AiExecutionStage.JOB_POSTING_ANALYSIS), "x".repeat(2_270));
        validator.validateRenderedPrompt(selection(AiExecutionStage.CANDIDATE_MATERIAL_ANALYSIS), "x".repeat(2_818));
        validator.validateRenderedPrompt(selection(AiExecutionStage.CUSTOMIZED_SYNTHESIS), "x".repeat(7_033));

        assertThat(true).isTrue();
    }

    private RetrievedEvidenceContextDto.RequirementEvidence requirement(String content) {
        return new RetrievedEvidenceContextDto.RequirementEvidence("req", RequirementType.REQUIRED, "requirement",
                RetrievalStatus.COMPLETED, List.of(new RetrievedEvidenceContextDto.RetrievedChunk(
                1L, 2L, 3L, UserDocumentType.RESUME, 1, 1, 0, content.length(), 1, 0.5, content)));
    }

    private GenerationClientSelection selection(AiExecutionStage stage) {
        return new GenerationClientSelection(stage, mock(AiClient.class), AiProvider.MOCK, "mock-v1", 1);
    }
}
