package com.example.jobpuzzle.analysis.synthesis.service;

import com.example.jobpuzzle.ai.dto.CustomizedAnalysisGenerationResult;
import com.example.jobpuzzle.ai.dto.SourceReference;
import com.example.jobpuzzle.ai.log.AiCallLogErrorType;
import com.example.jobpuzzle.ai.validation.AiProcessingException;
import com.example.jobpuzzle.analysis.entity.ActionPlanMatchLevel;
import com.example.jobpuzzle.analysis.entity.MatchAnalysisResultMatchLevel;
import com.example.jobpuzzle.analysis.entity.ReadinessResultStatus;
import com.example.jobpuzzle.analysis.synthesis.dto.CustomizedSynthesisEvidenceCatalog;
import com.example.jobpuzzle.analysis.synthesis.dto.CustomizedSynthesisProviderResult;
import com.example.jobpuzzle.guide.entity.GuideMatchType;
import com.example.jobpuzzle.interview.entity.InterviewQuestionReviewStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Provider 판단을 서버 권위 requirement/source/ID와 결합해 기존 저장 DTO로 완성한다. */
@Component
public class CustomizedSynthesisResultAssembler {

    public CustomizedAnalysisGenerationResult assemble(CustomizedSynthesisProviderResult provider,
                                                        CustomizedSynthesisEvidenceCatalog authority,
                                                        GuideMatchType guideMatchType) {
        if (provider == null || provider.getReadiness() == null || authority == null) {
            fail("provider result and authority catalog are required");
        }
        Map<String, CustomizedSynthesisEvidenceCatalog.RequirementItem> requirements = requirements(authority);
        Map<String, CustomizedSynthesisEvidenceCatalog.EvidenceItem> evidence = evidence(authority);
        Map<String, CustomizedAnalysisGenerationResult.RequirementMatch> matches =
                matches(provider.getRequirementMatches(), requirements, evidence);
        CustomizedAnalysisGenerationResult.Readiness readiness =
                readiness(provider.getReadiness(), requirements, authority, guideMatchType);
        List<CustomizedAnalysisGenerationResult.Question> questions =
                questions(provider.getQuestions(), readiness, requirements, evidence, matches);
        List<CustomizedAnalysisGenerationResult.Task> tasks =
                tasks(provider.getTasks(), requirements, matches);
        return CustomizedAnalysisGenerationResult.builder()
                .readiness(readiness)
                .requirementMatches(List.copyOf(matches.values()))
                .questions(questions)
                .tasks(tasks)
                .build();
    }

    private Map<String, CustomizedSynthesisEvidenceCatalog.RequirementItem> requirements(
            CustomizedSynthesisEvidenceCatalog authority) {
        Map<String, CustomizedSynthesisEvidenceCatalog.RequirementItem> values = new LinkedHashMap<>();
        for (CustomizedSynthesisEvidenceCatalog.RequirementItem item : authority.requirements()) {
            if (item == null || blank(item.requirementId()) || item.requirementType() == null
                    || blank(item.requirementText()) || values.put(item.requirementId(), item) != null) {
                fail("authority requirement catalog is invalid");
            }
        }
        return values;
    }

    private Map<String, CustomizedSynthesisEvidenceCatalog.EvidenceItem> evidence(
            CustomizedSynthesisEvidenceCatalog authority) {
        Map<String, CustomizedSynthesisEvidenceCatalog.EvidenceItem> values = new LinkedHashMap<>();
        for (CustomizedSynthesisEvidenceCatalog.EvidenceItem item : authority.evidence()) {
            if (item == null || blank(item.evidenceId()) || item.role() == null || item.sourceReference() == null
                    || values.put(item.evidenceId(), item) != null) {
                source("authority evidence catalog is invalid");
            }
        }
        return values;
    }

