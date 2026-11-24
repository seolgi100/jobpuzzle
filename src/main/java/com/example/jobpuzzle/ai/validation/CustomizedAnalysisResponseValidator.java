package com.example.jobpuzzle.ai.validation;

import com.example.jobpuzzle.ai.dto.CustomizedAnalysisGenerationResult;
import com.example.jobpuzzle.ai.dto.JobPostingAnalysisResult;
import com.example.jobpuzzle.ai.dto.SourceReference;
import com.example.jobpuzzle.ai.log.AiCallLogErrorType;
import com.example.jobpuzzle.analysis.entity.MatchAnalysisResultMatchLevel;
import com.example.jobpuzzle.analysis.entity.ReadinessResultStatus;
import com.example.jobpuzzle.analysis.entity.RequirementType;
import com.example.jobpuzzle.analysis.rag.dto.RetrievedEvidenceContextDto;
import com.example.jobpuzzle.guide.entity.GuideMatchType;
import com.example.jobpuzzle.interview.entity.InterviewQuestionReviewStatus;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.Function;

// JSON-05 응답의 요구사항·준비도·질문·과제·근거 관계를 저장 전에 검증한다.
@Component
public class CustomizedAnalysisResponseValidator {

    public CustomizedAnalysisGenerationResult validate(CustomizedAnalysisValidationContext context,
                                                       CustomizedAnalysisGenerationResult result) {
        if (result == null || result.getReadiness() == null) fail("readiness is required");
        if (result.getRequirementMatches() == null) result.setRequirementMatches(new ArrayList<>());
        if (result.getQuestions() == null) result.setQuestions(new ArrayList<>());
        if (result.getTasks() == null) result.setTasks(new ArrayList<>());
        Map<String, RequirementInput> inputs = requirements(context.jobPostingAnalysis());
        Map<String, List<SourceReference>> candidateRefs = candidateRefs(context.retrievedEvidence());
        Map<String, Map<String, SourceReference>> candidateRefsById = candidateRefsById(context.retrievedEvidence());
        validateReadiness(context.guideContext(), inputs, allCandidateRefs(candidateRefs), result.getReadiness());
        Map<String, CustomizedAnalysisGenerationResult.RequirementMatch> matches = validateMatches(inputs, candidateRefs, candidateRefsById, result.getRequirementMatches());
        result.setRequirementMatches(new ArrayList<>(matches.values()));
        normalizeQuestionAvailability(result.getReadiness(), result.getQuestions());
        validateQuestions(result.getReadiness().isCanGenerateQuestions(), matches, inputs.keySet(), inputs, allCandidateRefs(candidateRefs), result.getQuestions());
        result.setTasks(addMissingTasks(matches, result.getTasks()));
        validateTasks(matches, result.getTasks());
        return result;
    }

    private void normalizeQuestionAvailability(
            CustomizedAnalysisGenerationResult.Readiness readiness,
            List<CustomizedAnalysisGenerationResult.Question> questions) {
        // v2: JSON-05는 질문을 만들지 않는다. 질문이 없어도 canGenerateQuestions(자료 기반 면접 가능 여부)는 그대로 둔다.
        if (questions == null || questions.isEmpty()) return;
    }

