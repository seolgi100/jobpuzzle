package com.example.jobpuzzle.analysis.dto;

import com.example.jobpuzzle.ai.dto.CandidateMaterialAnalysisResult;
import com.example.jobpuzzle.ai.dto.JobPostingAnalysisResult;
import com.example.jobpuzzle.guide.dto.GuideContextResultDto;
import lombok.Builder;
import lombok.Getter;
import java.util.List;

/** 저장된 JSON-01·02·04·05를 snapshot 기준으로 조합한 읽기 전용 응답이다. */
@Getter @Builder
public class AnalysisResultResponse {
    private Long analysisCaseId; private Long snapshotId; private String status;
    private JobCategory jobCategory; private JobPostingAnalysisResult jobPostingAnalysis;
    private CandidateMaterialAnalysisResult candidateMaterialAnalysis; private GuideContextResultDto guideContext;
    private Readiness readiness; private List<RequirementMatch> requirementMatches; private List<ActionPlan> actionPlans;
    @Getter @Builder public static class JobCategory { private String mainCategory; private String subCategory; private String careerLevel; }
    @Getter @Builder public static class Readiness { private String status; private boolean canGenerateQuestions; private String reason; private List<String> limitations; }
    @Getter @Builder public static class RequirementMatch { private Long matchId; private String requirementId; private String requirementType; private String requirement; private Object postingSourceRefs; private String candidateEvidence; private Object candidateSourceRefs; private String matchLevel; private String reason; private String missingPoint; }
    @Getter @Builder public static class ActionPlan { private Long actionPlanId; private Long relatedMatchId; private String relatedRequirementId; private String matchLevel; private String missingPoint; private String suggestion; private String status; private Object deadline; private Object completedAt; }
}
