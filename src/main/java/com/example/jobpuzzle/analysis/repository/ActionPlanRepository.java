package com.example.jobpuzzle.analysis.repository;

import com.example.jobpuzzle.analysis.entity.ActionPlan;
import com.example.jobpuzzle.analysis.entity.ActionPlanStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ActionPlanRepository extends JpaRepository<ActionPlan, Long> {
    List<ActionPlan> findBySnapshot_SnapshotIdOrderByActionPlanIdAsc(Long snapshotId);
    Optional<ActionPlan> findBySnapshot_SnapshotIdAndTaskKey(Long snapshotId, String taskKey);

    // 수정 대상 조회 단계에서 로그인 사용자 소유권까지 함께 제한한다.
    Optional<ActionPlan> findByActionPlanIdAndSnapshot_User_UserId(Long actionPlanId, Long userId);

    // 사용자 전용 관리 화면에 필요한 연관 데이터를 한 번에 조회해 지연 로딩 반복을 막는다.
    @Query("""
            select actionPlan
            from ActionPlan actionPlan
            join fetch actionPlan.snapshot snapshot
            join fetch snapshot.analysisCase analysisCase
            join fetch snapshot.jobCategory jobCategory
            join fetch actionPlan.matchAnalysisResult matchResult
            where snapshot.user.userId = :userId
              and analysisCase.hiddenAt is null
              and (:status is null or actionPlan.status = :status)
              and (:analysisCaseId is null or analysisCase.analysisCaseId = :analysisCaseId)
            order by actionPlan.actionPlanId desc
            """)
    List<ActionPlan> findOwnedActionPlans(
            @Param("userId") Long userId,
            @Param("status") ActionPlanStatus status,
            @Param("analysisCaseId") Long analysisCaseId
    );

    // 달력 범위 조회도 사용자 소유권과 마감일 범위를 DB 조건으로 함께 제한한다.
    @Query("""
            select actionPlan
            from ActionPlan actionPlan
            join fetch actionPlan.snapshot snapshot
            join fetch snapshot.analysisCase analysisCase
            join fetch snapshot.jobCategory jobCategory
            join fetch actionPlan.matchAnalysisResult matchResult
            where snapshot.user.userId = :userId
              and analysisCase.hiddenAt is null
              and actionPlan.deadline between :from and :to
            order by actionPlan.deadline asc, actionPlan.actionPlanId asc
            """)
    List<ActionPlan> findOwnedCalendarActionPlans(
            @Param("userId") Long userId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to
    );

    boolean existsBySnapshot_SnapshotIdAndTaskKey(Long snapshotId, String taskKey);
    boolean existsBySnapshot_SnapshotId(Long snapshotId);
    void deleteBySnapshot_SnapshotId(Long snapshotId);
    void deleteBySnapshot_User_UserId(Long userId);
}