    private Map<String, CustomizedAnalysisGenerationResult.RequirementMatch> matches(
            List<CustomizedSynthesisProviderResult.RequirementMatch> generated,
            Map<String, CustomizedSynthesisEvidenceCatalog.RequirementItem> requirements,
            Map<String, CustomizedSynthesisEvidenceCatalog.EvidenceItem> evidence) {
        if (generated == null) fail("requirementMatches must not be null");
        Map<String, CustomizedSynthesisProviderResult.RequirementMatch> generatedByRequirement = new LinkedHashMap<>();
        for (CustomizedSynthesisProviderResult.RequirementMatch item : generated) {
            if (item == null || blank(item.getRequirementId())
                    || generatedByRequirement.put(item.getRequirementId(), item) != null) {
                fail("provider requirement IDs are invalid");
            }
        }
        if (!generatedByRequirement.keySet().equals(requirements.keySet())) {
            fail("JSON-01 requirements must be included exactly once");
        }

        Map<String, CustomizedAnalysisGenerationResult.RequirementMatch> values = new LinkedHashMap<>();
        int order = 0;
        for (CustomizedSynthesisEvidenceCatalog.RequirementItem requirement : requirements.values()) {
            CustomizedSynthesisProviderResult.RequirementMatch generatedMatch =
                    generatedByRequirement.get(requirement.requirementId());
            MatchAnalysisResultMatchLevel level = generatedMatch.getMatchLevel();
            if (level == null || blank(generatedMatch.getReason())) fail("match level and reason are required");
            boolean evidenced = Set.of(MatchAnalysisResultMatchLevel.HIGH,
                    MatchAnalysisResultMatchLevel.MEDIUM, MatchAnalysisResultMatchLevel.LOW).contains(level);
            List<SourceReference> candidateRefs = candidateRefs(
                    generatedMatch.getCandidateEvidenceIds(), requirement, evidence, evidenced);
            if (evidenced && blank(generatedMatch.getCandidateEvidence())) {
                fail("candidateEvidence is required for evidenced match");
            }
            if (!evidenced && !blank(generatedMatch.getCandidateEvidence())) {
                fail("candidateEvidence is forbidden for non-evidenced match");
            }
            if (level == MatchAnalysisResultMatchLevel.HIGH && !blank(generatedMatch.getMissingPoint())) {
                fail("HIGH missingPoint must be blank");
            }
            if (level != MatchAnalysisResultMatchLevel.HIGH && blank(generatedMatch.getMissingPoint())) {
                fail("missingPoint is required for non-HIGH match");
            }
            List<SourceReference> postingRefs = requirement.postingEvidenceIds().stream()
                    .map(id -> sourceFor(id, CustomizedSynthesisEvidenceCatalog.EvidenceRole.POSTING, evidence))
                    .map(this::copy).toList();
            String matchId = "match-" + (++order);
            values.put(requirement.requirementId(), CustomizedAnalysisGenerationResult.RequirementMatch.builder()
                    .matchId(matchId).requirementId(requirement.requirementId())
                    .requirementType(requirement.requirementType()).requirement(requirement.requirementText())
                    .postingSourceRefs(postingRefs)
                    .candidateEvidence(evidenced ? generatedMatch.getCandidateEvidence() : null)
                    .candidateEvidenceIds(null)
                    .candidateSourceRefs(candidateRefs)
                    .matchLevel(level).reason(generatedMatch.getReason())
                    .missingPoint(generatedMatch.getMissingPoint()).build());
        }
        return values;
    }

    private List<SourceReference> candidateRefs(List<String> selectedIds,
                                                CustomizedSynthesisEvidenceCatalog.RequirementItem requirement,
                                                Map<String, CustomizedSynthesisEvidenceCatalog.EvidenceItem> evidence,
                                                boolean required) {
        List<String> ids = selectedIds == null ? List.of() : selectedIds;
        if (required && ids.isEmpty()) source("candidateEvidenceIds must not be empty");
        if (!required && !ids.isEmpty()) source("candidateEvidenceIds are forbidden for non-evidenced match");
        Set<String> allowed = new HashSet<>(requirement.allowedCandidateEvidenceIds());
        Set<String> unique = new HashSet<>();
        List<SourceReference> values = new ArrayList<>();
        for (String id : ids) {
            if (blank(id) || !unique.add(id) || !allowed.contains(id)) {
                source("candidate evidence ID is unknown, duplicated, or belongs to another requirement");
            }
            values.add(copy(sourceFor(id, CustomizedSynthesisEvidenceCatalog.EvidenceRole.CANDIDATE, evidence)));
        }
        return List.copyOf(values);
    }

