package com.example.jobpuzzle.analysis.entity;

import com.example.jobpuzzle.ai.dto.CustomizedAnalysisGenerationResult;
import com.example.jobpuzzle.ai.dto.SourceReference;
import com.example.jobpuzzle.ai.log.AiCallLog;
import com.example.jobpuzzle.analysis.entity.AnalysisInputSnapshot;
import com.example.jobpuzzle.document.entity.UserDocumentType;
import com.example.jobpuzzle.interview.entity.*;
import com.example.jobpuzzle.jobcategory.entity.JobCategory;
import com.example.jobpuzzle.jobcategory.entity.JobCategoryCareerLevel;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CustomizedAnalysisStorageModelTest {

    @Test
    void separatesAiKeysFromDatabasePrimaryKeysAndReusesSourceReferenceStorage() {
        AnalysisInputSnapshot snapshot = mock(AnalysisInputSnapshot.class);
        AiCallLog log = mock(AiCallLog.class);
        SourceReference source = SourceReference.builder()
                .extractionId(1L).documentId(2L).documentType(UserDocumentType.JOB_POSTING)
                .pageNumber(3).segmentId("segment-1").evidenceText("근거").build();
        CustomizedAnalysisGenerationResult.RequirementMatch value = CustomizedAnalysisGenerationResult.RequirementMatch.builder()
                .matchId("match-ai-key").requirementId("requirement-1").requirementType(RequirementType.REQUIRED)
                .requirement("Java 경험").postingSourceRefs(List.of(source)).candidateSourceRefs(List.of())
                .matchLevel(MatchAnalysisResultMatchLevel.HIGH).reason("근거 있음").build();

        MatchAnalysisResult result = MatchAnalysisResult.from(snapshot, log, value);

        assertThat(result.getMatchId()).isNull();
        assertThat(result.getMatchKey()).isEqualTo("match-ai-key");
        assertThat(result.getPostingSourceRefs()).singleElement()
                .extracting(AnalysisSourceReference::getEvidenceText).isEqualTo("근거");
        assertThat(result.getCandidateSourceRefs()).isEmpty();
    }

    @Test
    void preservesReadinessEmptyLimitationsAndActionPlanAppLifecycleDefaults() {
        AnalysisInputSnapshot snapshot = mock(AnalysisInputSnapshot.class);
        AiCallLog log = mock(AiCallLog.class);
        MatchAnalysisResult match = mock(MatchAnalysisResult.class);
        ReadinessResult readiness = ReadinessResult.from(snapshot, log,
                CustomizedAnalysisGenerationResult.Readiness.builder()
                        .status(ReadinessResultStatus.PARTIAL).canGenerateQuestions(true).reason("제한 있음").build());
        ActionPlan actionPlan = ActionPlan.from(snapshot, match, log,
                CustomizedAnalysisGenerationResult.Task.builder().taskId("task-ai-key")
                        .relatedRequirementId("requirement-1").matchLevel(ActionPlanMatchLevel.LOW)
                        .missingPoint("부족함").suggestion("보완").build());

        assertThat(readiness.getLimitations()).isEmpty();
        assertThat(actionPlan.getActionPlanId()).isNull();
        assertThat(actionPlan.getTaskKey()).isEqualTo("task-ai-key");
        assertThat(actionPlan.getStatus()).isEqualTo(ActionPlanStatus.PENDING);
        assertThat(actionPlan.getDeadline()).isNull();
        assertThat(actionPlan.getCompletedAt()).isNull();
    }

    @Test
    void removesNotAnalyzableAndDoesNotKeepSessionIdOnGeneratedQuestion() {
        assertThat(java.util.Arrays.stream(ReadinessResultStatus.values()).map(Enum::name))
                .doesNotContain("NOT_ANALYZABLE");
        assertThat(java.util.Arrays.stream(InterviewQuestionType.values()).map(Enum::name))
                .contains("GENERAL").doesNotContain("BASIC");
        assertThat(java.util.Arrays.stream(InterviewQuestion.class.getDeclaredFields()).map(Field::getName))
                .doesNotContain("sessionId");
        assertThat(java.util.Arrays.stream(QuestionSet.class.getDeclaredFields()).map(Field::getName))
                .doesNotContain("interviewSession");
    }

    private CustomizedAnalysisGenerationResult.Question question(InterviewQuestionReviewStatus status) {
        return CustomizedAnalysisGenerationResult.Question.builder()
                .questionId("question-ai-key").questionType(InterviewQuestionType.GENERAL)
                .question("질문").intent("의도")
                .evaluationFocus(List.of(InterviewQuestionEvaluationFocus.specificity))
                .sourceRefs(List.of()).reviewStatus(status).build();
    }
}
