package com.example.jobpuzzle.ai.validation;

import com.example.jobpuzzle.ai.client.MockAiClient;
import com.example.jobpuzzle.ai.dto.CandidateMaterialAnalysisResult;
import com.example.jobpuzzle.ai.dto.CustomizedAnalysisGenerationResult;
import com.example.jobpuzzle.ai.dto.JobPostingAnalysisResult;
import com.example.jobpuzzle.ai.dto.SourceReference;
import com.example.jobpuzzle.ai.log.AiCallLogErrorType;
import com.example.jobpuzzle.ai.prompt.PromptTemplate;
import com.example.jobpuzzle.ai.prompt.PromptTemplateRenderer;
import com.example.jobpuzzle.analysis.entity.MatchAnalysisResultMatchLevel;
import com.example.jobpuzzle.analysis.entity.ReadinessResultStatus;
import com.example.jobpuzzle.analysis.entity.RequirementType;
import com.example.jobpuzzle.analysis.rag.dto.RetrievedEvidenceContextDto;
import com.example.jobpuzzle.document.entity.UserDocumentType;
import com.example.jobpuzzle.guide.dto.GuideContextResultDto;
import com.example.jobpuzzle.guide.entity.GuideMatchType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CustomizedAnalysisResponseValidatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AiResponseProcessor processor = new AiResponseProcessor(objectMapper, new AnalysisSourceMarkerParser());
    private final CustomizedAnalysisResponseValidator validator = new CustomizedAnalysisResponseValidator();
    private final PromptTemplateRenderer renderer = new PromptTemplateRenderer(objectMapper);
    private final MockAiClient mock = new MockAiClient(objectMapper, new AnalysisSourceMarkerParser());

    @Test
    void latestActivationSqlRegistersTheCurrentVersionWithoutUpsert() {
        // 현재 사용하는 JSON-05 최신 배포 SQL만 읽어 버전과 중복 덮어쓰기 금지를 검증한다.
        String sql = resource("/db/manual/activate-json-05-v1.10.sql");

        assertThat(sql).contains("PT-JSON05-001", "v1.10")
                .doesNotContain("ON DUPLICATE KEY UPDATE");
    }

    @Test
    void normalizesReadinessPriorityAndQuestionRules() {
        JobPostingAnalysisResult posting = posting();
        CandidateMaterialAnalysisResult noCandidate = CandidateMaterialAnalysisResult.builder()
                .availableDocumentTypes(List.of(UserDocumentType.RESUME))
                .resume(CandidateMaterialAnalysisResult.Resume.builder().experiences(List.of()).skills(List.of()).roles(List.of()).results(List.of()).build())
                .missingEvidence(List.of()).build();
        CustomizedAnalysisGenerationResult invalid = CustomizedAnalysisGenerationResult.builder()
                .readiness(CustomizedAnalysisGenerationResult.Readiness.builder().status(ReadinessResultStatus.SUFFICIENT)
                        .canGenerateQuestions(true).reason("잘못된 상태").limitations(List.of()).build())
                .requirementMatches(List.of(match(MatchAnalysisResultMatchLevel.NONE, List.of())))
                .questions(List.of()).tasks(List.of(task("match-1", MatchAnalysisResultMatchLevel.NONE))).build();

        CustomizedAnalysisGenerationResult normalized = validator.validate(
                new CustomizedAnalysisValidationContext(posting, noCandidate, guide(GuideMatchType.NONE), emptyEvidence()), invalid);

        assertThat(normalized.getReadiness().getStatus()).isEqualTo(ReadinessResultStatus.CANDIDATE_LACK);
        assertThat(normalized.getReadiness().isCanGenerateQuestions()).isFalse();
    }

    @Test
    void acceptsNoneOnlyWhenCandidateSourceRefsIsExactlyEmpty() {
        CandidateMaterialAnalysisResult noCandidate = noCandidate();
        CustomizedAnalysisGenerationResult result = CustomizedAnalysisGenerationResult.builder()
                .readiness(CustomizedAnalysisGenerationResult.Readiness.builder().status(ReadinessResultStatus.CANDIDATE_LACK)
                        .canGenerateQuestions(false).reason("지원자 근거 없음").limitations(List.of("CANDIDATE_LACK")).build())
                .requirementMatches(List.of(match(MatchAnalysisResultMatchLevel.NONE, List.of())))
                .questions(List.of()).tasks(List.of(task("match-1", MatchAnalysisResultMatchLevel.NONE))).build();

        assertThat(validator.validate(new CustomizedAnalysisValidationContext(posting(), noCandidate, guide(GuideMatchType.EXACT), emptyEvidence()), result))
                .isSameAs(result);
    }

    @Test
    void rejectsNoneWhenCandidateSourceRefExistsAsValidationFailure() {
        CustomizedAnalysisGenerationResult result = CustomizedAnalysisGenerationResult.builder()
                .readiness(CustomizedAnalysisGenerationResult.Readiness.builder().status(ReadinessResultStatus.SUFFICIENT)
                        .canGenerateQuestions(true).reason("정상").limitations(List.of()).build())
                .requirementMatches(List.of(match(MatchAnalysisResultMatchLevel.NONE, List.of(candidateRef()))))
                .questions(List.of()).tasks(List.of(task("match-1", MatchAnalysisResultMatchLevel.NONE))).build();

        assertFailure(() -> validator.validate(new CustomizedAnalysisValidationContext(posting(), candidate(), guide(GuideMatchType.EXACT), evidence()), result),
                AiCallLogErrorType.RESPONSE_VALIDATION_FAILED);
    }

    @Test
    // v2: JSON-05는 질문을 만들지 않는다. 질문이 없어도 자료 기반 면접 가능 여부는 그대로 남는다.
    void keepsInterviewAvailabilityWhenAnalysisHasNoQuestions() {
        CustomizedAnalysisGenerationResult result = CustomizedAnalysisGenerationResult.builder()
                .readiness(CustomizedAnalysisGenerationResult.Readiness.builder()
                        .status(ReadinessResultStatus.SUFFICIENT)
                        .canGenerateQuestions(true)
                        .reason("분석 가능")
                        .limitations(List.of())
                        .build())
                .requirementMatches(List.of(match(MatchAnalysisResultMatchLevel.HIGH, List.of(candidateRef()))))
                .questions(List.of())
                .tasks(List.of())
                .build();

        CustomizedAnalysisGenerationResult normalized = validator.validate(
                new CustomizedAnalysisValidationContext(
                        posting(), candidate(), guide(GuideMatchType.EXACT), evidence()), result);

        assertThat(normalized.getReadiness().isCanGenerateQuestions()).isTrue();
        assertThat(normalized.getReadiness().getLimitations()).isEmpty();
        assertThat(normalized.getQuestions()).isEmpty();
    }

    @Test
    void restoresRequirementDisplayFieldsAndEvidenceTextFromAuthoritativeInputs() {
        CustomizedAnalysisGenerationResult.RequirementMatch generated = match(MatchAnalysisResultMatchLevel.HIGH, List.of(candidateRef()));
        generated.setRequirementType(RequirementType.PREFERRED);
        generated.setRequirement("모델이 재서술한 요구사항");
        generated.getPostingSourceRefs().get(0).setEvidenceText("모델이 재서술한 공고 근거");
        generated.getCandidateSourceRefs().get(0).setEvidenceText("모델이 재서술한 지원자 근거");
        CustomizedAnalysisGenerationResult result = CustomizedAnalysisGenerationResult.builder()
                .readiness(CustomizedAnalysisGenerationResult.Readiness.builder().status(ReadinessResultStatus.SUFFICIENT)
                        .canGenerateQuestions(true).reason("정상").limitations(List.of()).build())
                .requirementMatches(List.of(generated)).questions(List.of(question())).tasks(List.of()).build();

        CustomizedAnalysisGenerationResult validated = validator.validate(
                new CustomizedAnalysisValidationContext(posting(), candidate(), guide(GuideMatchType.EXACT), evidence()), result);

        assertThat(validated.getRequirementMatches()).singleElement().satisfies(match -> {
            assertThat(match.getRequirementType()).isEqualTo(RequirementType.REQUIRED);
            assertThat(match.getRequirement()).isEqualTo("Spring 경험");
            assertThat(match.getPostingSourceRefs()).extracting(SourceReference::getEvidenceText).containsExactly("Spring 경험 요구");
            assertThat(match.getCandidateSourceRefs()).extracting(SourceReference::getEvidenceText).containsExactly("Spring 프로젝트 경험");
        });
    }

    @Test
    void restoresCandidateSourceIdentityWhenItsUniqueEvidenceExcerptIsAuthoritative() {
        SourceReference mistypedIdentity = SourceReference.builder().extractionId(999L).documentId(999L)
                .documentType(UserDocumentType.PORTFOLIO).pageNumber(99).segmentId(null)
                .evidenceText("Spring 프로젝트 경험").build();
        CustomizedAnalysisGenerationResult.RequirementMatch generated = match(MatchAnalysisResultMatchLevel.HIGH, List.of(mistypedIdentity));
        CustomizedAnalysisGenerationResult result = CustomizedAnalysisGenerationResult.builder()
                .readiness(CustomizedAnalysisGenerationResult.Readiness.builder().status(ReadinessResultStatus.SUFFICIENT)
                        .canGenerateQuestions(true).reason("정상").limitations(List.of()).build())
                .requirementMatches(List.of(generated)).questions(List.of(question())).tasks(List.of()).build();

        CustomizedAnalysisGenerationResult validated = validator.validate(
                new CustomizedAnalysisValidationContext(posting(), candidate(), guide(GuideMatchType.EXACT), evidence()), result);

        assertThat(validated.getRequirementMatches().get(0).getCandidateSourceRefs()).singleElement().satisfies(source -> {
            assertThat(source.getExtractionId()).isEqualTo(2L);
            assertThat(source.getDocumentId()).isEqualTo(20L);
            assertThat(source.getDocumentType()).isEqualTo(UserDocumentType.RESUME);
            assertThat(source.getPageNumber()).isEqualTo(1);
        });
    }

    @Test
    void materializesCandidateSourceRefsFromRequirementScopedEvidenceIds() {
        CustomizedAnalysisGenerationResult.RequirementMatch generated = match(MatchAnalysisResultMatchLevel.HIGH, null);
        generated.setCandidateEvidenceIds(List.of("chunk-101"));
        CustomizedAnalysisGenerationResult result = CustomizedAnalysisGenerationResult.builder()
                .readiness(CustomizedAnalysisGenerationResult.Readiness.builder().status(ReadinessResultStatus.SUFFICIENT)
                        .canGenerateQuestions(true).reason("정상").limitations(List.of()).build())
                .requirementMatches(List.of(generated)).questions(List.of(question())).tasks(List.of()).build();

        CustomizedAnalysisGenerationResult validated = validator.validate(
                new CustomizedAnalysisValidationContext(posting(), candidate(), guide(GuideMatchType.EXACT), evidence()), result);

        assertThat(validated.getRequirementMatches().get(0).getCandidateSourceRefs()).singleElement().satisfies(source -> {
            assertThat(source.getExtractionId()).isEqualTo(2L);
            assertThat(source.getDocumentId()).isEqualTo(20L);
            assertThat(source.getEvidenceText()).isEqualTo("Spring 프로젝트 경험");
        });
    }

    @Test
    void replacesUnknownCandidateEvidenceIdWithRequirementScopedEvidence() {
        CustomizedAnalysisGenerationResult.RequirementMatch generated =
                match(MatchAnalysisResultMatchLevel.HIGH, null);
        generated.setCandidateEvidenceIds(List.of("model-invented-chunk"));
        CustomizedAnalysisGenerationResult result = CustomizedAnalysisGenerationResult.builder()
                .readiness(CustomizedAnalysisGenerationResult.Readiness.builder()
                        .status(ReadinessResultStatus.SUFFICIENT)
                        .canGenerateQuestions(true).reason("정상").limitations(List.of()).build())
                .requirementMatches(List.of(generated))
                .questions(List.of(question()))
                .tasks(List.of())
                .build();

        CustomizedAnalysisGenerationResult normalized = validator.validate(
                new CustomizedAnalysisValidationContext(
                        posting(), candidate(), guide(GuideMatchType.EXACT), evidence()), result);

        assertThat(normalized.getRequirementMatches().get(0).getCandidateSourceRefs())
                .singleElement()
                .satisfies(source -> {
                    assertThat(source.getExtractionId()).isEqualTo(2L);
                    assertThat(source.getDocumentId()).isEqualTo(20L);
                    assertThat(source.getEvidenceText()).isEqualTo("Spring 프로젝트 경험");
                });
    }

    @Test
    void rejectsUnknownRequirementIdEvenWhenOtherFieldsAreValid() {
        CustomizedAnalysisGenerationResult.RequirementMatch generated = match(MatchAnalysisResultMatchLevel.NONE, List.of());
        generated.setRequirementId("unknown-requirement");
        CustomizedAnalysisGenerationResult result = CustomizedAnalysisGenerationResult.builder()
                .readiness(CustomizedAnalysisGenerationResult.Readiness.builder().status(ReadinessResultStatus.CANDIDATE_LACK)
                        .canGenerateQuestions(false).reason("지원자 근거 없음").limitations(List.of()).build())
                .requirementMatches(List.of(generated)).questions(List.of()).tasks(List.of(task("match-1", MatchAnalysisResultMatchLevel.NONE))).build();

        assertFailure(() -> validator.validate(
                        new CustomizedAnalysisValidationContext(posting(), noCandidate(), guide(GuideMatchType.EXACT), emptyEvidence()), result),
                AiCallLogErrorType.RESPONSE_VALIDATION_FAILED);
    }

    @Test
    void restoresPostingSourceFromRequirementInsteadOfRejectingModelIdentity() {
        SourceReference postingRef = postingRef();
        CustomizedAnalysisGenerationResult.RequirementMatch invalidMatch = CustomizedAnalysisGenerationResult.RequirementMatch.builder()
                .matchId("match-1").requirementId("req-1").requirementType(RequirementType.REQUIRED).requirement("Spring 경험")
                .postingSourceRefs(List.of(candidateRef())).candidateEvidence("이력서 근거").candidateSourceRefs(List.of(candidateRef()))
                .matchLevel(MatchAnalysisResultMatchLevel.HIGH).reason("판단").build();
        CustomizedAnalysisGenerationResult result = CustomizedAnalysisGenerationResult.builder()
                .readiness(CustomizedAnalysisGenerationResult.Readiness.builder().status(ReadinessResultStatus.SUFFICIENT)
                        .canGenerateQuestions(true).reason("정상").limitations(List.of()).build())
                .requirementMatches(List.of(invalidMatch))
                .questions(List.of(question()))
                .tasks(List.of()).build();
        CustomizedAnalysisGenerationResult normalized = validator.validate(
                new CustomizedAnalysisValidationContext(posting(), candidate(), guide(GuideMatchType.EXACT), evidence()), result);

        assertThat(normalized.getRequirementMatches().get(0).getPostingSourceRefs()).singleElement()
                .satisfies(source -> {
                    assertThat(source.getExtractionId()).isEqualTo(1L);
                    assertThat(source.getDocumentId()).isEqualTo(10L);
                    assertThat(source.getDocumentType()).isEqualTo(UserDocumentType.JOB_POSTING);
                    assertThat(source.getSegmentId()).isEqualTo("post-1");
                });
    }

    private void assertFailure(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, AiCallLogErrorType errorType) {
        assertThatThrownBy(action).isInstanceOf(AiProcessingException.class)
                .extracting(value -> ((AiProcessingException) value).getErrorType()).isEqualTo(errorType);
    }

    // 정본 prompt와 최초 등록 SQL을 같은 방식으로 읽어 테스트가 배포 산출물을 검증하게 한다.
    private static String resource(String path) {
        try (var input = CustomizedAnalysisResponseValidatorTest.class.getResourceAsStream(path)) {
            if (input == null) throw new IllegalStateException("resource is missing: " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("resource cannot be read: " + path, exception);
        }
    }

    private JobPostingAnalysisResult posting() {
        return JobPostingAnalysisResult.builder().mainTasks(List.of()).requirements(List.of(
                JobPostingAnalysisResult.Requirement.builder().requirementId("req-1").text("Spring 경험").sourceRefs(List.of(postingRef())).build()))
                .preferred(List.of()).companyValues(List.of()).coreCompetencies(List.of()).conflicts(List.of()).missingEvidence(List.of()).build();
    }

    private CandidateMaterialAnalysisResult candidate() {
        return CandidateMaterialAnalysisResult.builder().availableDocumentTypes(List.of(UserDocumentType.RESUME))
                .resume(CandidateMaterialAnalysisResult.Resume.builder().experiences(List.of(
                        CandidateMaterialAnalysisResult.Experience.builder().experienceId("exp-1").title("Spring 프로젝트").summary("경험").sourceRefs(List.of(candidateRef())).build()))
                        .skills(List.of()).roles(List.of()).results(List.of()).build())
                .coverLetter(null).portfolio(null).experienceNote(null).missingEvidence(List.of()).build();
    }

    private CandidateMaterialAnalysisResult noCandidate() {
        return CandidateMaterialAnalysisResult.builder().availableDocumentTypes(List.of(UserDocumentType.RESUME))
                .resume(CandidateMaterialAnalysisResult.Resume.builder().experiences(List.of()).skills(List.of()).roles(List.of()).results(List.of()).build())
                .coverLetter(null).portfolio(null).experienceNote(null).missingEvidence(List.of()).build();
    }

    private GuideContextResultDto guide(GuideMatchType matchType) {
        return GuideContextResultDto.builder().matchType(matchType).mainCategory("개발").subCategory("백엔드").careerLevel("EXPERIENCED")
                .evaluationFocus(List.of()).evidenceRules(List.of()).questionDirection(List.of()).avoidQuestions(List.of()).chunks(List.of()).build();
    }

    private CustomizedAnalysisGenerationResult.RequirementMatch match(MatchAnalysisResultMatchLevel level, List<SourceReference> candidateRefs) {
        return CustomizedAnalysisGenerationResult.RequirementMatch.builder().matchId("match-1").requirementId("req-1")
                .requirementType(RequirementType.REQUIRED).requirement("Spring 경험").postingSourceRefs(List.of(postingRef()))
                .candidateEvidence(level == MatchAnalysisResultMatchLevel.HIGH ? "이력서 근거" : null).candidateSourceRefs(candidateRefs)
                .matchLevel(level).reason("판단").missingPoint(level == MatchAnalysisResultMatchLevel.HIGH ? null : "보완 필요").build();
    }

    private CustomizedAnalysisGenerationResult.Task task(String matchId, MatchAnalysisResultMatchLevel level) {
        return CustomizedAnalysisGenerationResult.Task.builder().taskId("task-1").relatedMatchId(matchId).relatedRequirementId("req-1")
                .matchLevel(com.example.jobpuzzle.analysis.entity.ActionPlanMatchLevel.valueOf(level.name())).missingPoint("보완 필요").suggestion("학습").build();
    }

    private CustomizedAnalysisGenerationResult.Question question() {
        return CustomizedAnalysisGenerationResult.Question.builder().questionId("question-1")
                .questionType(com.example.jobpuzzle.interview.entity.InterviewQuestionType.COMPANY_FIT)
                .question("Spring 경험을 설명해 주세요.").intent("요구사항 충족 여부 확인")
                .evaluationFocus(List.of(com.example.jobpuzzle.interview.entity.InterviewQuestionEvaluationFocus.requirementConnection))
                .relatedMatchId("match-1").relatedRequirementId("req-1").sourceRefs(List.of(postingRef()))
                .reviewStatus(com.example.jobpuzzle.interview.entity.InterviewQuestionReviewStatus.PASS).build();
    }

    private SourceReference postingRef() { return SourceReference.builder().extractionId(1L).documentId(10L).documentType(UserDocumentType.JOB_POSTING).pageNumber(1).segmentId("post-1").evidenceText("Spring 경험 요구").build(); }
    private SourceReference candidateRef() { return SourceReference.builder().extractionId(2L).documentId(20L).documentType(UserDocumentType.RESUME).pageNumber(1).segmentId(null).evidenceText("Spring 프로젝트 경험").build(); }
    // requirement별 선택 청크만 candidate sourceRef로 허용하는 COMPLETED retrieval fixture다.
    private RetrievedEvidenceContextDto evidence() { return new RetrievedEvidenceContextDto(List.of(new RetrievedEvidenceContextDto.RequirementEvidence("req-1", RequirementType.REQUIRED, "Spring 경험", com.example.jobpuzzle.analysis.rag.entity.RetrievalStatus.COMPLETED, List.of(new RetrievedEvidenceContextDto.RetrievedChunk(101L, 2L, 20L, UserDocumentType.RESUME, 1, 1, 0, 10, 1, 0.9d, "Spring 프로젝트 경험"))))); }
    // candidate 근거 부족 상태의 readiness 분기를 검증하는 EMPTY retrieval fixture다.
    private RetrievedEvidenceContextDto emptyEvidence() { return new RetrievedEvidenceContextDto(List.of(new RetrievedEvidenceContextDto.RequirementEvidence("req-1", RequirementType.REQUIRED, "Spring 경험", com.example.jobpuzzle.analysis.rag.entity.RetrievalStatus.EMPTY, List.of()))); }
}
