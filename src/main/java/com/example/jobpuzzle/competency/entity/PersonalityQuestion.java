package com.example.jobpuzzle.competency.entity;

import com.example.jobpuzzle.global.common.BaseTimeEntity;
import com.example.jobpuzzle.jobcategory.entity.JobCategoryCareerLevel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Arrays;
import java.util.List;

/** 관리자가 등록한 직무별 인성 질문 (평가 명세 1장·8장). 인성 면접은 AI 없이 여기서 고른다. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "personality_question")
public class PersonalityQuestion extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "personality_question_id")
    private Long personalityQuestionId;

    @Column(name = "main_category", nullable = false, length = 50)
    private String mainCategory;

    @Column(name = "sub_category", nullable = false, length = 50)
    private String subCategory;

    @Column(name = "question", nullable = false, length = 500)
    private String question;

    @Column(name = "intent", nullable = false, length = 300)
    private String intent;

    // 태그 코드 1~2개를 쉼표로 저장한다. 개수가 작고 고정이라 별도 테이블을 두지 않는다.
    @Column(name = "competency_tags", nullable = false, length = 120)
    private String competencyTags;

    @Enumerated(EnumType.STRING)
    @Column(name = "career_level", nullable = false, length = 20)
    private JobCategoryCareerLevel careerLevel;

    public PersonalityQuestion(String mainCategory, String subCategory, String question, String intent,
                               List<String> competencyTags, JobCategoryCareerLevel careerLevel) {
        this.mainCategory = mainCategory;
        this.subCategory = subCategory;
        update(question, intent, competencyTags, careerLevel);
    }

    public void update(String question, String intent, List<String> competencyTags, JobCategoryCareerLevel careerLevel) {
        this.question = question;
        this.intent = intent;
        this.competencyTags = String.join(",", competencyTags);
        this.careerLevel = careerLevel;
    }

    public List<String> getCompetencyTagCodes() {
        return Arrays.asList(competencyTags.split(","));
    }

    public boolean usesTag(String code) {
        return getCompetencyTagCodes().contains(code);
    }
}
