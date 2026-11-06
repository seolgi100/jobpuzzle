package com.example.jobpuzzle.analysis.repository;

import com.example.jobpuzzle.analysis.entity.AnalysisCase;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.Optional;
import java.util.List;

public interface AnalysisCaseRepository extends JpaRepository<AnalysisCase, Long> {

    Optional<AnalysisCase> findByAnalysisCaseIdAndUser_UserId(Long analysisCaseId, Long userId);

    List<AnalysisCase> findByUser_UserIdAndStatusOrderByCreatedAtDesc(
            Long userId,
            com.example.jobpuzzle.analysis.entity.AnalysisCaseStatus status
    );

    List<AnalysisCase> findByUser_UserIdAndStatusInOrderByCreatedAtDesc(
            Long userId,
            java.util.Collection<com.example.jobpuzzle.analysis.entity.AnalysisCaseStatus> statuses
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<AnalysisCase> findWithLockByAnalysisCaseIdAndUser_UserId(Long analysisCaseId, Long userId);

    void deleteByUser_UserId(Long userId);
}