    // JSON-01의 REQUIRED/PREFERRED 요구사항이 JSON-05에 정확히 한 번씩 대응되는지 확인한다.
    private Map<String, CustomizedAnalysisGenerationResult.RequirementMatch> validateMatches(
            Map<String, RequirementInput> inputs, Map<String, List<SourceReference>> candidateRefs,
            Map<String, Map<String, SourceReference>> candidateRefsById,
            List<CustomizedAnalysisGenerationResult.RequirementMatch> matches) {
        Map<String, CustomizedAnalysisGenerationResult.RequirementMatch> byRequirement = new LinkedHashMap<>();
        Set<String> matchIds = new HashSet<>();
        for (CustomizedAnalysisGenerationResult.RequirementMatch match : matches) {
            requireText("matchId", match.getMatchId());
            if (!matchIds.add(match.getMatchId())) fail("duplicate matchId");
            requireText("requirementId", match.getRequirementId());
            if (byRequirement.put(match.getRequirementId(), match) != null) fail("duplicate requirementId");
            RequirementInput input = inputs.get(match.getRequirementId());
            if (input == null) fail("unknown requirementId");
            // requirementId만 AI가 선택한다. 유형·표시 문구는 JSON-01이 소유한 권위값으로 복원한다.
            match.setRequirementType(input.type());
            match.setRequirement(input.requirement());
            requireText("reason", match.getReason());
            if (match.getMatchLevel() == null) fail("matchLevel is required");
            // 공고 근거는 requirementId로 이미 권위 입력이 확정되어 있다. 모델이 내부 identity를
            // 잘못 echo해도 분석 전체를 폐기하지 않고 JSON-01 원본으로 결정적으로 복원한다.
            match.setPostingSourceRefs(copyReferences(input.sourceRefs()));
            validateCandidateEvidence(match, candidateRefs.getOrDefault(match.getRequirementId(), List.of()),
                    candidateRefsById.getOrDefault(match.getRequirementId(), Map.of()));
            boolean high = match.getMatchLevel() == MatchAnalysisResultMatchLevel.HIGH;
            if (high && !blank(match.getMissingPoint())) fail("HIGH missingPoint must be blank");
            if (!high) requireText("missingPoint", match.getMissingPoint());
        }
        if (!byRequirement.keySet().equals(inputs.keySet())) {
            List<CustomizedAnalysisGenerationResult.RequirementMatch> normalized = new ArrayList<>(matches);
            inputs.forEach((requirementId, input) -> {
                if (byRequirement.containsKey(requirementId)) return;
                CustomizedAnalysisGenerationResult.RequirementMatch missing =
                        CustomizedAnalysisGenerationResult.RequirementMatch.builder()
                                .matchId("server-missing-" + requirementId)
                                .requirementId(requirementId)
                                .requirementType(input.type())
                                .requirement(input.requirement())
                                .postingSourceRefs(copyReferences(input.sourceRefs()))
                                .candidateEvidence(null)
                                .candidateSourceRefs(List.of())
                                .matchLevel(MatchAnalysisResultMatchLevel.NONE)
                                .reason("분석 응답에서 해당 요구사항의 판단 근거를 확인하지 못했습니다.")
                                .missingPoint("관련 경험과 근거를 추가로 확인해 주세요.")
                                .build();
                normalized.add(missing);
                byRequirement.put(requirementId, missing);
            });
            matches = normalized;
        }
        return matches.stream().collect(java.util.stream.Collectors.toMap(
                CustomizedAnalysisGenerationResult.RequirementMatch::getMatchId, Function.identity(),
                (left, right) -> left, LinkedHashMap::new));
    }

    private List<CustomizedAnalysisGenerationResult.Task> addMissingTasks(
            Map<String, CustomizedAnalysisGenerationResult.RequirementMatch> matches,
            List<CustomizedAnalysisGenerationResult.Task> tasks) {
        List<CustomizedAnalysisGenerationResult.Task> normalized = new ArrayList<>(tasks);
        Set<String> covered = normalized.stream()
                .map(CustomizedAnalysisGenerationResult.Task::getRelatedMatchId)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        matches.values().stream()
                .filter(match -> match.getMatchLevel() != MatchAnalysisResultMatchLevel.HIGH)
                .filter(match -> !covered.contains(match.getMatchId()))
                .forEach(match -> normalized.add(CustomizedAnalysisGenerationResult.Task.builder()
                        .taskId("server-task-" + match.getMatchId())
                        .relatedMatchId(match.getMatchId())
                        .relatedRequirementId(match.getRequirementId())
                        .matchLevel(com.example.jobpuzzle.analysis.entity.ActionPlanMatchLevel.valueOf(match.getMatchLevel().name()))
                        .missingPoint(match.getMissingPoint())
                        .suggestion("관련 경험을 구체적인 사례와 결과 중심으로 정리해 주세요.")
                        .build()));
        return normalized;
    }

    private void validateCandidateEvidence(CustomizedAnalysisGenerationResult.RequirementMatch match, List<SourceReference> candidateRefs,
                                           Map<String, SourceReference> candidateRefsById) {
        boolean evidenceRequired = Set.of(MatchAnalysisResultMatchLevel.HIGH, MatchAnalysisResultMatchLevel.MEDIUM, MatchAnalysisResultMatchLevel.LOW)
                .contains(match.getMatchLevel());
        if (evidenceRequired) {
            requireText("candidateEvidence", match.getCandidateEvidence());
            if (match.getCandidateEvidenceIds() != null) {
                List<SourceReference> selected = new ArrayList<>();
                for (String evidenceId : match.getCandidateEvidenceIds()) {
                    SourceReference source = candidateRefsById.get(evidenceId);
                    // 모델이 잘못 만든 chunk ID는 폐기하고 아래에서 requirement 범위의 권위 근거로 복원한다.
                    if (source == null) continue;
                    if (selected.stream().noneMatch(existing -> sameIdentity(existing, source))) selected.add(source);
                }
                match.setCandidateSourceRefs(selected.isEmpty() ? copyReferences(candidateRefs) : copyReferences(selected));
            }
            match.setCandidateSourceRefs(normalizeReferences(match.getCandidateSourceRefs(), candidateRefs, true));
            if (match.getCandidateSourceRefs().isEmpty()) {
                match.setMatchLevel(MatchAnalysisResultMatchLevel.NONE);
                match.setCandidateEvidence(null);
                match.setMissingPoint("관련 경험과 근거를 추가로 확인해 주세요.");
            }
        } else if (match.getMatchLevel() == MatchAnalysisResultMatchLevel.NONE) {
            if (!blank(match.getCandidateEvidence())) fail("candidateEvidence must be blank for NONE");
            requireList("candidateSourceRefs", match.getCandidateSourceRefs());
            if (!match.getCandidateSourceRefs().isEmpty()) fail("candidateSourceRefs must be empty for NONE");
        } else {
            if (!blank(match.getCandidateEvidence()))
                fail("candidateEvidence must be blank for " + match.getMatchLevel());
            match.setCandidateSourceRefs(normalizeReferences(match.getCandidateSourceRefs(), candidateRefs, false));
        }
    }