    private CustomizedAnalysisGenerationResult.Readiness readiness(
            CustomizedSynthesisProviderResult.Readiness generated,
            Map<String, CustomizedSynthesisEvidenceCatalog.RequirementItem> requirements,
            CustomizedSynthesisEvidenceCatalog authority,
            GuideMatchType guideMatchType) {
        if (blank(generated.getReason()) || generated.getLimitations() == null) {
            fail("readiness reason and limitations are required");
        }
        boolean candidateEmpty = authority.evidence().stream()
                .noneMatch(value -> value.role() == CustomizedSynthesisEvidenceCatalog.EvidenceRole.CANDIDATE);
        ReadinessResultStatus status;
        if (requirements.isEmpty()) status = ReadinessResultStatus.POSTING_LACK;
        else if (candidateEmpty) status = ReadinessResultStatus.CANDIDATE_LACK;
        else if (guideMatchType == null || guideMatchType == GuideMatchType.NONE) status = ReadinessResultStatus.GUIDE_LACK;
        else if (Set.of(GuideMatchType.FALLBACK_PARENT_CATEGORY, GuideMatchType.FALLBACK_COMMON).contains(guideMatchType)) {
            status = ReadinessResultStatus.PARTIAL;
        } else status = ReadinessResultStatus.SUFFICIENT;
        boolean canGenerateQuestions = status == ReadinessResultStatus.SUFFICIENT
                || status == ReadinessResultStatus.PARTIAL;
        List<String> limitations = new ArrayList<>();
        for (String limitation : generated.getLimitations()) {
            if (blank(limitation)) fail("readiness limitation must not be blank");
            if (!limitations.contains(limitation)) limitations.add(limitation);
        }
        // 입력 가용성은 서버 권위값이다. 모델 문구와 별개로 구조적 부족 사유를 결정적으로 보존한다.
        if (requirements.isEmpty()) addLimitation(limitations, "채용공고 요구사항 근거가 부족합니다.");
        if (candidateEmpty) addLimitation(limitations, "지원자 근거가 부족합니다.");
        if (guideMatchType == null || guideMatchType == GuideMatchType.NONE) {
            addLimitation(limitations, "적용 가능한 직무 가이드가 부족합니다.");
        }
        return CustomizedAnalysisGenerationResult.Readiness.builder()
                .status(status).canGenerateQuestions(canGenerateQuestions)
                .reason(generated.getReason()).limitations(List.copyOf(limitations)).build();
    }

