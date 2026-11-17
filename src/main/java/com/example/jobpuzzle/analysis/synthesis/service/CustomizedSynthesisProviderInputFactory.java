package com.example.jobpuzzle.analysis.synthesis.service;

import com.example.jobpuzzle.ai.dto.CandidateMaterialAnalysisResult;
import com.example.jobpuzzle.ai.dto.SourceReference;
import com.example.jobpuzzle.analysis.synthesis.dto.CustomizedSynthesisEvidenceCatalog;
import com.example.jobpuzzle.analysis.synthesis.dto.CustomizedSynthesisProviderInput;
import com.example.jobpuzzle.document.entity.UserDocumentType;
import com.example.jobpuzzle.guide.dto.GuideContextResultDto;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 서버 권위 catalog와 JSON-02·04의 필요한 부분만 JSON-05 v1.3 provider 입력으로 투영한다. */
@Component
public class CustomizedSynthesisProviderInputFactory {
    private final CustomizedSynthesisProviderEvidenceProjector evidenceProjector;

    public CustomizedSynthesisProviderInputFactory(CustomizedSynthesisProviderEvidenceProjector evidenceProjector) {
        this.evidenceProjector = evidenceProjector;
    }

    public CustomizedSynthesisProviderInput create(String mainCategory, String subCategory, String careerLevel,
                                                   CandidateMaterialAnalysisResult candidate,
                                                   GuideContextResultDto guide,
                                                   CustomizedSynthesisEvidenceCatalog authority) {
        if (blank(mainCategory) || blank(subCategory) || blank(careerLevel)
                || candidate == null || guide == null || authority == null) {
            throw new IllegalArgumentException("JSON-05 v1.3 provider input is incomplete");
        }
        List<CustomizedSynthesisProviderInput.RequirementItem> requirements = authority.requirements().stream()
                .map(value -> new CustomizedSynthesisProviderInput.RequirementItem(
                        value.requirementId(), value.requirementType(), value.requirementText(),
                        value.postingEvidenceIds(), value.allowedCandidateEvidenceIds()))
                .toList();
        Map<SourceIdentity, List<String>> candidateEvidenceIds = candidateEvidenceIds(authority);
        // v2(평가 명세 1장): 분석과 자료 기반 질문 생성을 나눴다. JSON-05는 분석만 하고 질문은 만들지 않는다.
        boolean questionGenerationEnabled = false;
        return new CustomizedSynthesisProviderInput(
                new CustomizedSynthesisProviderInput.JobContext(mainCategory, subCategory, careerLevel),
                requirements,
                candidateProjection(candidate, candidateEvidenceIds),
                guideProjection(guide),
                evidenceProjector.project(authority),
                new CustomizedSynthesisProviderInput.GenerationPolicy(
                        questionGenerationEnabled, questionGenerationEnabled ? 10 : 0));
    }

    private Map<SourceIdentity, List<String>> candidateEvidenceIds(CustomizedSynthesisEvidenceCatalog authority) {
        Map<SourceIdentity, List<String>> values = new LinkedHashMap<>();
        authority.evidence().stream()
                .filter(value -> value.role() == CustomizedSynthesisEvidenceCatalog.EvidenceRole.CANDIDATE)
                .forEach(value -> values.computeIfAbsent(SourceIdentity.from(value.sourceReference()), ignored -> new ArrayList<>())
                        .add(value.evidenceId()));
        return values;
    }

