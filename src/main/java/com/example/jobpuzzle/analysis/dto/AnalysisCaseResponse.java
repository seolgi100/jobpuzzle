package com.example.jobpuzzle.analysis.dto;

import com.example.jobpuzzle.analysis.entity.AnalysisCase;
import com.example.jobpuzzle.analysis.entity.AnalysisCaseSource;
import com.example.jobpuzzle.analysis.entity.AnalysisCaseStatus;
import com.example.jobpuzzle.jobcategory.entity.JobCategoryCareerLevel;
import com.example.jobpuzzle.document.entity.UserDocumentType;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
public class AnalysisCaseResponse {

    private final Long analysisCaseId;
    private final Long jobCategoryId;
    private final String mainCategory;
    private final String subCategory;
    private final JobCategoryCareerLevel careerLevel;
    private final AnalysisCaseStatus status;
    private final List<AnalysisCaseSourceResponse> sources;
    private final LocalDateTime createdAt;
    // 면접 준비 화면의 공고별 묶음 기준: 이 분석에 고른 채용공고 자료
    private final Long postingDocumentId;
    private final String postingName;
    // 목록 조회에서만 채움: 아직 면접에 쓰지 않은 맞춤 질문 세트가 있는지
    private final Boolean questionSetAvailable;

    private AnalysisCaseResponse(
            Long analysisCaseId, Long jobCategoryId, String mainCategory, String subCategory,
            JobCategoryCareerLevel careerLevel, AnalysisCaseStatus status,
            List<AnalysisCaseSourceResponse> sources, LocalDateTime createdAt,
            Boolean questionSetAvailable
    ) {
        this.analysisCaseId = analysisCaseId;
        this.jobCategoryId = jobCategoryId;
        this.mainCategory = mainCategory;
        this.subCategory = subCategory;
        this.careerLevel = careerLevel;
        this.status = status;
        this.sources = sources;
        this.createdAt = createdAt;
        AnalysisCaseSourceResponse posting = sources.stream()
                .filter(source -> source.getDocumentType() == UserDocumentType.JOB_POSTING)
                .findFirst()
                .orElse(null);
        this.postingDocumentId = posting == null ? null : posting.getDocumentId();
        this.postingName = posting == null ? null : posting.getDisplayName();
        this.questionSetAvailable = questionSetAvailable;
    }

    public static AnalysisCaseResponse of(AnalysisCase analysisCase, List<AnalysisCaseSource> sources) {
        return of(analysisCase, sources, null);
    }

    public static AnalysisCaseResponse of(
            AnalysisCase analysisCase, List<AnalysisCaseSource> sources, Boolean questionSetAvailable
    ) {
        var jobCategory = analysisCase.getJobCategory();
        return new AnalysisCaseResponse(
                analysisCase.getAnalysisCaseId(),
                jobCategory.getJobCategoryId(),
                jobCategory.getMainCategory(),
                jobCategory.getSubCategory(),
                jobCategory.getCareerLevel(),
                analysisCase.getStatus(),
                sources.stream().map(AnalysisCaseSourceResponse::from).toList(),
                analysisCase.getCreatedAt(),
                questionSetAvailable
        );
    }
}