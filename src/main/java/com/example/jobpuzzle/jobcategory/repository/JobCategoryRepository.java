package com.example.jobpuzzle.jobcategory.repository;

import com.example.jobpuzzle.jobcategory.entity.JobCategory;
import com.example.jobpuzzle.jobcategory.entity.JobCategoryCareerLevel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface JobCategoryRepository extends JpaRepository<JobCategory, Long> {

    Optional<JobCategory> findByMainCategoryAndSubCategoryAndCareerLevel(
            String mainCategory,
            String subCategory,
            JobCategoryCareerLevel careerLevel
    );

    boolean existsByMainCategoryAndSubCategory(String mainCategory, String subCategory);

    // 관리자 수정 시 자기 자신은 중복으로 치지 않고 확인
    boolean existsByMainCategoryAndSubCategoryAndCareerLevelAndJobCategoryIdNot(
            String mainCategory,
            String subCategory,
            JobCategoryCareerLevel careerLevel,
            Long jobCategoryId
    );
}
