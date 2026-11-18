package com.example.jobpuzzle.analysis.entity;

import com.example.jobpuzzle.global.common.BaseEntity;
import com.example.jobpuzzle.global.error.CustomException;
import com.example.jobpuzzle.global.error.ErrorCode;
import com.example.jobpuzzle.jobcategory.entity.JobCategory;
import com.example.jobpuzzle.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

// 분석 작업 단위. DRAFT 상태에서 자료 선택과 직무·경력 기준을 자유롭게 편집하다가
// 분석 입력을 확정하면(JSON-03) INPUT_CONFIRMED로 전환되고 이후 자료 구성을 바꿀 수 없다.
@Getter
@Entity
@NoArgsConstructor
@Table(name = "analysis_case")
public class AnalysisCase extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "analysis_case_id")
    private Long analysisCaseId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_category_id", nullable = false)
    private JobCategory jobCategory;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AnalysisCaseStatus status;

    // 시뮬레이션 계정의 "삭제": 목록에서만 숨기고 결과는 DB에 남긴다 (같은 입력으로 다시 분석하면 이 결과를 다시 보여 줌)
    @Column(name = "hidden_at")
    private LocalDateTime hiddenAt;

    @Builder
    private AnalysisCase(User user, JobCategory jobCategory) {
        this.user = user;
        this.jobCategory = jobCategory;
        this.status = AnalysisCaseStatus.DRAFT;
    }

    public boolean isDraft() {
        return this.status == AnalysisCaseStatus.DRAFT;
    }

    public void changeJobCategory(JobCategory jobCategory) {
        this.jobCategory = jobCategory;
    }

    public void confirmInput() {
        this.status = AnalysisCaseStatus.INPUT_CONFIRMED;
    }

    // 최초 실행 또는 실패 재시도에서 분석 작업을 실행 상태로 전이한다.
    public void startOrRestartAnalysis() {
        if (this.status == AnalysisCaseStatus.INPUT_CONFIRMED || this.status == AnalysisCaseStatus.FAILED) {
            this.status = AnalysisCaseStatus.ANALYZING;
            return;
        }
        throw new CustomException(ErrorCode.ANALYSIS_CASE_NOT_READY);
    }

    // 실행 중 복구할 수 없는 실패를 재시도 가능한 실패 상태로 확정한다.
    public void failAnalysis() {
        if (this.status != AnalysisCaseStatus.ANALYZING) {
            throw new CustomException(ErrorCode.ANALYSIS_CASE_NOT_READY);
        }
        this.status = AnalysisCaseStatus.FAILED;
    }

    public void hide() {
        this.hiddenAt = LocalDateTime.now();
    }

    public void show() {
        this.hiddenAt = null;
    }

    // 모든 JSON-05 결과가 원자적으로 저장된 경우에만 분석 작업을 완료한다.
    public void complete() {
        if (this.status == AnalysisCaseStatus.ANALYZING) {
            this.status = AnalysisCaseStatus.COMPLETED;
        }
    }
}
