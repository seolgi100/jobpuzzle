package com.example.jobpuzzle.ai.client;

import com.example.jobpuzzle.ai.dto.FinalReportResult;
import com.example.jobpuzzle.ai.dto.QuestionGenerationResult;
import com.example.jobpuzzle.ai.log.AiProvider;
import com.example.jobpuzzle.document.entity.UserDocumentType;
import com.fasterxml.jackson.databind.JsonNode;

public interface AiClient {

    String analyzeJobPosting(String prompt);

    String analyzeCandidateMaterial(String prompt);

    default String analyzeCandidateMaterial(String prompt, UserDocumentType documentType) {
        return analyzeCandidateMaterial(prompt);
    }

    String generateCustomizedAnalysis(String renderedPrompt);

    default String generateCustomizedAnalysisV13(String renderedPrompt, JsonNode outputSchema) {
        return generateCustomizedAnalysis(renderedPrompt);
    }

    default String generateCustomizedAnalysisV15(String renderedPrompt, JsonNode outputSchema) {
        return generateCustomizedAnalysisV13(renderedPrompt, outputSchema);
    }

    default String generateCustomizedAnalysisV16(String renderedPrompt, JsonNode outputSchema) {
        return generateCustomizedAnalysisV13(renderedPrompt, outputSchema);
    }

    default String generateCustomizedAnalysisV17(String renderedPrompt, JsonNode outputSchema) {
        return generateCustomizedAnalysisV13(renderedPrompt, outputSchema);
    }

    default String generateCustomizedAnalysisV18(String renderedPrompt, JsonNode outputSchema) {
        return generateCustomizedAnalysisV13(renderedPrompt, outputSchema);
    }

    // interview 추가: JSON-11 기본 질문 생성 Provider 계약
    String generateBasicQuestions(String renderedPrompt);

    // 평가 명세 v2: JSON-12 기초 지식·자료 기반 질문 생성
    String generateQuestionsV2(String renderedPrompt);

    String evaluateAnswer(String renderedPrompt);

    QuestionGenerationResult generateQuestions(String prompt);

    FinalReportResult finalReport(String prompt);

    AiProvider getProvider();

    String getModel();

    String call(String prompt);

    // 기존 문자열 반환 계약은 유지한다. metadata가 없는 provider는 null을 반환한다.
    default AiProviderCompletionMetadata consumeCompletionMetadata() {
        return null;
    }
}
