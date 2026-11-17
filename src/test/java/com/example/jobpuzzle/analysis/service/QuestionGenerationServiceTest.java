package com.example.jobpuzzle.analysis.service;

import com.example.jobpuzzle.interview.entity.InterviewQuestion;
import com.example.jobpuzzle.interview.entity.InterviewSessionMode;
import com.example.jobpuzzle.interview.entity.QuestionSet;
import com.example.jobpuzzle.interview.entity.QuestionSetStatus;
import com.example.jobpuzzle.interview.repository.InterviewQuestionRepository;
import com.example.jobpuzzle.interview.repository.InterviewSessionRepository;
import com.example.jobpuzzle.interview.repository.QuestionSetRepository;
import com.example.jobpuzzle.evaluation.service.WeaknessTagNormalizer;
import com.example.jobpuzzle.jobcategory.entity.JobCategory;
import com.example.jobpuzzle.jobcategory.entity.JobCategoryCareerLevel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QuestionGenerationServiceTest {

    @Mock private QuestionSetRepository questionSetRepository;
    @Mock private InterviewQuestionRepository interviewQuestionRepository;
    @Mock private InterviewSessionRepository interviewSessionRepository;
    @Mock private WeaknessTagNormalizer weaknessTagNormalizer;
    @InjectMocks private QuestionGenerationService service;

    @Test
    void returnsOnlyUnusedBasicQuestionSets() {
        QuestionSet unusedBasic = questionSet(11L);
        QuestionSet consumedBasic = questionSet(12L);
        when(unusedBasic.getInterviewMode()).thenReturn(InterviewSessionMode.BASIC);
        InterviewQuestion basicQuestion = mock(InterviewQuestion.class);

        when(questionSetRepository.findByUser_UserIdAndInterviewModeAndStatus(
                7L, InterviewSessionMode.BASIC, QuestionSetStatus.ACTIVE
        )).thenReturn(List.of(unusedBasic, consumedBasic));
        when(interviewSessionRepository.existsByQuestionSet_QuestionSetId(11L)).thenReturn(false);
        when(interviewSessionRepository.existsByQuestionSet_QuestionSetId(12L)).thenReturn(true);
        when(interviewQuestionRepository.findByQuestionSet_QuestionSetIdOrderByDisplayOrderAsc(11L))
                .thenReturn(List.of(basicQuestion));

        var result = service.getUnusedGeneratedQuestionSets(7L);

        assertThat(result).extracting(value -> value.getQuestionSetId())
                .containsExactly(11L);
    }

    @Test
    void preparedBasicQuestionIncludesItsJobCategoryAndCareerLevel() {
        QuestionSet unusedBasic = questionSet(21L);
        JobCategory category = mock(JobCategory.class);
        InterviewQuestion question = mock(InterviewQuestion.class);
        when(unusedBasic.getInterviewMode()).thenReturn(InterviewSessionMode.BASIC);
        when(unusedBasic.getJobCategory()).thenReturn(category);
        when(unusedBasic.getCareerLevel()).thenReturn(JobCategoryCareerLevel.NEW);
        when(category.getMainCategory()).thenReturn("IT·개발");
        when(category.getSubCategory()).thenReturn("백엔드 개발");
        when(questionSetRepository.findByUser_UserIdAndInterviewModeAndStatus(
                7L, InterviewSessionMode.BASIC, QuestionSetStatus.ACTIVE
        )).thenReturn(List.of(unusedBasic));
        when(interviewSessionRepository.existsByQuestionSet_QuestionSetId(21L)).thenReturn(false);
        when(interviewQuestionRepository.findByQuestionSet_QuestionSetIdOrderByDisplayOrderAsc(21L))
                .thenReturn(List.of(question));

        var result = service.getUnusedGeneratedQuestionSets(7L).get(0);

        assertThat(result.getMainCategory()).isEqualTo("IT·개발");
        assertThat(result.getSubCategory()).isEqualTo("백엔드 개발");
        assertThat(result.getCareerLevel()).isEqualTo("신입");
        assertThat(result.getDisplayTitle()).isEqualTo("IT·개발 · 백엔드 개발 · 신입");
    }

    private QuestionSet questionSet(Long id) {
        QuestionSet value = mock(QuestionSet.class);
        when(value.getQuestionSetId()).thenReturn(id);
        return value;
    }
}
