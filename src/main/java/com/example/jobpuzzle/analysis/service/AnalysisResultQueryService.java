package com.example.jobpuzzle.analysis.service;

import com.example.jobpuzzle.analysis.dto.AnalysisResultResponse;
import com.example.jobpuzzle.analysis.entity.*;
import com.example.jobpuzzle.analysis.repository.*;
import com.example.jobpuzzle.global.error.*;
import com.example.jobpuzzle.guide.dto.GuideContextResultDto;
import com.example.jobpuzzle.guide.entity.*;
import com.example.jobpuzzle.guide.repository.*;
import com.example.jobpuzzle.interview.entity.*;
import com.example.jobpuzzle.interview.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 분석 결과를 생성하거나 변경하지 않고, 소유 snapshot의 저장 결과만 응답 DTO로 변환한다.
 */
@Service
@RequiredArgsConstructor
public class AnalysisResultQueryService {
    private final AnalysisCaseRepository cases;
    private final AnalysisInputSnapshotRepository snapshots;
    private final JobPostingAnalysisRepository postings;
    private final CandidateMaterialAnalysisRepository candidates;
    private final GuideContextResultRepository guides;
    private final GuideContextChunkRepository chunks;
    private final ReadinessResultRepository readiness;
    private final MatchAnalysisResultRepository matches;
    private final ActionPlanRepository actions;
    private final CustomizedAnalysisInputMapper mapper;

    @Transactional(readOnly = true)
    public AnalysisResultResponse getResult(Long userId, Long caseId) {
        AnalysisCase c = cases.findByAnalysisCaseIdAndUser_UserId(caseId, userId).orElseThrow(() -> new CustomException(ErrorCode.ANALYSIS_CASE_NOT_FOUND));
        if (c.getStatus() != AnalysisCaseStatus.COMPLETED) throw new CustomException(ErrorCode.ANALYSIS_CASE_NOT_READY);
        AnalysisInputSnapshot s = snapshots.findByAnalysisCase_AnalysisCaseIdAndUser_UserId(caseId, userId).orElseThrow(() -> new CustomException(ErrorCode.SNAPSHOT_NOT_FOUND));
        JobPostingAnalysis p = postings.findBySnapshot_SnapshotId(s.getSnapshotId()).orElseThrow(this::integrity);
        CandidateMaterialAnalysis m = candidates.findBySnapshot_SnapshotId(s.getSnapshotId()).orElseThrow(this::integrity);
        GuideContextResult g = guides.findByPurposeAndInputReferenceTypeAndInputReferenceId(GuideContextPurpose.CUSTOMIZED_SYNTHESIS, GuideContextInputReferenceType.ANALYSIS_SNAPSHOT, String.valueOf(s.getSnapshotId())).orElseThrow(this::integrity);
        ReadinessResult r = readiness.findBySnapshot_SnapshotId(s.getSnapshotId()).orElseThrow(this::integrity);
        List<MatchAnalysisResult> ms = matches.findBySnapshot_SnapshotIdOrderByMatchIdAsc(s.getSnapshotId());
        List<ActionPlan> as = actions.findBySnapshot_SnapshotIdOrderByActionPlanIdAsc(s.getSnapshotId());
        if (ms.stream().anyMatch(x -> x.getAiCallLog() != r.getAiCallLog()) || as.stream().anyMatch(x -> x.getAiCallLog() != r.getAiCallLog()))
            throw integrity();
        return AnalysisResultResponse.builder().analysisCaseId(caseId).snapshotId(s.getSnapshotId()).status(c.getStatus().name()).jobCategory(AnalysisResultResponse.JobCategory.builder().mainCategory(s.getJobCategory().getMainCategory()).subCategory(s.getJobCategory().getSubCategory()).careerLevel(s.getJobCategory().getCareerLevel().name()).build()).jobPostingAnalysis(mapper.jobPosting(p)).candidateMaterialAnalysis(mapper.candidate(m)).guideContext(GuideContextResultDto.from(g, chunks.findByGuideContextResult_GuideContextResultIdOrderByDisplayOrderAsc(g.getGuideContextResultId()))).readiness(AnalysisResultResponse.Readiness.builder().status(r.getStatus().name()).canGenerateQuestions(r.isCanGenerateQuestions()).reason(r.getReason()).limitations(r.getLimitations()).build()).requirementMatches(ms.stream().map(x -> AnalysisResultResponse.RequirementMatch.builder().matchId(x.getMatchId()).requirementId(x.getRequirementId()).requirementType(x.getRequirementType().name()).requirement(x.getRequirement()).postingSourceRefs(x.getPostingSourceRefs()).candidateEvidence(x.getCandidateEvidence()).candidateSourceRefs(x.getCandidateSourceRefs()).matchLevel(x.getMatchLevel().name()).reason(x.getReason()).missingPoint(x.getMissingPoint()).build()).toList()).actionPlans(as.stream().map(x -> AnalysisResultResponse.ActionPlan.builder().actionPlanId(x.getActionPlanId()).relatedMatchId(x.getMatchAnalysisResult().getMatchId()).relatedRequirementId(x.getRelatedRequirementId()).matchLevel(x.getMatchLevel().name()).missingPoint(x.getMissingPoint()).suggestion(x.getSuggestion()).status(x.getStatus().name()).deadline(x.getDeadline()).completedAt(x.getCompletedAt()).build()).toList()).build();
    }

    private CustomException integrity() {
        return new CustomException(ErrorCode.JSON05_RESULT_INTEGRITY_CONFLICT);
    }
}
