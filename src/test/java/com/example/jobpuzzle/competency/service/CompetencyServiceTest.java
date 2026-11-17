package com.example.jobpuzzle.competency.service;

import com.example.jobpuzzle.competency.dto.CompetencyDtos.TagRequest;
import com.example.jobpuzzle.competency.entity.JobCompetencyTag;
import com.example.jobpuzzle.competency.entity.PersonalityQuestion;
import com.example.jobpuzzle.competency.repository.JobCompetencyTagRepository;
import com.example.jobpuzzle.competency.repository.PersonalityQuestionRepository;
import com.example.jobpuzzle.global.error.CustomException;
import com.example.jobpuzzle.global.error.ErrorCode;
import com.example.jobpuzzle.jobcategory.entity.JobCategoryCareerLevel;
import com.example.jobpuzzle.jobcategory.repository.JobCategoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CompetencyServiceTest {

    private static final String MAIN = "IT·개발";
    private static final String SUB = "백엔드 개발";

    private final JobCompetencyTagRepository tagRepository = mock(JobCompetencyTagRepository.class);
    private final PersonalityQuestionRepository questionRepository = mock(PersonalityQuestionRepository.class);
    private final JobCategoryRepository jobCategoryRepository = mock(JobCategoryRepository.class);
    private final CompetencyService service = new CompetencyService(tagRepository, questionRepository, jobCategoryRepository);

    private final JobCompetencyTag systemDesign = new JobCompetencyTag(MAIN, SUB, "SYSTEM_DESIGN", "구조화·설계", "설명");

    @BeforeEach
    void setUp() {
        when(jobCategoryRepository.existsByMainCategoryAndSubCategory(MAIN, SUB)).thenReturn(true);
        when(tagRepository.findByMainCategoryAndSubCategoryOrderByJobCompetencyTagIdAsc(MAIN, SUB)).thenReturn(List.of(systemDesign));
        when(questionRepository.findByMainCategoryAndSubCategoryOrderByPersonalityQuestionIdAsc(MAIN, SUB)).thenReturn(List.of());
    }

    @Test
    void acceptsOneOrTwoCommonOrJobTags() {
        assertThatCode(() -> service.validateTags(MAIN, SUB, List.of("COLLABORATION"))).doesNotThrowAnyException();
        assertThatCode(() -> service.validateTags(MAIN, SUB, List.of("SYSTEM_DESIGN", "REASONING"))).doesNotThrowAnyException();
    }

    @Test
    void rejectsWrongTagCountDuplicatesAndOtherJobsTags() {
        assertInvalid(List.of());
        assertInvalid(List.of("COLLABORATION", "REASONING", "SYSTEM_DESIGN"));
        assertInvalid(List.of("REASONING", "REASONING"));
        assertInvalid(List.of("BRAND_UNDERSTANDING")); // 다른 직무(BX 디자인)의 태그
    }

    @Test
    void rejectsTagCodeThatShadowsCommonOrExistingTag() {
        assertThatThrownBy(() -> service.createTag(new TagRequest(MAIN, SUB, "COLLABORATION", "협업2", "설명")))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.COMPETENCY_TAG_DUPLICATE);
        assertThatThrownBy(() -> service.createTag(new TagRequest(MAIN, SUB, "SYSTEM_DESIGN", "다른 이름", "설명")))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.COMPETENCY_TAG_DUPLICATE);
    }

    @Test
    void refusesToDeleteOrRecodeTagUsedByAQuestion() {
        when(tagRepository.findById(1L)).thenReturn(Optional.of(systemDesign));
        when(questionRepository.findByMainCategoryAndSubCategoryOrderByPersonalityQuestionIdAsc(MAIN, SUB)).thenReturn(List.of(
                new PersonalityQuestion(MAIN, SUB, "질문", "의도", List.of("SYSTEM_DESIGN"), JobCategoryCareerLevel.ANY)));

        assertThatThrownBy(() -> service.deleteTag(1L))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.COMPETENCY_TAG_IN_USE);
        assertThatThrownBy(() -> service.updateTag(1L, new TagRequest(MAIN, SUB, "ARCHITECTURE", "구조화·설계", "설명")))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.COMPETENCY_TAG_IN_USE);
        verify(tagRepository, never()).delete(any());

        // 코드는 그대로 두고 이름·설명만 바꾸는 것은 허용
        service.updateTag(1L, new TagRequest(MAIN, SUB, "SYSTEM_DESIGN", "설계", "새 설명"));
        assertThat(systemDesign.getName()).isEqualTo("설계");
    }

    private void assertInvalid(List<String> codes) {
        assertThatThrownBy(() -> service.validateTags(MAIN, SUB, codes))
                .extracting(e -> ((CustomException) e).getErrorCode()).isEqualTo(ErrorCode.COMPETENCY_TAG_INVALID);
    }
}
