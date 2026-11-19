package com.example.jobpuzzle.analysis.service;

import com.example.jobpuzzle.interview.entity.InterviewQuestion;
import com.example.jobpuzzle.interview.entity.InterviewSessionMode;
import com.example.jobpuzzle.interview.entity.QuestionSet;
import com.example.jobpuzzle.interview.entity.QuestionSetStatus;
import com.example.jobpuzzle.interview.repository.InterviewQuestionRepository;
import com.example.jobpuzzle.interview.repository.InterviewSessionRepository;
import com.example.jobpuzzle.interview.repository.QuestionSetRepository;
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
    @InjectMocks private QuestionGenerationService service;

    // v2 세 모드를 모두 조회하므로, 각 테스트가 정하지 않은 모드는 빈 목록
    @org.junit.jupiter.api.BeforeEach
    void noSetsByDefault() {
        org.mockito.Mockito.lenient().when(questionSetRepository.findByUser_UserIdAndInterviewModeAndStatus(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()
        )).thenReturn(List.of());
    }

    @Test
    void returnsOnlyUnusedV2QuestionSets() {
        QuestionSet unusedBasic = questionSet(11L);
        QuestionSet consumedBasic = questionSet(12L);
        when(unusedBasic.getInterviewMode()).thenReturn(InterviewSessionMode.PERSONALITY);
        InterviewQuestion basicQuestion = mock(InterviewQuestion.class);

        when(questionSetRepository.findByUser_UserIdAndInterviewModeAndStatus(
                7L, InterviewSessionMode.PERSONALITY, QuestionSetStatus.ACTIVE
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
    void preparedQuestionSetIncludesItsJobCategoryAndCareerLevel() {
        QuestionSet unusedBasic = questionSet(21L);
        JobCategory category = mock(JobCategory.class);
        InterviewQuestion question = mock(InterviewQuestion.class);
        when(unusedBasic.getInterviewMode()).thenReturn(InterviewSessionMode.PERSONALITY);
        when(unusedBasic.getJobCategory()).thenReturn(category);
        when(unusedBasic.getCareerLevel()).thenReturn(JobCategoryCareerLevel.NEW);
        when(category.getMainCategory()).thenReturn("IT·개발");
        when(category.getSubCategory()).thenReturn("백엔드 개발");
        when(questionSetRepository.findByUser_UserIdAndInterviewModeAndStatus(
                7L, InterviewSessionMode.PERSONALITY, QuestionSetStatus.ACTIVE
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
