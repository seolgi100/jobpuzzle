package com.example.jobpuzzle.analysis.synthesis.service;

import com.example.jobpuzzle.ai.dto.CustomizedAnalysisGenerationResult;
import com.example.jobpuzzle.ai.log.AiCallLogErrorType;
import com.example.jobpuzzle.ai.validation.AiProcessingException;
import com.example.jobpuzzle.analysis.entity.MatchAnalysisResultMatchLevel;
import com.example.jobpuzzle.analysis.synthesis.dto.*;
import com.example.jobpuzzle.guide.entity.GuideMatchType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class CustomizedSynthesisV18ResultAssembler {
    private final CustomizedSynthesisResultAssembler resultAssembler;

    public CustomizedSynthesisV18ResultAssembler(CustomizedSynthesisResultAssembler resultAssembler) {
        this.resultAssembler = resultAssembler;
    }

    public CustomizedAnalysisGenerationResult assemble(CustomizedSynthesisV18ProviderResult provider,
                                                        CustomizedSynthesisProviderInput input,
                                                        CustomizedSynthesisEvidenceCatalog authority,
                                                        GuideMatchType guideMatchType) {
        if (provider == null || provider.getReadiness() == null || input == null || authority == null) {
            fail("v1.8 provider result and authority are required");
        }
        Set<String> expected = authority.requirements().stream()
                .map(CustomizedSynthesisEvidenceCatalog.RequirementItem::requirementId)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        exact("decisionById", provider.getDecisionById(), expected);
        exact("narrativesById", provider.getNarrativesById(), expected);
        List<CustomizedSynthesisProviderResult.RequirementMatch> matches = new ArrayList<>();
        List<CustomizedSynthesisProviderResult.Task> tasks = new ArrayList<>();
        for (String id : expected) {
            Decision decision = decision(provider.getDecisionById().get(id));
            CustomizedSynthesisV18ProviderResult.Narrative narrative = provider.getNarrativesById().get(id);
            if (narrative == null) fail("narrativesById values must not be null");
            boolean high = decision.level() == MatchAnalysisResultMatchLevel.HIGH;
            String reason = blank(narrative.getReason())
                    ? "선택된 근거를 기준으로 요구사항 충족도를 판단했습니다."
                    : narrative.getReason();
            String missingPoint = high ? null : defaultText(
                    narrative.getMissingPoint(), "추가 경험과 구체적인 근거를 확인해 주세요.");
            String candidateEvidence = Set.of(MatchAnalysisResultMatchLevel.HIGH,
                            MatchAnalysisResultMatchLevel.MEDIUM, MatchAnalysisResultMatchLevel.LOW)
                    .contains(decision.level())
                    ? defaultText(narrative.getCandidateEvidence(),
                    "선택된 지원자 근거에서 관련 경험이 확인되었습니다.")
                    : "";
            String taskSuggestion = high ? "" : defaultText(
                    narrative.getTaskSuggestion(), "관련 경험을 역할과 결과 중심으로 정리해 주세요.");
            matches.add(CustomizedSynthesisProviderResult.RequirementMatch.builder()
                    .requirementId(id)
                    .matchLevel(decision.level())
                    .reason(reason)
                    .missingPoint(missingPoint)
                    .candidateEvidence(candidateEvidence)
                    .candidateEvidenceIds(decision.evidenceId() == null
                            ? List.of() : List.of(decision.evidenceId()))
                    .build());
            boolean applicable = !high;
            if (applicable) {
                tasks.add(CustomizedSynthesisProviderResult.Task.builder()
                        .relatedRequirementId(id)
                        .missingPoint(missingPoint)
                        .suggestion(taskSuggestion)
                        .build());
            }
        }
        List<CustomizedSynthesisProviderResult.Question> questions =
                provider.getQuestions() == null ? List.of() : provider.getQuestions().stream()
                        .map(q -> q == null ? null : CustomizedSynthesisProviderResult.Question.builder()
                                .relatedRequirementId(q.getRelatedRequirementId())
                                .questionType(q.getQuestionType())
                                .question(q.getQuestion())
                                .intent(q.getIntent())
                                .evaluationFocus(q.getEvaluationFocus() == null
                                        ? List.of() : List.of(q.getEvaluationFocus()))
                                .evidenceIds(blank(q.getEvidenceId())
                                        ? List.of() : List.of(q.getEvidenceId()))
                                .build())
                        .toList();
        if (!input.generationPolicy().questionGenerationEnabled()) {
            questions = List.of();
        }
        return resultAssembler.assemble(CustomizedSynthesisProviderResult.builder()
                        .readiness(CustomizedSynthesisProviderResult.Readiness.builder()
                                .reason(defaultText(provider.getReadiness().getReason(),
                                        "등록된 자료를 기준으로 분석 결과를 정리했습니다."))
                                .limitations(provider.getReadiness().getLimitations() == null
                                        ? List.of()
                                        : provider.getReadiness().getLimitations().stream()
                                        .filter(value -> !blank(value)).distinct().toList())
                                .build())
                        .requirementMatches(matches)
                        .questions(questions)
                        .tasks(tasks)
                        .build(),
                authority, guideMatchType);
    }

    private Decision decision(String value) {
        if ("NONE".equals(value)) return new Decision(MatchAnalysisResultMatchLevel.NONE, null);
        if ("INSUFFICIENT".equals(value)) return new Decision(MatchAnalysisResultMatchLevel.INSUFFICIENT, null);
        if (value != null) {
            String[] parts = value.split("::", 2);
            if (parts.length == 2 && !blank(parts[1])) {
                try {
                    MatchAnalysisResultMatchLevel level = MatchAnalysisResultMatchLevel.valueOf(parts[0]);
                    if (Set.of(MatchAnalysisResultMatchLevel.HIGH,
                            MatchAnalysisResultMatchLevel.MEDIUM,
                            MatchAnalysisResultMatchLevel.LOW).contains(level)) return new Decision(level, parts[1]);
                } catch (IllegalArgumentException ignored) { }
            }
        }
        fail("decisionById value is invalid");
        return null;
    }

    private void exact(String field, Map<String, ?> values, Set<String> expected) {
        if (values == null || !values.keySet().equals(expected)) {
            fail(field + " must contain every requirement exactly once and no unknown key");
        }
    }
    private boolean blank(String value) { return value == null || value.isBlank(); }
    private String defaultText(String value, String fallback) { return blank(value) ? fallback : value; }
    private void fail(String message) {
        throw new AiProcessingException(AiCallLogErrorType.RESPONSE_VALIDATION_FAILED, message);
    }
    private record Decision(MatchAnalysisResultMatchLevel level, String evidenceId) { }
}
