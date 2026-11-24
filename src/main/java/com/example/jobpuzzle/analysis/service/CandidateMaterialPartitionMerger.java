package com.example.jobpuzzle.analysis.service;

import com.example.jobpuzzle.ai.dto.CandidateMaterialAnalysisResult;
import com.example.jobpuzzle.ai.dto.SourceReference;
import com.example.jobpuzzle.ai.validation.AiResponseProcessor;
import com.example.jobpuzzle.analysis.dto.AnalysisInputSnapshotContextSource;
import com.example.jobpuzzle.analysis.entity.CandidateMaterialPartition;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 검증 완료된 partition payload를 원문 순서대로 결합하고, 최종 결과를 기존 JSON-02 validator로 다시 검증한다.
@Component
public class CandidateMaterialPartitionMerger {
    private final ObjectMapper objectMapper;
    private final AiResponseProcessor responseProcessor;

    public CandidateMaterialPartitionMerger(ObjectMapper objectMapper, AiResponseProcessor responseProcessor) {
        this.objectMapper = objectMapper;
        this.responseProcessor = responseProcessor;
    }

    public CandidateMaterialAnalysisResult merge(List<CandidateMaterialPartition> partitions,
                                                  List<AnalysisInputSnapshotContextSource> allSources) {
        List<CandidateMaterialAnalysisResult> values = partitions.stream()
                .sorted(java.util.Comparator.comparingInt(CandidateMaterialPartition::getPartitionOrdinal))
                .map(this::read).toList();
        return mergeResults(values, allSources);
    }

    public CandidateMaterialAnalysisResult mergeResults(List<CandidateMaterialAnalysisResult> values,
                                                         List<AnalysisInputSnapshotContextSource> allSources) {
        CandidateMaterialAnalysisResult merged = CandidateMaterialAnalysisResult.builder()
                .availableDocumentTypes(allSources.stream().map(AnalysisInputSnapshotContextSource::getDocumentType).distinct().toList())
                .resume(resume(values)).coverLetter(coverLetter(values)).portfolio(portfolio(values))
                .experienceNote(experienceNote(values)).missingEvidence(missing(values)).build();
        try {
            // serialization 후 기존 parser를 재사용해 모든 sourceRefs가 snapshot 전체 marker에 속하는지 확정한다.
            return responseProcessor.parseCandidateMaterial(objectMapper.writeValueAsString(merged), allSources);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("partition merge serialization failed", exception);
        }
    }

    private CandidateMaterialAnalysisResult read(CandidateMaterialPartition partition) {
        try { return objectMapper.readValue(partition.getParsedResultJson(), CandidateMaterialAnalysisResult.class); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("partition payload is invalid: " + partition.getPartitionOrdinal(), exception); }
    }

    private CandidateMaterialAnalysisResult.Resume resume(List<CandidateMaterialAnalysisResult> values) {
        List<CandidateMaterialAnalysisResult.Experience> experiences = new ArrayList<>();
        List<CandidateMaterialAnalysisResult.Skill> skills = new ArrayList<>();
        List<CandidateMaterialAnalysisResult.Role> roles = new ArrayList<>();
        List<CandidateMaterialAnalysisResult.Result> results = new ArrayList<>();
        for (var value : values) if (value.getResume() != null) {
            experiences.addAll(value.getResume().getExperiences()); skills.addAll(value.getResume().getSkills());
            roles.addAll(value.getResume().getRoles()); results.addAll(value.getResume().getResults());
        }
        if (experiences.isEmpty() && skills.isEmpty() && roles.isEmpty() && results.isEmpty()
                && values.stream().noneMatch(value -> value.getResume() != null)) return null;
        List<CandidateMaterialAnalysisResult.Experience> uniqueExperiences = unique(experiences,
                value -> key(value.getTitle(), value.getPeriod(), value.getSummary(), value.getSourceRefs()));
        for (int i = 0; i < uniqueExperiences.size(); i++) uniqueExperiences.get(i).setExperienceId("exp-" + (i + 1));
        return CandidateMaterialAnalysisResult.Resume.builder().experiences(uniqueExperiences)
                .skills(unique(skills, value -> key(value.getSkill(), value.getUsageContext(), value.getSourceRefs())))
                .roles(unique(roles, value -> key(value.getRole(), value.getContext(), value.getSourceRefs())))
                .results(unique(results, value -> key(value.getResult(), value.getSourceRefs()))).build();
    }

