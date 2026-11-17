package com.example.jobpuzzle.ai.client;

import com.example.jobpuzzle.ai.dto.CandidateMaterialAnalysisResult;
import com.example.jobpuzzle.ai.dto.CandidateMaterialAnalysisResult.*;
import com.example.jobpuzzle.ai.dto.CustomizedAnalysisGenerationResult;
import com.example.jobpuzzle.ai.dto.FinalReportResult;
import com.example.jobpuzzle.ai.dto.JobPostingAnalysisResult;
import com.example.jobpuzzle.ai.dto.BasicQuestionGenerationInput;
import com.example.jobpuzzle.ai.dto.InterviewQuestionGenerationResult;
import com.example.jobpuzzle.ai.dto.QuestionGenerationResult;
import com.example.jobpuzzle.ai.dto.QuestionGenerationResult.*;
import com.example.jobpuzzle.ai.dto.SourceReference;
import com.example.jobpuzzle.ai.log.AiProvider;
import com.example.jobpuzzle.ai.validation.AnalysisSourceMarkerParser;
import com.example.jobpuzzle.analysis.entity.ActionPlanMatchLevel;
import com.example.jobpuzzle.analysis.entity.MatchAnalysisResultMatchLevel;
import com.example.jobpuzzle.analysis.entity.ReadinessResultStatus;
import com.example.jobpuzzle.analysis.entity.RequirementType;
import com.example.jobpuzzle.analysis.rag.dto.CustomizedSynthesisEvidenceProjection;
import com.example.jobpuzzle.analysis.synthesis.dto.CustomizedSynthesisProviderInput;
import com.example.jobpuzzle.analysis.synthesis.dto.CustomizedSynthesisProviderResult;
import com.example.jobpuzzle.analysis.synthesis.dto.CustomizedSynthesisProviderEvidenceCatalog;
import com.example.jobpuzzle.analysis.synthesis.dto.CustomizedSynthesisV15ProviderResult;
import com.example.jobpuzzle.analysis.synthesis.dto.CustomizedSynthesisV16ProviderResult;
import com.example.jobpuzzle.analysis.synthesis.dto.CustomizedSynthesisV17ProviderResult;
import com.example.jobpuzzle.analysis.synthesis.dto.CustomizedSynthesisV18ProviderResult;
import com.example.jobpuzzle.guide.entity.GuideMatchType;
import com.example.jobpuzzle.interview.entity.InterviewQuestionReviewStatus;
import com.example.jobpuzzle.interview.entity.InterviewQuestionEvaluationFocus;
import com.example.jobpuzzle.interview.entity.InterviewQuestionType;
import com.example.jobpuzzle.jobcategory.entity.JobCategoryCareerLevel;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class MockAiClient implements AiClient {

    private final ObjectMapper objectMapper;
    private final AnalysisSourceMarkerParser markerParser;

    public MockAiClient(ObjectMapper objectMapper, AnalysisSourceMarkerParser markerParser) {
        this.objectMapper = objectMapper;
        this.markerParser = markerParser;
    }

    // Mock도 실제 Provider와 동일하게 marker 기반 원시 JSON 문자열을 반환한다.
    @Override
    public String analyzeJobPosting(String prompt) {
        List<AnalysisSourceMarkerParser.SourceMarker> markers = markerParser.parsePrompt(prompt).stream()
                .filter(marker -> marker.documentType() == com.example.jobpuzzle.document.entity.UserDocumentType.JOB_POSTING
                        || marker.documentType() == com.example.jobpuzzle.document.entity.UserDocumentType.COMPANY_INFO)
                .toList();
        List<JobPostingAnalysisResult.Item> tasks = markers.isEmpty() ? List.of() : List.of(
                JobPostingAnalysisResult.Item.builder().itemId("task-001").text(excerpt(markers.get(0))).sourceRefs(List.of(reference(markers.get(0)))).build()
        );

        AnalysisSourceMarkerParser.SourceMarker postingMarker = markers.stream()
                .filter(marker -> marker.documentType()
                        == com.example.jobpuzzle.document.entity.UserDocumentType.JOB_POSTING)
                .findFirst()
                .orElse(null);

        List<JobPostingAnalysisResult.Requirement> requirements =
                postingMarker == null
                        ? List.of()
                        : List.of(
                        JobPostingAnalysisResult.Requirement.builder()
                                .requirementId("requirement-001")
                                .text("Java 및 Spring Boot 기반 백엔드 개발 경험")
                                .sourceRefs(List.of(reference(postingMarker)))
                                .build()
                );

        List<JobPostingAnalysisResult.Requirement> preferred =
                postingMarker == null
                        ? List.of()
                        : List.of(
                        JobPostingAnalysisResult.Requirement.builder()
                                .requirementId("preferred-001")
                                .text("테스트 코드 작성 및 배포 경험")
                                .sourceRefs(List.of(reference(postingMarker)))
                                .build()
                );

        return json(JobPostingAnalysisResult.builder()
                .mainTasks(tasks)
                .requirements(requirements)
                .preferred(preferred)
                .companyValues(List.of())
                .coreCompetencies(List.of())
                .conflicts(List.of())
                .missingEvidence(List.of())
                .build());
    }

    // 선택된 지원자 자료 marker가 있을 때만 해당 문서 유형의 구조화 항목을 만든다.
    @Override
    public String analyzeCandidateMaterial(String prompt) {
        List<AnalysisSourceMarkerParser.SourceMarker> markers = markerParser.parsePrompt(prompt).stream()
                .filter(marker -> Set.of(com.example.jobpuzzle.document.entity.UserDocumentType.RESUME,
                        com.example.jobpuzzle.document.entity.UserDocumentType.COVER_LETTER,
                        com.example.jobpuzzle.document.entity.UserDocumentType.PORTFOLIO,
                        com.example.jobpuzzle.document.entity.UserDocumentType.EXPERIENCE_NOTE).contains(marker.documentType()))
                .toList();
        var types = markerParser.documentTypesInPrompt(prompt).stream()
                .filter(type -> Set.of(com.example.jobpuzzle.document.entity.UserDocumentType.RESUME,
                        com.example.jobpuzzle.document.entity.UserDocumentType.COVER_LETTER,
                        com.example.jobpuzzle.document.entity.UserDocumentType.PORTFOLIO,
                        com.example.jobpuzzle.document.entity.UserDocumentType.EXPERIENCE_NOTE).contains(type))
                .toList();
        return json(CandidateMaterialAnalysisResult.builder()
                .availableDocumentTypes(types)
                .resume(types.contains(com.example.jobpuzzle.document.entity.UserDocumentType.RESUME) ? markerOfOrEmpty(markers, com.example.jobpuzzle.document.entity.UserDocumentType.RESUME, marker -> Resume.builder()
                        .experiences(List.of(Experience.builder().experienceId("exp-001").title(excerpt(marker)).summary(excerpt(marker)).sourceRefs(List.of(reference(marker))).build()))
                        .skills(List.of()).roles(List.of()).results(List.of()).build(), Resume.builder().experiences(List.of()).skills(List.of()).roles(List.of()).results(List.of()).build()) : null)
                .coverLetter(types.contains(com.example.jobpuzzle.document.entity.UserDocumentType.COVER_LETTER) ? markerOfOrEmpty(markers, com.example.jobpuzzle.document.entity.UserDocumentType.COVER_LETTER, marker -> CoverLetter.builder()
                        .motivation(SummaryEvidence.builder().summary(excerpt(marker)).sourceRefs(List.of(reference(marker))).build())
                        .experienceNarratives(List.of()).build(), CoverLetter.builder().experienceNarratives(List.of()).build()) : null)
                .portfolio(types.contains(com.example.jobpuzzle.document.entity.UserDocumentType.PORTFOLIO) ? markerOfOrEmpty(markers, com.example.jobpuzzle.document.entity.UserDocumentType.PORTFOLIO, marker -> Portfolio.builder()
                        .projects(List.of(Project.builder().projectId("project-001").projectName(excerpt(marker)).structure(excerpt(marker)).role("작성자 역할")
                                .contributions(List.of()).techUsageReasons(List.of()).problemSolving(List.of()).outputs(List.of()).sourceRefs(List.of(reference(marker))).build())).build(), Portfolio.builder().projects(List.of()).build()) : null)
                .experienceNote(types.contains(com.example.jobpuzzle.document.entity.UserDocumentType.EXPERIENCE_NOTE) ? markerOfOrEmpty(markers, com.example.jobpuzzle.document.entity.UserDocumentType.EXPERIENCE_NOTE, marker -> ExperienceNote.builder()
                        .starCandidates(List.of(StarCandidate.builder().candidateId("star-001").situation(excerpt(marker)).missingParts(List.of()).sourceRefs(List.of(reference(marker))).build())).build(), ExperienceNote.builder().starCandidates(List.of()).build()) : null)
                .missingEvidence(List.of()).build());
    }

    // 렌더링된 JSON-05 입력 구역을 읽어 검증 가능한 결정적 원시 JSON을 생성한다.
    @Override
    public String generateCustomizedAnalysis(String renderedPrompt) {
        JobPostingAnalysisResult jobPosting = readSection(renderedPrompt, "JOB_POSTING_ANALYSIS", JobPostingAnalysisResult.class);
        readSection(renderedPrompt, "CANDIDATE_MATERIAL_ANALYSIS", CandidateMaterialAnalysisResult.class);
        JsonNode guide = readSectionTree(renderedPrompt, "GUIDE_CONTEXT");
        CustomizedSynthesisEvidenceProjection evidence = readSection(renderedPrompt, "RETRIEVED_EVIDENCE", CustomizedSynthesisEvidenceProjection.class);
        Map<String, List<SourceReference>> candidateRefs = retrievedCandidateSourceRefs(evidence);
        List<RequirementInput> requirements = requirements(jobPosting);
        ReadinessResultStatus status = readiness(requirements, candidateRefs.values().stream().flatMap(List::stream).toList(), guide.path("matchType").asText());
        boolean canGenerateQuestions = status == ReadinessResultStatus.SUFFICIENT || status == ReadinessResultStatus.PARTIAL;

        List<CustomizedAnalysisGenerationResult.RequirementMatch> matches = new java.util.ArrayList<>();
        for (RequirementInput input : requirements) {
            List<SourceReference> requirementRefs = candidateRefs.getOrDefault(input.requirementId(), List.of());
            boolean hasCandidateEvidence = !requirementRefs.isEmpty();
            MatchAnalysisResultMatchLevel matchLevel = hasCandidateEvidence ? MatchAnalysisResultMatchLevel.HIGH : MatchAnalysisResultMatchLevel.NONE;
            matches.add(CustomizedAnalysisGenerationResult.RequirementMatch.builder()
                    .matchId("match-" + input.requirementId())
                    .requirementId(input.requirementId()).requirementType(input.requirementType()).requirement(input.requirement())
                    .postingSourceRefs(input.sourceRefs())
                    .candidateEvidence(hasCandidateEvidence ? requirementRefs.get(0).getEvidenceText() : null)
                    .candidateSourceRefs(hasCandidateEvidence ? List.of(requirementRefs.get(0)) : List.of())
                    .matchLevel(matchLevel).reason(hasCandidateEvidence ? "입력 지원자 근거와 연결됨" : "입력 지원자 근거를 찾지 못함")
                    .missingPoint(matchLevel == MatchAnalysisResultMatchLevel.HIGH ? null : "지원자 경험 근거 보완 필요")
                    .build());
        }

        CustomizedAnalysisGenerationResult.RequirementMatch firstMatched = matches.stream()
                .filter(match -> match.getMatchLevel() == MatchAnalysisResultMatchLevel.HIGH).findFirst().orElse(null);
        List<CustomizedAnalysisGenerationResult.Question> questions = canGenerateQuestions && firstMatched != null
                ? List.of(question(firstMatched, candidateRefs.get(firstMatched.getRequirementId()).get(0))) : List.of();
        List<CustomizedAnalysisGenerationResult.Task> tasks = matches.stream()
                .filter(match -> match.getMatchLevel() != MatchAnalysisResultMatchLevel.HIGH)
                .map(this::task).toList();
        return json(CustomizedAnalysisGenerationResult.builder()
                .readiness(CustomizedAnalysisGenerationResult.Readiness.builder().status(status).canGenerateQuestions(canGenerateQuestions)
                        .reason("입력 구조화 결과 기준 준비도").limitations(limitations(status)).build())
                .requirementMatches(matches).questions(questions).tasks(tasks).build());
    }

    @Override
    public String generateCustomizedAnalysisV13(String renderedPrompt, JsonNode outputSchema) {
        CustomizedSynthesisProviderInput input =
                readSection(renderedPrompt, "SYNTHESIS_INPUT", CustomizedSynthesisProviderInput.class);
        Map<String, String> evidenceText = input.evidenceCatalog().evidence().stream()
                .collect(java.util.stream.Collectors.toMap(
                        CustomizedSynthesisProviderEvidenceCatalog.EvidenceItem::evidenceId,
                        CustomizedSynthesisProviderEvidenceCatalog.EvidenceItem::evidenceText
                ));
        List<CustomizedSynthesisProviderResult.RequirementMatch> matches = input.requirementCatalog().stream()
                .map(requirement -> {
                    List<String> selected = requirement.allowedCandidateEvidenceIds().stream().limit(1).toList();
                    boolean evidenced = !selected.isEmpty();
                    return CustomizedSynthesisProviderResult.RequirementMatch.builder()
                            .requirementId(requirement.requirementId())
                            .matchLevel(evidenced ? MatchAnalysisResultMatchLevel.HIGH : MatchAnalysisResultMatchLevel.NONE)
                            .reason(evidenced ? "허용된 지원자 근거와 연결됨" : "허용된 지원자 근거가 없음")
                            .missingPoint(evidenced ? null : "지원자 경험 근거 보완 필요")
                            .candidateEvidence(evidenced ? evidenceText.get(selected.get(0)) : null)
                            .candidateEvidenceIds(selected)
                            .build();
                }).toList();
        CustomizedSynthesisProviderInput.RequirementItem questionRequirement = input.requirementCatalog().stream()
                .filter(value -> !value.postingEvidenceIds().isEmpty() && !value.allowedCandidateEvidenceIds().isEmpty())
                .findFirst().orElse(null);
        boolean guideAvailable = input.guideProjection() != null
                && input.guideProjection().matchType() != GuideMatchType.NONE;
        List<CustomizedSynthesisProviderResult.Question> questions =
                guideAvailable && questionRequirement != null
                        ? List.of(CustomizedSynthesisProviderResult.Question.builder()
                        .relatedRequirementId(questionRequirement.requirementId())
                        .questionType(InterviewQuestionType.COMPANY_FIT)
                        .question(questionRequirement.requirementText() + " 경험을 설명해주세요.")
                        .intent("요구사항과 지원자 근거의 연결 확인")
                        .evaluationFocus(List.of(
                                com.example.jobpuzzle.interview.entity.InterviewQuestionEvaluationFocus.requirementConnection))
                        .evidenceIds(List.of(questionRequirement.postingEvidenceIds().get(0),
                                questionRequirement.allowedCandidateEvidenceIds().get(0)))
                        .build()) : List.of();
        List<CustomizedSynthesisProviderResult.Task> tasks = matches.stream()
                .filter(value -> value.getMatchLevel() != MatchAnalysisResultMatchLevel.HIGH)
                .map(value -> CustomizedSynthesisProviderResult.Task.builder()
                        .relatedRequirementId(value.getRequirementId())
                        .missingPoint(value.getMissingPoint())
                        .suggestion("관련 경험 근거를 보완하세요.")
                        .build())
                .toList();
        return json(CustomizedSynthesisProviderResult.builder()
                .readiness(CustomizedSynthesisProviderResult.Readiness.builder()
                        .reason("입력 구조화 결과 기준 준비도")
                        .limitations(List.of())
                        .build())
                .requirementMatches(matches)
                .questions(questions)
                .tasks(tasks)
                .build());
    }

    @Override
    public String generateCustomizedAnalysisV15(String renderedPrompt, JsonNode outputSchema) {
        CustomizedSynthesisProviderInput input =
                readSection(renderedPrompt, "SYNTHESIS_INPUT", CustomizedSynthesisProviderInput.class);
        Map<String, String> evidenceText = input.evidenceCatalog().evidence().stream()
                .collect(java.util.stream.Collectors.toMap(
                        CustomizedSynthesisProviderEvidenceCatalog.EvidenceItem::evidenceId,
                        CustomizedSynthesisProviderEvidenceCatalog.EvidenceItem::evidenceText));
        Map<String, CustomizedSynthesisV15ProviderResult.MatchSlot> matches = new java.util.LinkedHashMap<>();
        Map<String, CustomizedSynthesisV15ProviderResult.TaskSlot> tasks = new java.util.LinkedHashMap<>();
        for (CustomizedSynthesisProviderInput.RequirementItem requirement : input.requirementCatalog()) {
            List<String> selected = requirement.allowedCandidateEvidenceIds().stream().limit(1).toList();
            boolean evidenced = !selected.isEmpty();
            matches.put(requirement.requirementId(), CustomizedSynthesisV15ProviderResult.MatchSlot.builder()
                    .matchLevel(evidenced ? MatchAnalysisResultMatchLevel.HIGH : MatchAnalysisResultMatchLevel.NONE)
                    .reason(evidenced ? "허용된 지원자 근거와 연결됨" : "허용된 지원자 근거가 없음")
                    .missingPoint(evidenced ? "" : "지원자 경험 근거 보완 필요")
                    .candidateEvidence(evidenced ? evidenceText.get(selected.get(0)) : "")
                    .candidateEvidenceIds(selected)
                    .build());
            tasks.put(requirement.requirementId(), CustomizedSynthesisV15ProviderResult.TaskSlot.builder()
                    .applicable(!evidenced)
                    .missingPoint(evidenced ? "" : "지원자 경험 근거 보완 필요")
                    .suggestion(evidenced ? "" : "관련 경험 근거를 보완하세요.")
                    .build());
        }
        CustomizedSynthesisV15ProviderResult.QuestionSlot question = null;
        if (input.generationPolicy().questionGenerationEnabled()) {
            CustomizedSynthesisProviderInput.RequirementItem requirement = input.requirementCatalog().stream()
                    .filter(value -> !value.postingEvidenceIds().isEmpty()
                            && !value.allowedCandidateEvidenceIds().isEmpty())
                    .findFirst().orElse(null);
            String relatedRequirementId = requirement == null ? "" : requirement.requirementId();
            List<String> questionEvidenceIds;
            String questionText;
            if (requirement != null) {
                questionEvidenceIds = List.of(requirement.postingEvidenceIds().get(0),
                        requirement.allowedCandidateEvidenceIds().get(0));
                questionText = requirement.requirementText() + " 경험을 설명해주세요.";
            } else {
                questionEvidenceIds = input.evidenceCatalog().evidence().stream()
                        .map(CustomizedSynthesisProviderEvidenceCatalog.EvidenceItem::evidenceId)
                        .limit(2).toList();
                questionText = "지원 직무와 연결되는 경험을 설명해주세요.";
            }
            question = CustomizedSynthesisV15ProviderResult.QuestionSlot.builder()
                    .relatedRequirementId(relatedRequirementId)
                    .questionType(InterviewQuestionType.COMPANY_FIT)
                    .question(questionText)
                    .intent("요구사항과 지원자 근거의 연결 확인")
                    .evaluationFocus(List.of(
                            com.example.jobpuzzle.interview.entity.InterviewQuestionEvaluationFocus.requirementConnection))
                    .evidenceIds(questionEvidenceIds)
                    .build();
        }
        return json(CustomizedSynthesisV15ProviderResult.builder()
                .readiness(CustomizedSynthesisV15ProviderResult.Readiness.builder()
                        .reason("입력 구조화 결과 기준 준비도").limitations(List.of()).build())
                .requirementMatchesById(matches)
                .primaryQuestion(question)
                .tasksByRequirementId(tasks)
                .build());
    }

    @Override
    public String generateCustomizedAnalysisV16(String renderedPrompt, JsonNode outputSchema) {
        CustomizedSynthesisProviderInput input =
                readSection(renderedPrompt, "SYNTHESIS_INPUT", CustomizedSynthesisProviderInput.class);
        Map<String, String> evidenceText = input.evidenceCatalog().evidence().stream()
                .collect(java.util.stream.Collectors.toMap(
                        CustomizedSynthesisProviderEvidenceCatalog.EvidenceItem::evidenceId,
                        CustomizedSynthesisProviderEvidenceCatalog.EvidenceItem::evidenceText));
        Map<String, MatchAnalysisResultMatchLevel> levels = new java.util.LinkedHashMap<>();
        Map<String, String> reasons = new java.util.LinkedHashMap<>();
        Map<String, String> missing = new java.util.LinkedHashMap<>();
        Map<String, String> candidateEvidence = new java.util.LinkedHashMap<>();
        Map<String, String> candidateId = new java.util.LinkedHashMap<>();
        Map<String, Boolean> applicable = new java.util.LinkedHashMap<>();
        Map<String, String> taskMissing = new java.util.LinkedHashMap<>();
        Map<String, String> suggestions = new java.util.LinkedHashMap<>();
        for (CustomizedSynthesisProviderInput.RequirementItem requirement : input.requirementCatalog()) {
            String selected = requirement.allowedCandidateEvidenceIds().stream().findFirst().orElse("");
            boolean evidenced = !selected.isBlank();
            levels.put(requirement.requirementId(),
                    evidenced ? MatchAnalysisResultMatchLevel.HIGH : MatchAnalysisResultMatchLevel.NONE);
            reasons.put(requirement.requirementId(), evidenced ? "허용된 지원자 근거와 연결됨" : "허용된 지원자 근거가 없음");
            missing.put(requirement.requirementId(), evidenced ? "" : "지원자 경험 근거 보완 필요");
            candidateEvidence.put(requirement.requirementId(), evidenced ? evidenceText.get(selected) : "");
            candidateId.put(requirement.requirementId(), selected);
            applicable.put(requirement.requirementId(), !evidenced);
            taskMissing.put(requirement.requirementId(), evidenced ? "" : "지원자 경험 근거 보완 필요");
            suggestions.put(requirement.requirementId(), evidenced ? "" : "관련 경험 근거를 보완하세요.");
        }
        CustomizedSynthesisV16ProviderResult.QuestionSlot question = null;
        if (input.generationPolicy().questionGenerationEnabled()) {
            List<String> evidenceIds = input.evidenceCatalog().evidence().stream()
                    .map(CustomizedSynthesisProviderEvidenceCatalog.EvidenceItem::evidenceId).limit(2).toList();
            question = new CustomizedSynthesisV16ProviderResult.QuestionSlot(
                    "", InterviewQuestionType.COMPANY_FIT, "지원 직무와 연결되는 경험을 설명해주세요.",
                    "지원자 근거와 직무 적합성 확인",
                    com.example.jobpuzzle.interview.entity.InterviewQuestionEvaluationFocus.requirementConnection,
                    evidenceIds.get(0));
        }
        return json(new CustomizedSynthesisV16ProviderResult(
                new CustomizedSynthesisV16ProviderResult.Readiness("입력 구조화 결과 기준 준비도", List.of()),
                levels, reasons, missing, candidateEvidence, candidateId, question,
                applicable, taskMissing, suggestions));
    }

    @Override
    public String generateCustomizedAnalysisV17(String renderedPrompt, JsonNode outputSchema) {
        CustomizedSynthesisProviderInput input =
                readSection(renderedPrompt, "SYNTHESIS_INPUT", CustomizedSynthesisProviderInput.class);
        Map<String, String> evidenceText = input.evidenceCatalog().evidence().stream()
                .collect(java.util.stream.Collectors.toMap(
                        CustomizedSynthesisProviderEvidenceCatalog.EvidenceItem::evidenceId,
                        CustomizedSynthesisProviderEvidenceCatalog.EvidenceItem::evidenceText));
        Map<String, String> decisions = new java.util.LinkedHashMap<>();
        Map<String, List<String>> narratives = new java.util.LinkedHashMap<>();
        for (CustomizedSynthesisProviderInput.RequirementItem requirement : input.requirementCatalog()) {
            String selected = requirement.allowedCandidateEvidenceIds().stream().findFirst().orElse("");
            boolean evidenced = !selected.isBlank();
            decisions.put(requirement.requirementId(), evidenced ? "HIGH::" + selected : "NONE");
            narratives.put(requirement.requirementId(), evidenced
                    ? List.of("허용된 지원자 근거와 연결됨", "", evidenceText.get(selected), "")
                    : List.of("허용된 지원자 근거가 없음", "지원자 경험 근거 보완 필요", "",
                    "관련 경험 근거를 보완하세요."));
        }
        CustomizedSynthesisV16ProviderResult.QuestionSlot question = null;
        if (input.generationPolicy().questionGenerationEnabled()) {
            String evidenceId = input.evidenceCatalog().evidence().get(0).evidenceId();
            question = new CustomizedSynthesisV16ProviderResult.QuestionSlot(
                    "", InterviewQuestionType.COMPANY_FIT, "지원 직무와 연결되는 경험을 설명해주세요.",
                    "지원자 근거와 직무 적합성 확인",
                    com.example.jobpuzzle.interview.entity.InterviewQuestionEvaluationFocus.requirementConnection,
                    evidenceId);
        }
        return json(new CustomizedSynthesisV17ProviderResult(
                new CustomizedSynthesisV16ProviderResult.Readiness("입력 구조화 결과 기준 준비도", List.of()),
                decisions, narratives, question));
    }

    @Override
    public String generateCustomizedAnalysisV18(String renderedPrompt, JsonNode outputSchema) {
        CustomizedSynthesisProviderInput input =
                readSection(renderedPrompt, "SYNTHESIS_INPUT", CustomizedSynthesisProviderInput.class);
        Map<String, String> evidenceText = input.evidenceCatalog().evidence().stream()
                .collect(java.util.stream.Collectors.toMap(
                        CustomizedSynthesisProviderEvidenceCatalog.EvidenceItem::evidenceId,
                        CustomizedSynthesisProviderEvidenceCatalog.EvidenceItem::evidenceText));
        Map<String, String> decisions = new java.util.LinkedHashMap<>();
        Map<String, CustomizedSynthesisV18ProviderResult.Narrative> narratives = new java.util.LinkedHashMap<>();
        for (CustomizedSynthesisProviderInput.RequirementItem requirement : input.requirementCatalog()) {
            String selected = requirement.allowedCandidateEvidenceIds().stream().findFirst().orElse("");
            boolean evidenced = !selected.isBlank();
            decisions.put(requirement.requirementId(), evidenced ? "HIGH::" + selected : "NONE");
            narratives.put(requirement.requirementId(), new CustomizedSynthesisV18ProviderResult.Narrative(
                    evidenced ? "허용된 지원자 근거와 연결됨" : "허용된 지원자 근거가 없음",
                    evidenced ? "" : "지원자 경험 근거 보완 필요",
                    evidenced ? evidenceText.get(selected) : "",
                    evidenced ? "" : "관련 경험 근거를 보완하세요."));
        }
        List<CustomizedSynthesisV16ProviderResult.QuestionSlot> questions =
                input.generationPolicy().questionGenerationEnabled()
                        ? input.requirementCatalog().stream()
                        .filter(requirement -> !requirement.allowedCandidateEvidenceIds().isEmpty())
                        .limit(3)
                        .map(requirement -> new CustomizedSynthesisV16ProviderResult.QuestionSlot(
                                requirement.requirementId(), InterviewQuestionType.COMPANY_FIT,
                                requirement.requirementText() + "과 연결되는 경험을 설명해주세요.",
                                "지원자 근거와 직무 적합성 확인",
                                com.example.jobpuzzle.interview.entity.InterviewQuestionEvaluationFocus.requirementConnection,
                                requirement.allowedCandidateEvidenceIds().get(0)))
                        .toList()
                        : null;
        return json(new CustomizedSynthesisV18ProviderResult(
                new CustomizedSynthesisV16ProviderResult.Readiness("입력 구조화 결과 기준 준비도", List.of()),
                decisions, narratives, questions));
    }

    private ReadinessResultStatus readiness(List<RequirementInput> requirements, List<SourceReference> candidateRefs, String guideMatchType) {
        if (requirements.isEmpty()) return ReadinessResultStatus.POSTING_LACK;
        if (candidateRefs.isEmpty()) return ReadinessResultStatus.CANDIDATE_LACK;
        if (GuideMatchType.NONE.name().equals(guideMatchType)) return ReadinessResultStatus.GUIDE_LACK;
        if (GuideMatchType.FALLBACK_PARENT_CATEGORY.name().equals(guideMatchType)
                || GuideMatchType.FALLBACK_COMMON.name().equals(guideMatchType)) return ReadinessResultStatus.PARTIAL;
        return ReadinessResultStatus.SUFFICIENT;
    }

    private List<String> limitations(ReadinessResultStatus status) {
        return status == ReadinessResultStatus.SUFFICIENT ? List.of() : List.of(status.name());
    }

    private CustomizedAnalysisGenerationResult.Question question(CustomizedAnalysisGenerationResult.RequirementMatch match, SourceReference candidateRef) {
        return CustomizedAnalysisGenerationResult.Question.builder().questionId("question-" + match.getRequirementId())
                .questionType(InterviewQuestionType.COMPANY_FIT).question(match.getRequirement() + " 경험을 설명해주세요.")
                .intent("요구사항과 지원자 근거의 연결 확인")
                .evaluationFocus(List.of(com.example.jobpuzzle.interview.entity.InterviewQuestionEvaluationFocus.requirementConnection))
                .relatedMatchId(match.getMatchId()).relatedRequirementId(match.getRequirementId())
                .sourceRefs(List.of(match.getPostingSourceRefs().get(0), candidateRef))
                .reviewStatus(InterviewQuestionReviewStatus.PASS).build();
    }

    private CustomizedAnalysisGenerationResult.Task task(CustomizedAnalysisGenerationResult.RequirementMatch match) {
        return CustomizedAnalysisGenerationResult.Task.builder().taskId("task-" + match.getRequirementId())
                .relatedMatchId(match.getMatchId()).relatedRequirementId(match.getRequirementId())
                .matchLevel(ActionPlanMatchLevel.valueOf(match.getMatchLevel().name()))
                .missingPoint(match.getMissingPoint()).suggestion("관련 경험 근거를 보완하세요.").build();
    }

    private List<RequirementInput> requirements(JobPostingAnalysisResult result) {
        List<RequirementInput> inputs = new java.util.ArrayList<>();
        result.getRequirements().forEach(value -> inputs.add(new RequirementInput(value.getRequirementId(), RequirementType.REQUIRED, value.getText(), value.getSourceRefs())));
        result.getPreferred().forEach(value -> inputs.add(new RequirementInput(value.getRequirementId(), RequirementType.PREFERRED, value.getText(), value.getSourceRefs())));
        return inputs;
    }

    private List<SourceReference> candidateSourceRefs(CandidateMaterialAnalysisResult result) {
        List<SourceReference> refs = new java.util.ArrayList<>();
        if (result.getResume() != null) {
            result.getResume().getExperiences().forEach(value -> refs.addAll(value.getSourceRefs()));
            result.getResume().getSkills().forEach(value -> refs.addAll(value.getSourceRefs()));
            result.getResume().getRoles().forEach(value -> refs.addAll(value.getSourceRefs()));
            result.getResume().getResults().forEach(value -> refs.addAll(value.getSourceRefs()));
        }
        if (result.getCoverLetter() != null) {
            addSummaryRefs(refs, result.getCoverLetter().getMotivation());
            addSummaryRefs(refs, result.getCoverLetter().getValues());
            result.getCoverLetter().getExperienceNarratives().forEach(value -> addSummaryRefs(refs, value));
            addSummaryRefs(refs, result.getCoverLetter().getJobConnection());
        }
        if (result.getPortfolio() != null)
            result.getPortfolio().getProjects().forEach(value -> refs.addAll(value.getSourceRefs()));
        if (result.getExperienceNote() != null)
            result.getExperienceNote().getStarCandidates().forEach(value -> refs.addAll(value.getSourceRefs()));
        return refs;
    }

    // projection의 requirement별 evidenceId가 가리키는 chunk만 JSON-05 candidate 근거로 echo한다.
    private Map<String, List<SourceReference>> retrievedCandidateSourceRefs(CustomizedSynthesisEvidenceProjection evidence) {
        if (evidence == null || evidence.requirements() == null) throw new IllegalArgumentException("missing mock retrieved evidence");
        Map<String, CustomizedSynthesisEvidenceProjection.CandidateEvidence> byId = new java.util.LinkedHashMap<>();
        for (CustomizedSynthesisEvidenceProjection.CandidateEvidence candidate : evidence.candidateEvidence()) {
            byId.put(candidate.evidenceId(), candidate);
        }
        Map<String, List<SourceReference>> values = new java.util.LinkedHashMap<>();
        for (CustomizedSynthesisEvidenceProjection.RequirementEvidence requirement : evidence.requirements()) {
            values.put(requirement.requirementId(), requirement.candidateEvidenceIds().stream()
                    .map(byId::get)
                    .filter(java.util.Objects::nonNull)
                    .map(chunk -> SourceReference.builder().extractionId(chunk.extractionId()).documentId(chunk.documentId())
                            .documentType(chunk.documentType()).pageNumber(chunk.pageStart()).segmentId(null)
                            .evidenceText(chunk.content()).build()).toList());
        }
        return values;
    }

    private void addSummaryRefs(List<SourceReference> refs, CandidateMaterialAnalysisResult.SummaryEvidence value) {
        if (value != null) refs.addAll(value.getSourceRefs());
    }

    private <T> T readSection(String prompt, String name, Class<T> type) {
        try {
            return objectMapper.readValue(section(prompt, name), type);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("invalid mock input section: " + name);
        }
    }

    private JsonNode readSectionTree(String prompt, String name) {
        try {
            return objectMapper.readTree(section(prompt, name));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("invalid mock input section: " + name);
        }
    }

    private String section(String prompt, String name) {
        String start = "<" + name + ">";
        String end = "</" + name + ">";
        int opening = findTagOutsideJsonString(prompt, start, 0);
        if (opening < 0) throw new IllegalArgumentException("missing mock input opening tag: " + name);
        if (findTagOutsideJsonString(prompt, end, 0) >= 0 && findTagOutsideJsonString(prompt, end, 0) < opening) {
            throw new IllegalArgumentException("mock input closing tag precedes opening tag: " + name);
        }
        if (findTagOutsideJsonString(prompt, start, opening + start.length()) >= 0) {
            throw new IllegalArgumentException("duplicate mock input opening tag: " + name);
        }
        int closing = findTagOutsideJsonString(prompt, end, opening + start.length());
        if (closing < 0) throw new IllegalArgumentException("missing mock input closing tag: " + name);
        if (findTagOutsideJsonString(prompt, end, closing + end.length()) >= 0) {
            throw new IllegalArgumentException("duplicate mock input closing tag: " + name);
        }
        return prompt.substring(opening + start.length(), closing).trim();
    }

    // JSON 문자열 안의 태그 텍스트는 무시해 실제 렌더링 구역의 경계만 찾는다.
    private int findTagOutsideJsonString(String value, String tag, int fromIndex) {
        boolean quoted = false;
        boolean escaped = false;
        for (int index = 0; index <= value.length() - tag.length(); index++) {
            char character = value.charAt(index);
            if (quoted) {
                if (escaped) escaped = false;
                else if (character == '\\') escaped = true;
                else if (character == '"') quoted = false;
                continue;
            }
            if (character == '"') {
                quoted = true;
                continue;
            }
            if (index >= fromIndex && value.startsWith(tag, index)) return index;
        }
        return -1;
    }

    private record RequirementInput(String requirementId, RequirementType requirementType, String requirement,
                                    List<SourceReference> sourceRefs) {
    }

    private <T> T markerOf(List<AnalysisSourceMarkerParser.SourceMarker> markers, com.example.jobpuzzle.document.entity.UserDocumentType type,
                           java.util.function.Function<AnalysisSourceMarkerParser.SourceMarker, T> mapper) {
        return markers.stream().filter(marker -> marker.documentType() == type).findFirst().map(mapper).orElse(null);
    }

    private <T> T markerOfOrEmpty(List<AnalysisSourceMarkerParser.SourceMarker> markers, com.example.jobpuzzle.document.entity.UserDocumentType type,
                                  java.util.function.Function<AnalysisSourceMarkerParser.SourceMarker, T> mapper, T emptyValue) {
        return markerOf(markers, type, mapper) == null ? emptyValue : markerOf(markers, type, mapper);
    }

    // interview 추가: 실제 AI 대신 JSON-11 계약에 맞춘 결정적 기본 질문 5개를 반환한다.
    @Override
    public String generateBasicQuestions(String renderedPrompt) {
        BasicQuestionGenerationInput input = readSection(
                renderedPrompt,
                "BASIC_INPUT",
                BasicQuestionGenerationInput.class
        );
        String job = input.subCategory();
        List<InterviewQuestionGenerationResult.Question> questions = List.of(
                generatedQuestion("basic-self-intro", InterviewQuestionType.SELF_INTRO,
                        job + " 직무와 연결하여 자기소개를 해주세요.",
                        "직무와 연결된 핵심 경험과 강점 확인",
                        List.of(InterviewQuestionEvaluationFocus.intentMatch,
                                InterviewQuestionEvaluationFocus.deliveryClarity)),
                generatedQuestion("basic-motivation", InterviewQuestionType.MOTIVATION,
                        job + " 직무를 선택한 이유와 지원 동기를 설명해주세요.",
                        "직무 선택 이유와 동기의 구체성 확인",
                        List.of(InterviewQuestionEvaluationFocus.intentMatch,
                                InterviewQuestionEvaluationFocus.guideAlignment)),
                generatedQuestion("basic-strength-weakness", InterviewQuestionType.STRENGTH_WEAKNESS,
                        "본인의 강점과 보완 중인 약점을 실제 경험과 함께 설명해주세요.",
                        "자기 이해와 개선 행동 확인",
                        List.of(InterviewQuestionEvaluationFocus.specificity,
                                InterviewQuestionEvaluationFocus.ownRole)),
                generatedQuestion("basic-failure-conflict", InterviewQuestionType.FAILURE_CONFLICT,
                        "실패하거나 갈등을 겪었던 경험과 해결 과정을 설명해주세요.",
                        "문제 해결 과정과 본인 역할 확인",
                        List.of(InterviewQuestionEvaluationFocus.problemSolving,
                                InterviewQuestionEvaluationFocus.resultExpression)),
                generatedQuestion("basic-job-general", InterviewQuestionType.JOB_GENERAL,
                        job + " 직무에 필요한 핵심 역량은 무엇이라고 생각하나요?",
                        "직무 이해와 준비 수준 확인",
                        List.of(InterviewQuestionEvaluationFocus.intentMatch,
                                InterviewQuestionEvaluationFocus.guideAlignment))
        );
        return json(new InterviewQuestionGenerationResult(questions));
    }

    /**
     * 평가 명세 9-2 stub: [INPUT]의 mode로 미리 쓴 응답(fixture)을 고른다.
     * 답변이 30자 미만이면 점수가 낮고 꼬리질문이 있는 응답을 돌려줘, stub에서도 꼬리질문 흐름을 볼 수 있게 한다.
     */
    @Override
    public String evaluateAnswer(String renderedPrompt) {
        JsonNode input = stubInput(renderedPrompt);
        String kind = input.path("answer").asText().strip().length() < 30 ? "low" : "normal";
        return fixture("ai-fixtures/answer-evaluation-v2/" + input.path("mode").asText() + "-" + kind + ".json");
    }

    /** 평가 명세 9-2 stub: [INPUT]의 mode로 미리 쓴 질문 생성 응답을 고른다. 태그는 모든 직무에 있는 공통 역량만 쓴다. */
    @Override
    public String generateQuestionsV2(String renderedPrompt) {
        return fixture("ai-fixtures/question-generation-v2/" + stubInput(renderedPrompt).path("mode").asText() + ".json");
    }

    private JsonNode stubInput(String renderedPrompt) {
        try {
            return objectMapper.readTree(renderedPrompt.substring(renderedPrompt.lastIndexOf("[INPUT]") + "[INPUT]".length()));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stub 입력을 읽을 수 없습니다", exception);
        }
    }

    private String fixture(String path) {
        try (var stream = new org.springframework.core.io.ClassPathResource(path).getInputStream()) {
            return new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("stub 응답을 읽을 수 없습니다: " + path, exception);
        }
    }

    private InterviewQuestionGenerationResult.Question generatedQuestion(
            String questionId,
            InterviewQuestionType questionType,
            String question,
            String intent,
            List<InterviewQuestionEvaluationFocus> evaluationFocus
    ) {
        return new InterviewQuestionGenerationResult.Question(
                questionId,
                questionType,
                question,
                intent,
                evaluationFocus,
                null,
                null,
                null,
                InterviewQuestionReviewStatus.PASS,
                null
        );
    }

    private SourceReference reference(AnalysisSourceMarkerParser.SourceMarker marker) {
        return SourceReference.builder().extractionId(marker.extractionId()).documentId(marker.documentId()).documentType(marker.documentType())
                .pageNumber(marker.pageNumber()).segmentId(marker.segmentId()).evidenceText(excerpt(marker)).build();
    }

    private String excerpt(AnalysisSourceMarkerParser.SourceMarker marker) {
        String text = marker.segmentText().trim();
        return text.length() <= 80 ? text : text.substring(0, 80);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("mock JSON serialization failed", exception);
        }
    }

    // 확정된 분석 정보를 바탕으로 적합도, 면접 질문, 보완 과제를 생성
    @Override
    public QuestionGenerationResult generateQuestions(String prompt) {
        // readiness, requirementMatches, questions, tasks를 고정값으로 반환
        return QuestionGenerationResult.builder()
                .readiness(Readiness.builder()
                        .status(ReadinessResultStatus.PARTIAL)
                        .reason("Spring Boot 개발 경험은 확인되지만 AWS 인프라 운영 경험에 대한 근거가 부족함")
                        .build())

                .requirementMatches(List.of(
                        RequirementMatch.builder()
                                .requirement("Spring Boot 기반 백엔드 개발 경험")
                                .candidateEvidence("팀 프로젝트에서 Spring Boot를 사용하여 회원가입 및 로그인 API를 구현함")
                                .matchLevel(MatchAnalysisResultMatchLevel.HIGH)
                                .reason("요구사항과 직접 연결되는 프로젝트 경험 및 담당 기능이 확인됨")
                                .missingPoint("")
                                .build(),

                        RequirementMatch.builder()
                                .requirement("AWS 인프라 운영 경험")
                                .candidateEvidence("제출 자료에서 관련 경험을 확인할 수 없음")
                                .matchLevel(MatchAnalysisResultMatchLevel.NONE)
                                .reason("AWS 배포 또는 운영 경험에 대한 구체적인 근거가 없음")
                                .missingPoint("AWS를 활용한 배포·운영 경험 및 담당 역할")
                                .build()
                ))

                .questions(List.of(
                        Question.builder()
                                .questionId("q-001")
                                .questionType(InterviewQuestionType.EXPERIENCE)
                                .question("Spring Boot로 개발한 API 중 본인이 담당한 기능과 구현 과정을 설명해주세요.")
                                .intent("지원자의 실제 담당 범위와 기술 활용 수준 확인")
                                .evaluationFocus(List.of(
                                        "본인 담당 역할",
                                        "구현 과정",
                                        "문제 해결 경험"
                                ))
                                .relatedRequirement("Spring Boot 기반 백엔드 개발 경험")
                                .reviewStatus(InterviewQuestionReviewStatus.PASS)
                                .reviewNote("")
                                .build()
                ))

                .tasks(List.of(
                        ActionPlanItem.builder()
                                .taskId("t-001")
                                .relatedRequirement("AWS 인프라 운영 경험")
                                .matchLevel(ActionPlanMatchLevel.NONE)
                                .missingPoint("AWS를 활용한 배포·운영 경험 및 담당 역할")
                                .suggestion("AWS를 활용한 배포 실습을 진행하고 사용 서비스, 배포 과정, 발생 문제와 해결 내용을 포트폴리오에 추가")
                                .build()
                ))
                .build();
    }

    @Override
    public FinalReportResult finalReport(String prompt) {
        return FinalReportResult.builder()
                .overallScore(78)
                .scoreLabel("세션 종합 기준 충족도")
                .overallAssessment("전반적으로 기준 점수를 충족했으며, 결과 표현과 문제 해결 과정에서 보완이 필요합니다.")
                .categoryScores(
                        FinalReportResult.CategoryScores.builder()
                                .intentMatch(81)
                                .requirementConnection(75)
                                .specificity(70)
                                .ownRole(85)
                                .problemSolving(68)
                                .resultExpression(62)
                                .guideAlignment(72)
                                .deliveryClarity(82).build())
                .basisSummary(
                        FinalReportResult.BasisSummary.builder()
                                .jobCategory("IT·개발 / 백엔드")
                                .careerLevel(JobCategoryCareerLevel.NEW)
                                .evaluationPassThreshold(70)
                                .originEvaluationIds(List.of())
                                .requirementConnections(List.of(
                                        FinalReportResult.RequirementConnections.builder()
                                                .requirement("Spring Boot 기반 백엔드 개발 경험")
                                                .matchLevel(MatchAnalysisResultMatchLevel.HIGH)
                                                .build(),
                                        FinalReportResult.RequirementConnections.builder()
                                                .requirement("RDB 설계 경험")
                                                .matchLevel(MatchAnalysisResultMatchLevel.MEDIUM)
                                                .build(),
                                        FinalReportResult.RequirementConnections.builder()
                                                .requirement("AWS 인프라 운영 경험")
                                                .matchLevel(MatchAnalysisResultMatchLevel.NONE)
                                                .build()
                                ))
                                .usedGuide(FinalReportResult.UsedGuide.builder()
                                        .guideId(12L)
                                        .version("v1.2")
                                        .build()
                                )
                                .missingEvidence(List.of(
                                        "AWS 인프라 운영 경험 근거 없음",
                                        "테스트 코드 작성 경험 근거 없음"
                                ))
                                .build())
                .weaknessTagSummary(List.of(
                        FinalReportResult.WeaknessTagSummary.builder()
                                .tag("RESULT_EXPRESSION_WEAK")
                                .count(4).build(),
                        FinalReportResult.WeaknessTagSummary.builder()
                                .tag("PROBLEM_SOLVING_WEAK")
                                .count(2).build()))
                .nextPracticeRecommendation(List.of(
                        FinalReportResult.NextPracticeRecommendation.builder()
                                .questionType(InterviewQuestionType.EXPERIENCE)
                                .reason("성과 표현 부족이 누적 확인됨").build(),
                        FinalReportResult.NextPracticeRecommendation.builder()
                                .questionType(InterviewQuestionType.PROBLEM_SOLVING)
                                .reason("문제 해결 과정 서술이 반복적으로 부족함").build()))
                .improvementSuggestion(
                        FinalReportResult.ImprovementSuggestion.builder()
                                .resume(List.of(
                                        "담당 API, 사용 기술, 정량적 결과를 구체적으로 추가",
                                        "AWS 관련 실습 경험을 스킬 항목에 보완"
                                ))
                                .coverLetter(List.of(
                                        "공고 요구사항과 프로젝트 경험 간 연결 서술 보강",
                                        "지원 동기에 구체적인 기술적 문제 해결 사례 추가"
                                ))
                                .portfolio(List.of(
                                        "문제 상황과 해결 과정을 별도 섹션으로 분리해 보완",
                                        "사용 기술 선택 이유를 프로젝트별로 명시"
                                ))
                                .experienceNote(List.of(
                                        "트래픽 증가 대응 경험을 STAR 구조(상황-과제-행동-결과)로 재정리",
                                        "정량적 성과(응답시간, 처리량 등) 수치 추가"
                                )).build())
                .learningDirection(List.of(
                                "AWS 배포 · 운영 실습 (EC2, S3 등) 후 포트폴리오에 사용 서비스 · 과정 · 문제해결 내용 추가",
                                "문제 해결 경험을 정량적 성과 중심으로 서술하는 연습"
                        )
                )
                .build();
    }

    // 현재 AI 클라이언트 구현체가 사용하는 제공자를 반환
    @Override
    public AiProvider getProvider() {
        return AiProvider.MOCK;
    }

    @Override
    public String getModel() {
        return "mock-fixed-sample";
    }

    @Override
    public String call(String prompt) {
        // TODO: Mock JSON 응답 반환
        return "{}";
    }
}
