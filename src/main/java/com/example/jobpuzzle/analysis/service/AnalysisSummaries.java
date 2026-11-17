package com.example.jobpuzzle.analysis.service;

import com.example.jobpuzzle.analysis.entity.CandidateMaterialAnalysis;
import com.example.jobpuzzle.analysis.entity.JobPostingAnalysis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** v2 AI 입력(질문 생성·답변 평가)에 넣는 분석 결과 요약. 원문 근거(sourceRefs)는 토큰을 아끼려고 뺀다. */
public final class AnalysisSummaries {

    private AnalysisSummaries() {
    }

    public static List<String> requirements(JobPostingAnalysis analysis) {
        if (analysis == null) return List.of();
        List<String> texts = new ArrayList<>();
        if (analysis.getRequirements() != null) analysis.getRequirements().forEach(r -> texts.add(r.getText()));
        if (analysis.getPreferred() != null) analysis.getPreferred().forEach(r -> texts.add("(우대) " + r.getText()));
        return texts;
    }

    public static Map<String, Object> candidate(CandidateMaterialAnalysis analysis) {
        if (analysis == null) return null;
        Map<String, Object> candidate = new LinkedHashMap<>();
        if (analysis.getResumeAnalysis() != null && analysis.getResumeAnalysis().getExperiences() != null) {
            candidate.put("experiences", analysis.getResumeAnalysis().getExperiences().stream()
                    .map(e -> e.getTitle() + ": " + e.getSummary()).toList());
        }
        if (analysis.getCoverLetterAnalysis() != null && analysis.getCoverLetterAnalysis().getMotivation() != null) {
            candidate.put("motivation", analysis.getCoverLetterAnalysis().getMotivation().getSummary());
        }
        if (analysis.getPortfolioAnalysis() != null && analysis.getPortfolioAnalysis().getProjects() != null) {
            candidate.put("projects", analysis.getPortfolioAnalysis().getProjects().stream()
                    .map(p -> p.getProjectName() + " (" + p.getRole() + ")").toList());
        }
        return candidate;
    }
}
