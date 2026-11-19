package com.example.jobpuzzle.ai.validation;

import com.example.jobpuzzle.ai.dto.JobPostingAnalysisResult;
import com.example.jobpuzzle.ai.log.AiCallLogErrorType;
import com.example.jobpuzzle.analysis.dto.AnalysisInputSnapshotContextSource;
import com.example.jobpuzzle.document.entity.UserDocumentType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiResponseProcessorTest {

    private final AiResponseProcessor processor = new AiResponseProcessor(new ObjectMapper(), new AnalysisSourceMarkerParser());
    private final AnalysisInputSnapshotContextSource jobPosting = source(
            UserDocumentType.JOB_POSTING, 10L, 20L,
            "[SOURCE extractionId=10 documentId=20][PAGE=1][SEGMENT=seg-001]\n검증 가능한 공고 근거"
    );
    private final AnalysisInputSnapshotContextSource resume = source(
            UserDocumentType.RESUME, 11L, 21L,
            "[SOURCE extractionId=11 documentId=21][PAGE=1][SEGMENT=seg-002]\n검증 가능한 이력서 근거"
    );

    @Test
    void parsesFullJsonCodeBlockAndValidatesEchoedSourceReference() {
        JobPostingAnalysisResult result = processor.parseJobPosting("""
                ```json
                %s
                ```
                """.formatted(validJobJson("10", "20", "JOB_POSTING", "1", "seg-001", "검증 가능한 공고 근거")), List.of(jobPosting));

        assertThat(result.getMainTasks()).hasSize(1);
    }

    @Test
    void parsesSingleJsonObjectSurroundedByProviderExplanation() {
        String response = "분석 결과는 아래 JSON입니다.\n" + validJobJson("10", "20", "JOB_POSTING", "1", "seg-001", "검증 가능한 공고 근거")
                + "\n이 객체만 사용하세요.";

        JobPostingAnalysisResult result = processor.parseJobPosting(response, List.of(jobPosting));

        assertThat(result.getMainTasks()).hasSize(1);
    }

    @Test
    void rejectsMultipleJsonObjectsInsteadOfSelectingOneArbitrarily() {
        String valid = validJobJson("10", "20", "JOB_POSTING", "1", "seg-001", "검증 가능한 공고 근거");

        assertFailure(() -> processor.parseJobPosting(valid + "\n" + valid, List.of(jobPosting)), AiCallLogErrorType.RESPONSE_PARSE_FAILED);
    }

    @Test
    void rejectsMalformedJsonAndUnknownField() {
        assertThatThrownBy(() -> processor.parseJobPosting("{", List.of(jobPosting)))
                .isInstanceOf(AiProcessingException.class)
                .satisfies(error -> {
                    AiProcessingException value = (AiProcessingException) error;
                    assertThat(value.getErrorType()).isEqualTo(AiCallLogErrorType.RESPONSE_PARSE_FAILED);
                    assertThat(value.getMessage()).contains("JSON-01 must contain exactly one JSON object",
                            "chars=1", "completedObjects=0", "openDepth=1", "first=OBJECT_OPEN", "last=OBJECT_OPEN");
                });
        assertFailure(() -> processor.parseJobPosting(validJobJson("10", "20", "JOB_POSTING", "1", "seg-001", "검증 가능한 공고 근거")
                .replace("{", "{\"unexpected\":true,"), List.of(jobPosting)), AiCallLogErrorType.RESPONSE_PARSE_FAILED);
        assertFailure(() -> processor.parseJobPosting(validJobJson("10", "20", "UNKNOWN_TYPE", "1", "seg-001", "검증 가능한 공고 근거"), List.of(jobPosting)), AiCallLogErrorType.RESPONSE_PARSE_FAILED);
    }

    @Test
    void rejectsMissingTopLevelFieldAndDuplicateId() {
        assertFailure(() -> processor.parseJobPosting("{\"mainTasks\":[]}", List.of(jobPosting)), AiCallLogErrorType.RESPONSE_VALIDATION_FAILED);
        String duplicate = validJobJson("10", "20", "JOB_POSTING", "1", "seg-001", "검증 가능한 공고 근거")
                .replace("}],\"requirements\"", "},{\"itemId\":\"task-1\",\"text\":\"공고 업무\",\"sourceRefs\":[{\"extractionId\":10,\"documentId\":20,\"documentType\":\"JOB_POSTING\",\"pageNumber\":1,\"segmentId\":\"seg-001\",\"evidenceText\":\"검증 가능한 공고 근거\"}]}],\"requirements\"");
        assertFailure(() -> processor.parseJobPosting(duplicate, List.of(jobPosting)), AiCallLogErrorType.RESPONSE_VALIDATION_FAILED);
    }

    @Test
    void rejectsUnknownSourceAndSourceAttributeMismatch() {
        assertFailure(() -> processor.parseJobPosting(validJobJson("99", "20", "JOB_POSTING", "1", "seg-001", "검증 가능한 공고 근거"), List.of(jobPosting)), AiCallLogErrorType.SOURCE_REFERENCE_INVALID);
        assertFailure(() -> processor.parseJobPosting(validJobJson("10", "21", "JOB_POSTING", "1", "seg-001", "검증 가능한 공고 근거"), List.of(jobPosting)), AiCallLogErrorType.SOURCE_REFERENCE_INVALID);
        assertFailure(() -> processor.parseJobPosting(validJobJson("10", "20", "JOB_POSTING", "2", "seg-001", "검증 가능한 공고 근거"), List.of(jobPosting)), AiCallLogErrorType.SOURCE_REFERENCE_INVALID);
        assertFailure(() -> processor.parseJobPosting(validJobJson("10", "20", "JOB_POSTING", "1", "seg-x", "검증 가능한 공고 근거"), List.of(jobPosting)), AiCallLogErrorType.SOURCE_REFERENCE_INVALID);
    }

    @Test
    void replacesModelEvidenceTextWithCanonicalMarkerExcerptForJson01() {
        JobPostingAnalysisResult result = processor.parseJobPosting(
                validJobJson("10", "20", "JOB_POSTING", "1", "seg-001", "모델이 재서술한 근거"),
                List.of(jobPosting)
        );

        assertThat(result.getMainTasks().getFirst().getSourceRefs().getFirst().getEvidenceText())
                .isEqualTo("검증 가능한 공고 근거");
    }

    @Test
    void dropsUnverifiableConflictInsteadOfRejectingWholeJson01() {
        String response = validJobJson("10", "20", "JOB_POSTING", "1", "seg-001", "검증 가능한 공고 근거")
                .replace("\"conflicts\":[]", """
                        "conflicts":[{"field":"근무 조건","postingValue":"공고 값","companyInfoValue":"회사 값","appliedValue":"공고 값","sourceRefs":[{"extractionId":10,"documentId":20,"documentType":"JOB_POSTING","pageNumber":1,"segmentId":"seg-001","evidenceText":"검증 가능한 공고 근거"}]}]""");

        JobPostingAnalysisResult result = processor.parseJobPosting(response, List.of(jobPosting));

        assertThat(result.getMainTasks()).hasSize(1);
        assertThat(result.getConflicts()).isEmpty();
    }

    @Test
    void removesOnlyNonEvaluativeRequirementsFromJson01() {
        String sourceRef = "[{\"extractionId\":10,\"documentId\":20,\"documentType\":\"JOB_POSTING\","
                + "\"pageNumber\":1,\"segmentId\":\"seg-001\",\"evidenceText\":\"검증 가능한 공고 근거\"}]";
        String response = validJobJson("10", "20", "JOB_POSTING", "1", "seg-001", "검증 가능한 공고 근거")
                .replace("\"requirements\":[]", "\"requirements\":["
                        + "{\"requirementId\":\"req-1\",\"text\":\"학력 무관\",\"sourceRefs\":" + sourceRef + "},"
                        + "{\"requirementId\":\"req-2\",\"text\":\"학사 이상\",\"sourceRefs\":" + sourceRef + "}]")
                .replace("\"preferred\":[]", "\"preferred\":["
                        + "{\"requirementId\":\"pref-1\",\"text\":\"경력 제한 없음\",\"sourceRefs\":" + sourceRef + "},"
                        + "{\"requirementId\":\"pref-2\",\"text\":\"관련 전공 우대\",\"sourceRefs\":" + sourceRef + "}]");

        JobPostingAnalysisResult result = processor.parseJobPosting(response, List.of(jobPosting));

        assertThat(result.getRequirements()).extracting(JobPostingAnalysisResult.Requirement::getText)
                .containsExactly("학사 이상");
        assertThat(result.getPreferred()).extracting(JobPostingAnalysisResult.Requirement::getText)
                .containsExactly("관련 전공 우대");
    }

    @Test
    void fillsMissingEvidenceTextFromSelectedMarkerForJson02() {
        String candidate = """
                {"availableDocumentTypes":["RESUME"],"resume":{"experiences":[{"experienceId":"exp-1","title":"개발","period":"2024","summary":"서버 개발","sourceRefs":[{"extractionId":11,"documentId":21,"documentType":"RESUME","pageNumber":1,"segmentId":"seg-002"}]}],"skills":[],"roles":[],"results":[]},"coverLetter":null,"portfolio":null,"experienceNote":null,"missingEvidence":[]}
                """;

        var result = processor.parseCandidateMaterial(candidate, List.of(resume));

        assertThat(result.getResume().getExperiences().getFirst().getSourceRefs().getFirst().getEvidenceText())
                .isEqualTo("검증 가능한 이력서 근거");
    }

    @Test
    void restoresWrongSourceIdentityWhenSegmentIsUniqueInsidePartition() {
        String candidate = """
                {"availableDocumentTypes":["RESUME"],"resume":{"experiences":[{"title":"개발","period":"2024","summary":"서버 개발","sourceRefs":[{"extractionId":-1,"documentId":-1,"documentType":"RESUME","pageNumber":1,"segmentId":"seg-002"}]}],"skills":[],"roles":[],"results":[]},"coverLetter":null,"portfolio":null,"experienceNote":null,"missingEvidence":[]}
                """;

        var result = processor.parseCandidateMaterial(candidate, List.of(resume));

        assertThat(result.getResume().getExperiences().getFirst().getSourceRefs().getFirst())
                .satisfies(reference -> {
                    assertThat(reference.getExtractionId()).isEqualTo(11L);
                    assertThat(reference.getDocumentId()).isEqualTo(21L);
                });
    }

    @Test
    void dropsBlankCandidateItemInsteadOfRejectingWholePartition() {
        String candidate = """
                {"availableDocumentTypes":["RESUME"],"resume":{"experiences":[],"skills":[{"skill":"Java","usageContext":"","sourceRefs":[{"extractionId":11,"documentId":21,"documentType":"RESUME","pageNumber":1,"segmentId":"seg-002"}]}],"roles":[],"results":[]},"coverLetter":null,"portfolio":null,"experienceNote":null,"missingEvidence":[]}
                """;

        var result = processor.parseCandidateMaterial(candidate, List.of(resume));

        assertThat(result.getResume().getSkills()).isEmpty();
    }

    @Test
    void assignsDeterministicTechnicalIdsWhenJson02OmitsModelGeneratedIds() {
        String candidate = """
                {"availableDocumentTypes":["RESUME"],"resume":{"experiences":[{"title":"개발","period":null,"summary":"서버 개발","sourceRefs":[{"extractionId":11,"documentId":21,"documentType":"RESUME","pageNumber":1,"segmentId":"seg-002"}]},{"title":"운영","period":null,"summary":"서비스 운영","sourceRefs":[{"extractionId":11,"documentId":21,"documentType":"RESUME","pageNumber":1,"segmentId":"seg-002"}]}],"skills":[],"roles":[],"results":[]},"coverLetter":null,"portfolio":null,"experienceNote":null,"missingEvidence":[]}
                """;

        var result = processor.parseCandidateMaterial(candidate, List.of(resume));

        assertThat(result.getResume().getExperiences())
                .extracting(item -> item.getExperienceId())
                .containsExactly("exp-1", "exp-2");
    }

    @Test
    void restoresRequiredPrimaryAndAdditionalSourceReferencesToTheExistingArrayContract() {
        String candidate = """
                {"availableDocumentTypes":["RESUME"],"resume":{"experiences":[{"title":"개발","period":null,"summary":"서버 개발","sourceRef":{"extractionId":11,"documentId":21,"documentType":"RESUME","pageNumber":1,"segmentId":"seg-002"},"additionalSourceRefs":[{"extractionId":11,"documentId":21,"documentType":"RESUME","pageNumber":1,"segmentId":"seg-002"}]}],"skills":[],"roles":[],"results":[]},"coverLetter":null,"portfolio":null,"experienceNote":null,"missingEvidence":[]}
                """;

        var result = processor.parseCandidateMaterial(candidate, List.of(resume));

        assertThat(result.getResume().getExperiences().getFirst().getSourceRefs()).hasSize(2);
        assertThat(result.getResume().getExperiences().getFirst().getSourceRefs())
                .allSatisfy(reference -> assertThat(reference.getEvidenceText()).isEqualTo("검증 가능한 이력서 근거"));
    }

    @Test
    void rejectsCandidateReferenceInJson01AndNormalizesAvailableDocumentTypes() {
        assertFailure(() -> processor.parseJobPosting(validJobJson("11", "21", "RESUME", "1", "seg-002", "검증 가능한 이력서 근거"), List.of(resume)), AiCallLogErrorType.SOURCE_REFERENCE_INVALID);
        String candidate = """
                {"availableDocumentTypes":["JOB_POSTING"],"resume":{"experiences":[],"skills":[],"roles":[],"results":[]},"coverLetter":null,"portfolio":null,"experienceNote":null,"missingEvidence":[]}
                """;
        assertThat(processor.parseCandidateMaterial(candidate, List.of(resume)).getAvailableDocumentTypes())
                .containsExactly(UserDocumentType.RESUME);
    }

    private void assertFailure(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable, AiCallLogErrorType expected) {
        assertThatThrownBy(callable).isInstanceOf(AiProcessingException.class)
                .extracting(error -> ((AiProcessingException) error).getErrorType()).isEqualTo(expected);
    }

    private String validJobJson(String extractionId, String documentId, String documentType, String pageNumber, String segmentId, String evidenceText) {
        return """
                {"mainTasks":[{"itemId":"task-1","text":"공고 업무","sourceRefs":[{"extractionId":%s,"documentId":%s,"documentType":"%s","pageNumber":%s,"segmentId":"%s","evidenceText":"%s"}]}],"requirements":[],"preferred":[],"companyValues":[],"coreCompetencies":[],"conflicts":[],"missingEvidence":[]}
                """.formatted(extractionId, documentId, documentType, pageNumber, segmentId, evidenceText);
    }

    private AnalysisInputSnapshotContextSource source(UserDocumentType type, Long extractionId, Long documentId, String analysisText) {
        AnalysisInputSnapshotContextSource source = mock(AnalysisInputSnapshotContextSource.class);
        when(source.getDocumentType()).thenReturn(type);
        when(source.getExtractionId()).thenReturn(extractionId);
        when(source.getDocumentId()).thenReturn(documentId);
        when(source.getAnalysisText()).thenReturn(analysisText);
        return source;
    }
}
