package com.example.jobpuzzle.analysis.synthesis.dto;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class CustomizedSynthesisProviderResultContractTest {

    private static final Set<String> FORBIDDEN_PROVIDER_FIELDS = Set.of(
            "requirementType", "requirement", "requirementText",
            "postingSourceRefs", "candidateSourceRefs", "sourceRefs",
            "extractionId", "documentId", "documentType", "pageNumber", "segmentId", "evidenceText",
            "matchId", "questionId", "taskId", "relatedMatchId", "reviewStatus", "displayOrder");

    @Test
    void providerContractContainsNoServerOwnedIdentityOrSourceFields() {
        Set<String> fields = Set.of(
                        CustomizedSynthesisProviderResult.class,
                        CustomizedSynthesisProviderResult.Readiness.class,
                        CustomizedSynthesisProviderResult.RequirementMatch.class,
                        CustomizedSynthesisProviderResult.Question.class,
                        CustomizedSynthesisProviderResult.Task.class).stream()
                .flatMap(type -> java.util.Arrays.stream(type.getDeclaredFields()))
                .map(Field::getName)
                .collect(Collectors.toSet());

        assertThat(fields).doesNotContainAnyElementsOf(FORBIDDEN_PROVIDER_FIELDS);
    }

}