    // readiness 상태와 질문 생성 가능 여부는 입력 부족 사유의 우선순위와 함께 검증한다.
    private void validateReadiness(com.example.jobpuzzle.guide.dto.GuideContextResultDto guide,
                                   Map<String, RequirementInput> inputs, List<SourceReference> candidateRefs,
                                   CustomizedAnalysisGenerationResult.Readiness readiness) {
        if (readiness.getStatus() == null) fail("readiness.status is required");
        if (blank(readiness.getReason())) readiness.setReason("등록된 자료를 기준으로 분석 결과를 정리했습니다.");
        if (readiness.getLimitations() == null) readiness.setLimitations(new ArrayList<>());
        readiness.getLimitations().forEach(value -> requireText("readiness.limitations", value));
        List<ReadinessResultStatus> lacks = new ArrayList<>();
        if (inputs.isEmpty()) lacks.add(ReadinessResultStatus.POSTING_LACK);
        if (candidateRefs.isEmpty()) lacks.add(ReadinessResultStatus.CANDIDATE_LACK);
        if (guide == null || guide.getMatchType() == GuideMatchType.NONE) lacks.add(ReadinessResultStatus.GUIDE_LACK);
        ReadinessResultStatus expected = lacks.isEmpty() ? null : lacks.get(0);
        if (expected != null) readiness.setStatus(expected);
        if (expected == null && guide != null && Set.of(GuideMatchType.FALLBACK_PARENT_CATEGORY, GuideMatchType.FALLBACK_COMMON).contains(guide.getMatchType())
                && readiness.getStatus() != ReadinessResultStatus.PARTIAL) readiness.setStatus(ReadinessResultStatus.PARTIAL);
        if (expected == null && guide != null && Set.of(GuideMatchType.EXACT, GuideMatchType.FALLBACK_SAME_SUBCATEGORY).contains(guide.getMatchType())
                && !Set.of(ReadinessResultStatus.SUFFICIENT, ReadinessResultStatus.PARTIAL).contains(readiness.getStatus()))
            readiness.setStatus(ReadinessResultStatus.PARTIAL);
        boolean expectedQuestions = readiness.getStatus() == ReadinessResultStatus.SUFFICIENT || readiness.getStatus() == ReadinessResultStatus.PARTIAL;
        readiness.setCanGenerateQuestions(expectedQuestions);
    }

