package com.example.jobpuzzle.competency.repository;

import com.example.jobpuzzle.competency.entity.PersonalityQuestion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PersonalityQuestionRepository extends JpaRepository<PersonalityQuestion, Long> {

    List<PersonalityQuestion> findByMainCategoryAndSubCategoryOrderByPersonalityQuestionIdAsc(String mainCategory, String subCategory);
}