    private List<CustomizedAnalysisGenerationResult.Question> questions(
            List<CustomizedSynthesisProviderResult.Question> generated,
            CustomizedAnalysisGenerationResult.Readiness readiness,
            Map<String, CustomizedSynthesisEvidenceCatalog.RequirementItem> requirements,
            Map<String, CustomizedSynthesisEvidenceCatalog.EvidenceItem> evidence,
            Map<String, CustomizedAnalysisGenerationResult.RequirementMatch> matches) {
        List<CustomizedSynthesisProviderResult.Question> source = generated == null ? List.of() : generated;
        // v2에서 JSON-05는 질문을 만들지 않는다. 이때 canGenerateQuestions는 "자료 기반 면접을 만들 수 있는 분석"이라는 뜻으로 유지한다.
        if (!readiness.isCanGenerateQuestions() || source.isEmpty()) {
            return List.of();
        }
        List<CustomizedAnalysisGenerationResult.Question> values = new ArrayList<>();
        Set<String> questionTexts = new HashSet<>();
        for (int index = 0; index < source.size() && values.size() < 10; index++) {
            CustomizedSynthesisProviderResult.Question item = source.get(index);
            if (item == null || item.getQuestionType() == null || blank(item.getQuestion())
                    || blank(item.getIntent()) || item.getEvaluationFocus() == null
                    || item.getEvaluationFocus().isEmpty() || temporaryQuestion(item.getQuestion())) continue;
            CustomizedSynthesisEvidenceCatalog.RequirementItem requirement =
                    blank(item.getRelatedRequirementId()) ? null : requirements.get(item.getRelatedRequirementId());
            if (!blank(item.getRelatedRequirementId()) && requirement == null) continue;
            if (requirement != null && (nonInterviewRequirement(requirement.requirementText())
                    || requirement.allowedCandidateEvidenceIds().isEmpty())) continue;
            String normalizedQuestion = item.getQuestion().replaceAll("\\s+", " ").trim().toLowerCase();
            if (!questionTexts.add(normalizedQuestion)) continue;
            Set<String> allowed = new HashSet<>();
            if (requirement == null) allowed.addAll(evidence.keySet());
            else {
                allowed.addAll(requirement.postingEvidenceIds());
                allowed.addAll(requirement.allowedCandidateEvidenceIds());
            }
            // 질문 문구는 모델 판단을 유지하되 근거 연결은 서버 권위 catalog로 정규화한다.
            // unknown ID는 저장하지 않고, 관련 요구사항의 실제 공고/지원자 근거를 보충한다.
            List<String> selectedIds = new ArrayList<>();
            if (item.getEvidenceIds() != null) {
                item.getEvidenceIds().stream()
                        .filter(allowed::contains)
                        .filter(value -> !selectedIds.contains(value))
                        .forEach(selectedIds::add);
            }
            if (requirement != null) {
                boolean hasPosting = selectedIds.stream().anyMatch(requirement.postingEvidenceIds()::contains);
                boolean hasCandidate = selectedIds.stream().anyMatch(requirement.allowedCandidateEvidenceIds()::contains);
                if (!hasPosting && !requirement.postingEvidenceIds().isEmpty())
                    selectedIds.add(requirement.postingEvidenceIds().get(0));
                if (!hasCandidate && !requirement.allowedCandidateEvidenceIds().isEmpty())
                    selectedIds.add(requirement.allowedCandidateEvidenceIds().get(0));
            }
            if (new HashSet<>(item.getEvaluationFocus()).size() != item.getEvaluationFocus().size()) {
                continue;
            }
            List<SourceReference> refs = selectedIds.stream()
                    .map(evidence::get)
                    .map(value -> {
                        if (value == null) {
                            source("question evidence ID is unknown");
                        }
                        return copy(value.sourceReference());
                    }).toList();
            CustomizedAnalysisGenerationResult.RequirementMatch match =
                    requirement == null ? null : matches.get(requirement.requirementId());
            values.add(CustomizedAnalysisGenerationResult.Question.builder()
                    .questionId("question-" + (values.size() + 1)).questionType(item.getQuestionType())
                    .question(item.getQuestion()).intent(item.getIntent())
                    .evaluationFocus(List.copyOf(item.getEvaluationFocus()))
                    .relatedRequirementId(requirement == null ? null : requirement.requirementId())
                    .relatedMatchId(match == null ? null : match.getMatchId())
                    .sourceRefs(refs).reviewStatus(InterviewQuestionReviewStatus.PASS).reviewNote(null).build());
        }
        if (values.isEmpty()) {
            readiness.setCanGenerateQuestions(false);
            List<String> limitations = new ArrayList<>(readiness.getLimitations());
            addLimitation(limitations, "질문 생성 결과에서 사용할 수 있는 근거 기반 질문을 확인하지 못했습니다.");
            readiness.setLimitations(List.copyOf(limitations));
        }
        return List.copyOf(values);
    }