    private List<CustomizedSynthesisProviderInput.CandidateContextItem> candidateProjection(
            CandidateMaterialAnalysisResult candidate, Map<SourceIdentity, List<String>> evidenceIds) {
        List<CustomizedSynthesisProviderInput.CandidateContextItem> values = new ArrayList<>();
        CandidateMaterialAnalysisResult.Resume resume = candidate.getResume();
        if (resume != null) {
            int index = 0;
            for (CandidateMaterialAnalysisResult.Experience item : list(resume.getExperiences())) {
                add(values, id(item.getExperienceId(), "experience", ++index),
                        CustomizedSynthesisProviderInput.CandidateContextType.EXPERIENCE,
                        summary(item.getTitle(), item.getPeriod(), item.getSummary()), item.getSourceRefs(), evidenceIds);
            }
            index = 0;
            for (CandidateMaterialAnalysisResult.Skill item : list(resume.getSkills())) {
                add(values, "skill-" + (++index), CustomizedSynthesisProviderInput.CandidateContextType.SKILL,
                        summary(item.getSkill(), item.getUsageContext()), item.getSourceRefs(), evidenceIds);
            }
            index = 0;
            for (CandidateMaterialAnalysisResult.Role item : list(resume.getRoles())) {
                add(values, "role-" + (++index), CustomizedSynthesisProviderInput.CandidateContextType.ROLE,
                        summary(item.getRole(), item.getContext()), item.getSourceRefs(), evidenceIds);
            }
            index = 0;
            for (CandidateMaterialAnalysisResult.Result item : list(resume.getResults())) {
                add(values, "result-" + (++index), CustomizedSynthesisProviderInput.CandidateContextType.RESULT,
                        item.getResult(), item.getSourceRefs(), evidenceIds);
            }
        }
        CandidateMaterialAnalysisResult.CoverLetter coverLetter = candidate.getCoverLetter();
        if (coverLetter != null) {
            addSummary(values, "cover-motivation", coverLetter.getMotivation(), evidenceIds);
            addSummary(values, "cover-values", coverLetter.getValues(), evidenceIds);
            int index = 0;
            for (CandidateMaterialAnalysisResult.SummaryEvidence item : list(coverLetter.getExperienceNarratives())) {
                addSummary(values, "cover-experience-" + (++index), item, evidenceIds);
            }
            addSummary(values, "cover-job-connection", coverLetter.getJobConnection(), evidenceIds);
        }
        CandidateMaterialAnalysisResult.Portfolio portfolio = candidate.getPortfolio();
        if (portfolio != null) {
            int index = 0;
            for (CandidateMaterialAnalysisResult.Project item : list(portfolio.getProjects())) {
                add(values, id(item.getProjectId(), "project", ++index),
                        CustomizedSynthesisProviderInput.CandidateContextType.PROJECT,
                        summary(item.getProjectName(), item.getStructure(), item.getRole(),
                                join(item.getContributions()), join(item.getTechUsageReasons()),
                                join(item.getProblemSolving()), join(item.getOutputs())),
                        item.getSourceRefs(), evidenceIds);
            }
        }
        CandidateMaterialAnalysisResult.ExperienceNote note = candidate.getExperienceNote();
        if (note != null) {
            int index = 0;
            for (CandidateMaterialAnalysisResult.StarCandidate item : list(note.getStarCandidates())) {
                add(values, id(item.getCandidateId(), "star", ++index),
                        CustomizedSynthesisProviderInput.CandidateContextType.STAR,
                        summary(item.getSituation(), item.getTask(), item.getAction(), item.getResult(),
                                item.getMissingParts() == null ? null
                                        : item.getMissingParts().stream().map(Enum::name)
                                        .collect(java.util.stream.Collectors.joining(" | "))),
                        item.getSourceRefs(), evidenceIds);
            }
        }
        return List.copyOf(values);
    }

    private void addSummary(List<CustomizedSynthesisProviderInput.CandidateContextItem> target, String id,
                            CandidateMaterialAnalysisResult.SummaryEvidence value,
                            Map<SourceIdentity, List<String>> evidenceIds) {
        if (value != null) add(target, id, CustomizedSynthesisProviderInput.CandidateContextType.COVER_LETTER,
                value.getSummary(), value.getSourceRefs(), evidenceIds);
    }

    private void add(List<CustomizedSynthesisProviderInput.CandidateContextItem> target, String id,
                     CustomizedSynthesisProviderInput.CandidateContextType type, String summary,
                     List<SourceReference> sourceRefs, Map<SourceIdentity, List<String>> evidenceIds) {
        List<String> selected = list(sourceRefs).stream()
                .flatMap(source -> evidenceIds.getOrDefault(SourceIdentity.from(source), List.of()).stream())
                .distinct().toList();
        if (!selected.isEmpty() && !blank(summary)) {
            target.add(new CustomizedSynthesisProviderInput.CandidateContextItem(id, type, summary, selected));
        }
    }

    private CustomizedSynthesisProviderInput.GuideProjection guideProjection(GuideContextResultDto guide) {
        return new CustomizedSynthesisProviderInput.GuideProjection(
                guide.getGuideCode(), guide.getVersion(), guide.getMatchType(), guide.getApplicableScope(),
                guide.getEvaluationFocus(), guide.getEvidenceRules(), guide.getQuestionDirection(), guide.getAvoidQuestions(),
                list(guide.getChunks()).stream().map(value -> new CustomizedSynthesisProviderInput.GuideChunk(
                        "guide-chunk-" + value.getChunkId(), value.getTitle(), value.getContent())).toList());
    }

    private String id(String value, String prefix, int index) {
        return blank(value) ? prefix + "-" + index : value;
    }

    private String summary(String... values) {
        return java.util.Arrays.stream(values).filter(value -> value != null && !value.isBlank())
                .reduce((left, right) -> left + " | " + right).orElse("");
    }

    private String join(List<String> values) {
        return list(values).stream().filter(value -> value != null && !value.isBlank())
                .reduce((left, right) -> left + " | " + right).orElse("");
    }

    private <T> List<T> list(List<T> values) {
        return values == null ? List.of() : values;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private record SourceIdentity(Long extractionId, Long documentId, UserDocumentType documentType,
                                  Integer pageNumber, String segmentId) {
        private static SourceIdentity from(SourceReference source) {
            if (source == null) return new SourceIdentity(null, null, null, null, null);
            return new SourceIdentity(source.getExtractionId(), source.getDocumentId(),
                    source.getDocumentType(), source.getPageNumber(), source.getSegmentId());
        }
    }
}
