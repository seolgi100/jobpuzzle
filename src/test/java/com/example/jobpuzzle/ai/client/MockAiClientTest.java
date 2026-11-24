package com.example.jobpuzzle.ai.client;

import com.example.jobpuzzle.ai.dto.CandidateMaterialAnalysisResult;
import com.example.jobpuzzle.ai.dto.JobPostingAnalysisResult;
import com.example.jobpuzzle.ai.log.AiProvider;
import com.example.jobpuzzle.ai.validation.AnalysisSourceMarkerParser;
import com.example.jobpuzzle.analysis.entity.ActionPlanMatchLevel;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

public class MockAiClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MockAiClient mockAiClient = new MockAiClient(objectMapper, new AnalysisSourceMarkerParser());
    // Lombok @Builder만 쓰는 순수 객체라 스프링 없이 new로 바로 만들어도 됨

    @Test
    @DisplayName("공고 분석 결과는 null이 아니다")
    void analyzeJobPosting_원시_JSON과_실제_근거를_반환한다() throws Exception {
        // given
        String dummyPrompt = "[JOB_POSTING]\n[SOURCE extractionId=1 documentId=2][PAGE=1][SEGMENT=seg-001]\n공고 본문";

        // when
        JobPostingAnalysisResult result = objectMapper.readValue(mockAiClient.analyzeJobPosting(dummyPrompt), JobPostingAnalysisResult.class);

        // then
        assertThat(result).isNotNull();
        assertThat(result.getMainTasks()).isNotEmpty();
        assertThat(result.getMainTasks().get(0).getItemId()).isNotBlank();
        assertThat(result.getMainTasks().get(0).getSourceRefs()).hasSize(1);
        assertThat(result.getMainTasks().get(0).getSourceRefs().get(0).getExtractionId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("지원자 분석 결과는 null이 아니고, 중첩 객체와 리스트가 최소 1개 이상 존재한다")
    void analyzeCandidateMaterial_원시_JSON과_실제_근거를_반환한다() throws Exception {
        String prompt = "[RESUME]\n[SOURCE extractionId=3 documentId=4][PAGE=1][SEGMENT=seg-002]\n이력서 본문";
        CandidateMaterialAnalysisResult result = objectMapper.readValue(mockAiClient.analyzeCandidateMaterial(prompt), CandidateMaterialAnalysisResult.class);

        assertThat(result).isNotNull();
        assertThat(result.getAvailableDocumentTypes()).containsExactly(com.example.jobpuzzle.document.entity.UserDocumentType.RESUME);
        assertThat(result.getResume()).isNotNull();
        assertThat(result.getResume().getExperiences()).isNotEmpty();
        assertThat(result.getResume().getExperiences().get(0).getSourceRefs()).hasSize(1);
        assertThat(result.getCoverLetter()).isNull();
        assertThat(result.getPortfolio()).isNull();
        assertThat(result.getExperienceNote()).isNull();
    }

    @Test
    @DisplayName("Provider는 MOCK이다")
    void getProvider_MOCK을_반환한다() {
        AiProvider provider = mockAiClient.getProvider();

        assertThat(provider).isEqualTo(AiProvider.MOCK);
    }

}