    private CandidateMaterialAnalysisResult.CoverLetter coverLetter(List<CandidateMaterialAnalysisResult> values) {
        List<CandidateMaterialAnalysisResult.SummaryEvidence> narratives = new ArrayList<>();
        List<CandidateMaterialAnalysisResult.SummaryEvidence> motivations = new ArrayList<>(), valuesList = new ArrayList<>(), connections = new ArrayList<>();
        boolean present = false;
        for (var value : values) if (value.getCoverLetter() != null) { present = true; var cover = value.getCoverLetter();
            add(motivations, cover.getMotivation()); add(valuesList, cover.getValues()); add(connections, cover.getJobConnection());
            if (cover.getExperienceNarratives() != null) narratives.addAll(cover.getExperienceNarratives());
        }
        if (!present) return null;
        return CandidateMaterialAnalysisResult.CoverLetter.builder().motivation(combineSummary(motivations))
                .values(combineSummary(valuesList)).jobConnection(combineSummary(connections))
                .experienceNarratives(unique(narratives, value -> key(value.getSummary(), value.getSourceRefs()))).build();
    }

    private CandidateMaterialAnalysisResult.Portfolio portfolio(List<CandidateMaterialAnalysisResult> values) {
        List<CandidateMaterialAnalysisResult.Project> projects = new ArrayList<>(); boolean present = false;
        for (var value : values) if (value.getPortfolio() != null) { present = true; projects.addAll(value.getPortfolio().getProjects()); }
        if (!present) return null;
        List<CandidateMaterialAnalysisResult.Project> unique = unique(projects, value -> key(value.getProjectName(), value.getStructure(), value.getRole(), value.getSourceRefs()));
        for (int i = 0; i < unique.size(); i++) unique.get(i).setProjectId("project-" + (i + 1));
        return CandidateMaterialAnalysisResult.Portfolio.builder().projects(unique).build();
    }

    private CandidateMaterialAnalysisResult.ExperienceNote experienceNote(List<CandidateMaterialAnalysisResult> values) {
        List<CandidateMaterialAnalysisResult.StarCandidate> candidates = new ArrayList<>(); boolean present = false;
        for (var value : values) if (value.getExperienceNote() != null) { present = true; candidates.addAll(value.getExperienceNote().getStarCandidates()); }
        if (!present) return null;
        List<CandidateMaterialAnalysisResult.StarCandidate> unique = unique(candidates, value -> key(value.getSituation(), value.getTask(), value.getAction(), value.getResult(), value.getSourceRefs()));
        for (int i = 0; i < unique.size(); i++) unique.get(i).setCandidateId("star-" + (i + 1));
        return CandidateMaterialAnalysisResult.ExperienceNote.builder().starCandidates(unique).build();
    }

    private List<CandidateMaterialAnalysisResult.MissingEvidence> missing(List<CandidateMaterialAnalysisResult> values) {
        List<CandidateMaterialAnalysisResult.MissingEvidence> items = new ArrayList<>();
        values.forEach(value -> { if (value.getMissingEvidence() != null) items.addAll(value.getMissingEvidence()); });
        return unique(items, value -> key(value.getItem(), value.getReason()));
    }

    private CandidateMaterialAnalysisResult.SummaryEvidence combineSummary(List<CandidateMaterialAnalysisResult.SummaryEvidence> inputs) {
        List<CandidateMaterialAnalysisResult.SummaryEvidence> unique = unique(inputs, value -> key(value.getSummary(), value.getSourceRefs()));
        if (unique.isEmpty()) return null;
        if (unique.size() == 1) return unique.getFirst();
        List<SourceReference> refs = new ArrayList<>(); unique.forEach(value -> refs.addAll(value.getSourceRefs()));
        return CandidateMaterialAnalysisResult.SummaryEvidence.builder().summary(unique.stream().map(CandidateMaterialAnalysisResult.SummaryEvidence::getSummary)
                .reduce((left, right) -> left + "\n" + right).orElseThrow()).sourceRefs(unique(refs, this::referenceKey)).build();
    }

    private void add(List<CandidateMaterialAnalysisResult.SummaryEvidence> values, CandidateMaterialAnalysisResult.SummaryEvidence value) { if (value != null) values.add(value); }
    private String referenceKey(SourceReference value) { return key(value.getExtractionId(), value.getDocumentId(), value.getDocumentType(), value.getPageNumber(), value.getSegmentId()); }
    private String key(Object... values) { return java.util.Arrays.stream(values).map(value -> value instanceof List<?> list ? referenceListKey(list) : String.valueOf(value).trim().replaceAll("\\s+", " ").toLowerCase()).reduce("", (a,b) -> a + "|" + b); }
    private String referenceListKey(List<?> values) { return values.stream().filter(SourceReference.class::isInstance).map(SourceReference.class::cast).map(this::referenceKey).sorted().reduce("", (a,b) -> a + "|" + b); }
    private <T> List<T> unique(List<T> values, java.util.function.Function<T, String> key) { Map<String,T> result = new LinkedHashMap<>(); values.forEach(value -> result.putIfAbsent(key.apply(value), value)); return new ArrayList<>(result.values()); }
}