    // 질문은 실제 requirementMatch와 JSON-01·02 근거를 연결한 PASS 결과만 허용한다.
    private void validateQuestions(boolean canGenerateQuestions,
                                   Map<String, CustomizedAnalysisGenerationResult.RequirementMatch> matches,
                                   Set<String> requirementIds, Map<String, RequirementInput> inputs,
                                   List<SourceReference> candidateRefs,
                                   List<CustomizedAnalysisGenerationResult.Question> questions) {
        if (!canGenerateQuestions) {
            if (!questions.isEmpty()) fail("questions must be empty when generation is disabled");
            return;
        }
        if (questions.isEmpty()) return; // v2 분석 결과는 질문이 없다
        if (questions.size() > 10) fail("questions must contain 1 to 10 PASS items");
        Set<String> ids = new HashSet<>();
        List<SourceReference> allRefs = new ArrayList<>();
        inputs.values().forEach(value -> allRefs.addAll(value.sourceRefs()));
        allRefs.addAll(candidateRefs);
        for (CustomizedAnalysisGenerationResult.Question question : questions) {
            question.setQuestion(normalizeQuestion(question.getQuestion()));
            question.setIntent(normalizeSentence(question.getIntent()));
            requireText("questionId", question.getQuestionId());
            if (!ids.add(question.getQuestionId())) fail("duplicate questionId");
            requireText("question", question.getQuestion());
            requireText("intent", question.getIntent());
            if (question.getQuestionType() == null) fail("questionType is required");
            requireList("evaluationFocus", question.getEvaluationFocus());
            if (question.getEvaluationFocus().isEmpty()) fail("evaluationFocus must not be empty");
            if (new HashSet<>(question.getEvaluationFocus()).size() != question.getEvaluationFocus().size())
                fail("duplicate evaluationFocus");
            if (question.getReviewStatus() != InterviewQuestionReviewStatus.PASS)
                fail("only PASS questions are allowed");
            CustomizedAnalysisGenerationResult.RequirementMatch match = null;
            if (question.getRelatedMatchId() != null) {
                match = matches.get(question.getRelatedMatchId());
                if (match == null) fail("unknown relatedMatchId");
            }
            if (question.getRelatedRequirementId() != null && !requirementIds.contains(question.getRelatedRequirementId()))
                fail("unknown relatedRequirementId");
            if (match != null && question.getRelatedRequirementId() != null && !match.getRequirementId().equals(question.getRelatedRequirementId()))
                fail("question relation mismatch");
            question.setSourceRefs(normalizeReferences(question.getSourceRefs(), allRefs, true));
        }
    }

    // 과제는 실제 연결 결과와 같은 등급·요구사항을 참조하며 HIGH에는 존재할 수 없다.
    private void validateTasks(Map<String, CustomizedAnalysisGenerationResult.RequirementMatch> matches,
                               List<CustomizedAnalysisGenerationResult.Task> tasks) {
        Set<String> ids = new HashSet<>();
        Map<String, Integer> counts = new HashMap<>();
        for (CustomizedAnalysisGenerationResult.Task task : tasks) {
            requireText("taskId", task.getTaskId());
            if (!ids.add(task.getTaskId())) fail("duplicate taskId");
            requireText("relatedMatchId", task.getRelatedMatchId());
            requireText("relatedRequirementId", task.getRelatedRequirementId());
            CustomizedAnalysisGenerationResult.RequirementMatch match = matches.get(task.getRelatedMatchId());
            if (match == null) fail("unknown task relatedMatchId");
            if (!match.getRequirementId().equals(task.getRelatedRequirementId())) fail("task requirement mismatch");
            if (task.getMatchLevel() == null || !task.getMatchLevel().name().equals(match.getMatchLevel().name()))
                fail("task matchLevel mismatch");
            if (match.getMatchLevel() == MatchAnalysisResultMatchLevel.HIGH) fail("HIGH task is forbidden");
            requireText("task.missingPoint", task.getMissingPoint());
            requireText("task.suggestion", task.getSuggestion());
            counts.merge(match.getMatchId(), 1, Integer::sum);
        }
        for (CustomizedAnalysisGenerationResult.RequirementMatch match : matches.values()) {
            if (match.getMatchLevel() != MatchAnalysisResultMatchLevel.HIGH && counts.getOrDefault(match.getMatchId(), 0) == 0)
                fail("task is required for " + match.getMatchLevel());
        }
    }

    private SourceReference uniquelyMatchingEvidence(List<SourceReference> allowed, String evidenceText) {
        String requested = normalize(evidenceText);
        if (requested.length() < 8) return null;
        List<SourceReference> matches = allowed.stream()
                .filter(candidate -> normalize(candidate.getEvidenceText()).contains(requested))
                .toList();
        return matches.size() == 1 ? matches.get(0) : null;
    }

    private Map<String, RequirementInput> requirements(JobPostingAnalysisResult result) {
        if (result == null || result.getRequirements() == null || result.getPreferred() == null)
            fail("JSON-01 requirements are required");
        Map<String, RequirementInput> values = new LinkedHashMap<>();
        result.getRequirements().forEach(value -> putRequirement(values, value, RequirementType.REQUIRED));
        result.getPreferred().forEach(value -> putRequirement(values, value, RequirementType.PREFERRED));
        return values;
    }

    private void putRequirement(Map<String, RequirementInput> values, JobPostingAnalysisResult.Requirement value, RequirementType type) {
        if (value == null || blank(value.getRequirementId()) || blank(value.getText()) || value.getSourceRefs() == null)
            fail("invalid JSON-01 requirement");
        if (values.put(value.getRequirementId(), new RequirementInput(type, value.getText(), value.getSourceRefs())) != null)
            fail("duplicate JSON-01 requirementId");
    }