    private boolean temporaryQuestion(String value) {
        String normalized = value.replaceAll("[\\s?!.,:;_-]+", "").toLowerCase();
        return Set.of("placeholder", "todo", "tbd", "na", "질문", "질문을입력하세요").contains(normalized);
    }

    private boolean nonInterviewRequirement(String value) {
        if (blank(value)) return true;
        String normalized = value.replaceAll("\\s+", "");
        return normalized.contains("학력무관")
                || normalized.contains("경력무관")
                || normalized.contains("성별무관")
                || normalized.contains("연령무관")
                || normalized.startsWith("우대전공:")
                || normalized.contains("관련학과전공자");
    }

    private List<CustomizedAnalysisGenerationResult.Task> tasks(
            List<CustomizedSynthesisProviderResult.Task> generated,
            Map<String, CustomizedSynthesisEvidenceCatalog.RequirementItem> requirements,
            Map<String, CustomizedAnalysisGenerationResult.RequirementMatch> matches) {
        List<CustomizedSynthesisProviderResult.Task> source = generated == null ? List.of() : generated;
        Map<String, Integer> taskCounts = new LinkedHashMap<>();
        List<CustomizedAnalysisGenerationResult.Task> values = new ArrayList<>();
        for (int index = 0; index < source.size(); index++) {
            CustomizedSynthesisProviderResult.Task item = source.get(index);
            CustomizedSynthesisEvidenceCatalog.RequirementItem requirement =
                    item == null ? null : requirements.get(item.getRelatedRequirementId());
            if (requirement == null || blank(item.getMissingPoint()) || blank(item.getSuggestion())) {
                fail("provider task is invalid");
            }
            CustomizedAnalysisGenerationResult.RequirementMatch match = matches.get(requirement.requirementId());
            if (match.getMatchLevel() == MatchAnalysisResultMatchLevel.HIGH) {
                fail("HIGH task is forbidden");
            }
            taskCounts.merge(requirement.requirementId(), 1, Integer::sum);
            values.add(CustomizedAnalysisGenerationResult.Task.builder()
                    .taskId("task-" + (index + 1)).relatedRequirementId(requirement.requirementId())
                    .relatedMatchId(match.getMatchId())
                    .matchLevel(ActionPlanMatchLevel.valueOf(match.getMatchLevel().name()))
                    .missingPoint(item.getMissingPoint()).suggestion(item.getSuggestion()).build());
        }
        for (CustomizedAnalysisGenerationResult.RequirementMatch match : matches.values()) {
            if (match.getMatchLevel() != MatchAnalysisResultMatchLevel.HIGH
                    && taskCounts.getOrDefault(match.getRequirementId(), 0) != 1) {
                fail("exactly one task is required for non-HIGH match");
            }
        }
        return List.copyOf(values);
    }

    private void addLimitation(List<String> limitations, String value) {
        if (!limitations.contains(value)) limitations.add(value);
    }

    private SourceReference sourceFor(String id, CustomizedSynthesisEvidenceCatalog.EvidenceRole role,
                                      Map<String, CustomizedSynthesisEvidenceCatalog.EvidenceItem> evidence) {
        CustomizedSynthesisEvidenceCatalog.EvidenceItem item = evidence.get(id);
        if (item == null || item.role() != role) source("evidence ID has an invalid role or is unknown");
        return item.sourceReference();
    }

    private SourceReference copy(SourceReference source) {
        return SourceReference.builder().extractionId(source.getExtractionId()).documentId(source.getDocumentId())
                .documentType(source.getDocumentType()).pageNumber(source.getPageNumber())
                .segmentId(source.getSegmentId()).evidenceText(source.getEvidenceText()).build();
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private void fail(String message) {
        throw new AiProcessingException(AiCallLogErrorType.RESPONSE_VALIDATION_FAILED, message);
    }

    private void source(String message) {
        throw new AiProcessingException(AiCallLogErrorType.SOURCE_REFERENCE_INVALID, message);
    }
}
