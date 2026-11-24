package com.example.jobpuzzle.competency.dto;

import com.example.jobpuzzle.competency.entity.CommonCompetencyTag;
import com.example.jobpuzzle.competency.entity.JobCompetencyTag;
import com.example.jobpuzzle.competency.entity.PersonalityQuestion;
import com.example.jobpuzzle.jobcategory.entity.JobCategoryCareerLevel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 직무별 역량 태그·인성 질문 관리 API의 요청·응답. */
public final class CompetencyDtos {

    private CompetencyDtos() {
    }

    public record JobRef(String mainCategory, String subCategory) {
    }

    public record TagRequest(
            @NotBlank(message = "대분류를 입력해주세요.") @Size(max = 50) String mainCategory,
            @NotBlank(message = "중분류를 입력해주세요.") @Size(max = 50) String subCategory,
            @NotBlank(message = "코드를 입력해주세요.") @Size(max = 50)
            @Pattern(regexp = "[A-Z][A-Z0-9_]*", message = "코드는 영문 대문자, 숫자, 밑줄만 쓸 수 있습니다.") String code,
            @NotBlank(message = "이름을 입력해주세요.") @Size(max = 30) String name,
            @NotBlank(message = "설명을 입력해주세요.") @Size(max = 300) String description) {
    }

    public record QuestionRequest(
            @NotBlank(message = "대분류를 입력해주세요.") @Size(max = 50) String mainCategory,
            @NotBlank(message = "중분류를 입력해주세요.") @Size(max = 50) String subCategory,
            @NotBlank(message = "질문을 입력해주세요.") @Size(max = 500) String question,
            @NotBlank(message = "출제 의도를 입력해주세요.") @Size(max = 300) String intent,
            @NotEmpty(message = "역량 태그를 1개 이상 선택해주세요.") List<String> competencyTags,
            @NotNull(message = "경력수준을 선택해주세요.") JobCategoryCareerLevel careerLevel) {
    }

    public record CommonTagResponse(String code, String name) {
        public static CommonTagResponse from(CommonCompetencyTag tag) {
            return new CommonTagResponse(tag.name(), tag.getLabel());
        }
    }

    public record TagResponse(Long tagId, String code, String name, String description) {
        public static TagResponse from(JobCompetencyTag tag) {
            return new TagResponse(tag.getJobCompetencyTagId(), tag.getCode(), tag.getName(), tag.getDescription());
        }
    }

    public record QuestionResponse(Long questionId, String question, String intent, List<String> competencyTags,
                                   JobCategoryCareerLevel careerLevel) {
        public static QuestionResponse from(PersonalityQuestion q) {
            return new QuestionResponse(q.getPersonalityQuestionId(), q.getQuestion(), q.getIntent(),
                    q.getCompetencyTagCodes(), q.getCareerLevel());
        }
    }

    public record JobCompetencyResponse(String mainCategory, String subCategory, List<CommonTagResponse> commonTags,
                                        List<TagResponse> jobTags, List<QuestionResponse> personalityQuestions) {
    }
}