    // retrieval-selected chunk만 requirement별 candidateSourceRefs의 허용 근거로 변환한다.
    private Map<String, List<SourceReference>> candidateRefs(RetrievedEvidenceContextDto evidence) {
        if (evidence == null || evidence.requirements() == null) fail("retrieved evidence is required");
        Map<String, List<SourceReference>> refs = new HashMap<>();
        for (RetrievedEvidenceContextDto.RequirementEvidence requirement : evidence.requirements()) {
            if (requirement == null || blank(requirement.requirementId()) || requirement.chunks() == null
                    || refs.containsKey(requirement.requirementId())) fail("invalid retrieved evidence");
            List<SourceReference> values = requirement.chunks().stream().map(chunk -> SourceReference.builder()
                    .extractionId(chunk.extractionId()).documentId(chunk.documentId()).documentType(chunk.documentType())
                    .pageNumber(chunk.pageStart()).segmentId(null).evidenceText(chunk.content()).build()).toList();
            refs.put(requirement.requirementId(), values);
        }
        return refs;
    }

    private String normalizeQuestion(String value) {
        String normalized = normalizeSentence(value);
        if (blank(normalized) || normalized.endsWith("?")) return normalized;
        return normalized.replaceFirst("[.!]+$", "") + "?";
    }

    private String normalizeSentence(String value) {
        return value == null ? null : value.replaceAll("\\s+", " ").trim();
    }

    private List<SourceReference> normalizeReferences(List<SourceReference> values,
                                                      List<SourceReference> allowed,
                                                      boolean required) {
        List<SourceReference> normalized = new ArrayList<>();
        if (values != null) {
            for (SourceReference value : values) {
                if (value == null) continue;
                SourceReference original = allowed.stream()
                        .filter(candidate -> sameIdentity(candidate, value))
                        .findFirst().orElse(null);
                if (original == null) original = uniquelyMatchingEvidence(allowed, value.getEvidenceText());
                SourceReference resolved = original;
                if (resolved != null && normalized.stream().noneMatch(existing -> sameIdentity(existing, resolved))) {
                    normalized.add(resolved);
                }
            }
        }
        if (required && normalized.isEmpty()) normalized.addAll(allowed);
        return copyReferences(normalized);
    }

    private Map<String, Map<String, SourceReference>> candidateRefsById(RetrievedEvidenceContextDto evidence) {
        Map<String, Map<String, SourceReference>> refs = new HashMap<>();
        for (RetrievedEvidenceContextDto.RequirementEvidence requirement : evidence.requirements()) {
            Map<String, SourceReference> values = new LinkedHashMap<>();
            for (RetrievedEvidenceContextDto.RetrievedChunk chunk : requirement.chunks()) {
                values.put("chunk-" + chunk.chunkId(), SourceReference.builder().extractionId(chunk.extractionId())
                        .documentId(chunk.documentId()).documentType(chunk.documentType()).pageNumber(chunk.pageStart())
                        .segmentId(null).evidenceText(chunk.content()).build());
            }
            refs.put(requirement.requirementId(), values);
        }
        return refs;
    }

    // readiness와 질문은 전체 retrieval 근거를 보되 requirement match는 개별 범위를 유지한다.
    private List<SourceReference> allCandidateRefs(Map<String, List<SourceReference>> values) {
        return values.values().stream().flatMap(Collection::stream).toList();
    }

    private boolean sameIdentity(SourceReference left, SourceReference right) {
        return Objects.equals(left.getExtractionId(), right.getExtractionId()) && Objects.equals(left.getDocumentId(), right.getDocumentId()) && left.getDocumentType() == right.getDocumentType() && Objects.equals(left.getPageNumber(), right.getPageNumber()) && Objects.equals(left.getSegmentId(), right.getSegmentId());
    }

    private List<SourceReference> copyReferences(List<SourceReference> values) {
        return values.stream().map(value -> SourceReference.builder()
                .extractionId(value.getExtractionId())
                .documentId(value.getDocumentId())
                .documentType(value.getDocumentType())
                .pageNumber(value.getPageNumber())
                .segmentId(value.getSegmentId())
                .evidenceText(value.getEvidenceText())
                .build()).toList();
    }

    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private void requireText(String field, String value) {
        if (blank(value)) fail(field + " must not be blank");
    }

    private void requireList(String field, List<?> value) {
        if (value == null) fail(field + " must not be null");
    }

    private void fail(String message) {
        throw new AiProcessingException(AiCallLogErrorType.RESPONSE_VALIDATION_FAILED, message);
    }

    private record RequirementInput(RequirementType type, String requirement, List<SourceReference> sourceRefs) {
    }
}
