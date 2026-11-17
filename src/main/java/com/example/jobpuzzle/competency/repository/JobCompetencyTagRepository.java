package com.example.jobpuzzle.competency.repository;

import com.example.jobpuzzle.competency.entity.JobCompetencyTag;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface JobCompetencyTagRepository extends JpaRepository<JobCompetencyTag, Long> {

    List<JobCompetencyTag> findByMainCategoryAndSubCategoryOrderByJobCompetencyTagIdAsc(String mainCategory, String subCategory);
}
