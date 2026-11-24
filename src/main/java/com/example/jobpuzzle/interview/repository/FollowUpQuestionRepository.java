package com.example.jobpuzzle.interview.repository;

import com.example.jobpuzzle.interview.entity.FollowUpQuestion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface FollowUpQuestionRepository extends JpaRepository<FollowUpQuestion, Long> {
    boolean existsByEvaluation_EvaluationId(Long evaluationId);

    Optional<FollowUpQuestion> findFirstByEvaluation_EvaluationId(Long evaluationId);
}
