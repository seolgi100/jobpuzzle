package com.example.jobpuzzle.analysis.dto;

import com.example.jobpuzzle.interview.entity.QuestionSet;
import com.example.jobpuzzle.jobcategory.entity.JobCategoryCareerLevel;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/** 만들어 두고 아직 면접을 시작하지 않은 질문 묶음 (면접 화면의 "준비된 질문" 목록) */
@Getter
@Builder
public class PreparedQuestionSetResponse {
    private Long questionSetId;
    private String mode;
    private String displayTitle;
    private String mainCategory;
    private String subCategory;
    private String careerLevel;
    private int questionCount;
    private LocalDateTime createdAt;

    public static PreparedQuestionSetResponse from(QuestionSet set, int questionCount) {
        String mainCategory = set.getJobCategory() == null ? null : set.getJobCategory().getMainCategory();
        String subCategory = set.getJobCategory() == null ? null : set.getJobCategory().getSubCategory();
        String careerLevel = careerLevelLabel(set.getCareerLevel());
        return PreparedQuestionSetResponse.builder()
                .questionSetId(set.getQuestionSetId())
                .mode(set.getInterviewMode().name())
                .displayTitle(String.join(" · ", java.util.stream.Stream.of(mainCategory, subCategory, careerLevel)
                        .filter(value -> value != null && !value.isBlank())
                        .toList()))
                .mainCategory(mainCategory)
                .subCategory(subCategory)
                .careerLevel(careerLevel)
                .questionCount(questionCount)
                .createdAt(set.getCreatedAt())
                .build();
    }

    private static String careerLevelLabel(JobCategoryCareerLevel careerLevel) {
        if (careerLevel == null) return null;
        return switch (careerLevel) {
            case NEW -> "신입";
            case EXPERIENCED -> "경력";
            case ANY -> "경력 무관";
        };
    }
}
