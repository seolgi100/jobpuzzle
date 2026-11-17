package com.example.jobpuzzle.analysis.service;

import com.example.jobpuzzle.ai.dto.BasicQuestionGenerationInput;
import com.example.jobpuzzle.evaluation.entity.AnswerEvaluation;
import com.example.jobpuzzle.evaluation.entity.WeaknessTagLog;
import com.example.jobpuzzle.interview.entity.InterviewMessage;
import com.example.jobpuzzle.interview.entity.InterviewSessionQuestion;
import com.example.jobpuzzle.jobcategory.entity.JobCategory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 질문 생성용 DB Entity를 AI 입력 전용 DTO로 변환한다.
 */
@Component
public class InterviewQuestionGenerationInputMapper {

    public BasicQuestionGenerationInput basic(JobCategory jobCategory) {
        return new BasicQuestionGenerationInput(
                jobCategory.getJobCategoryId(),
                jobCategory.getMainCategory(),
                jobCategory.getSubCategory(),
                jobCategory.getCareerLevel()
        );
    }

}
