package com.example.jobpuzzle.interview.entity;

import com.example.jobpuzzle.analysis.entity.AnalysisInputSnapshot;
import com.example.jobpuzzle.analysis.entity.CandidateMaterialAnalysis;
import com.example.jobpuzzle.analysis.entity.JobPostingAnalysis;
import com.example.jobpuzzle.global.common.BaseTimeEntity;
import com.example.jobpuzzle.guide.entity.GuideContextResult;
import com.example.jobpuzzle.guide.entity.JobGuideDocument;
import com.example.jobpuzzle.jobcategory.entity.JobCategory;
import com.example.jobpuzzle.jobcategory.entity.JobCategoryCareerLevel;
import com.example.jobpuzzle.user.entity.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

// 사용자가 선택한 질문으로 생성하는 면접 세션과 모드별 불변 기준 저장
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "interview_session")
public class InterviewSession extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "session_id")
    private Long sessionId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    // 질문 선택의 원본 묶음
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "question_set_id", nullable = false)
    private QuestionSet questionSet;

    // COMPANY_FIT에서 사용한 분석 입력 스냅샷
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "snapshot_id")
    private AnalysisInputSnapshot snapshot;

    // COMPANY_FIT 세션에서 사용한 공고 분석 결과
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_posting_analysis_id")
    private JobPostingAnalysis jobPostingAnalysis;

    // COMPANY_FIT 세션에서 사용한 사용자 자료 분석 결과
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "candidate_analysis_id")
    private CandidateMaterialAnalysis candidateAnalysis;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 30)
    private InterviewSessionMode mode;

    // 세션 생성 당시 직무 기준
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_category_id", nullable = false)
    private JobCategory jobCategory;

    // 세션 생성 당시 경력 기준
    @Enumerated(EnumType.STRING)
    @Column(name = "career_level", nullable = false, length = 20)
    private JobCategoryCareerLevel careerLevel;

    // 질문 생성에 사용한 가이드 컨텍스트
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "guide_context_id")
    private GuideContextResult guideContextResult;

    // 실제 사용한 가이드
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "guide_id")
    private JobGuideDocument guideDocument;

    @Column(name = "guide_version", length = 20)
    private String guideVersion;

    // AI 질문 생성 프롬프트 버전
    @Column(name = "prompt_version", length = 20)
    private String promptVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private InterviewSessionStatus status;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    // 진행 중 면접 목록의 최신 활동 정렬 기준
    @Column(name = "last_activity_at")
    private LocalDateTime lastActivityAt;

    @Column(name = "canceled_at")
    private LocalDateTime canceledAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    // 평가 명세 v2 다회차: 이 회차를 만든 바로 앞 회차. 첫 회차는 null
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "previous_session_id")
    private InterviewSession previousSession;

    // 회차 번호. 예전 행은 null이라 getRoundNumber()로 읽는다
    @Column(name = "round_no")
    private Integer round;

    public int getRoundNumber() {
        return round == null ? 1 : round;
    }

    /**
     * 끝난 면접의 다음 회차. 같은 분석 결과를 그대로 쓰고, 질문은 이전 회차와 같은 묶음(다시 진행)이거나
     * 새로 만든 묶음(질문 다시 생성)이다.
     */
    public static InterviewSession nextRound(InterviewSession previous, QuestionSet questionSet) {
        InterviewSession session = create(questionSet, previous.jobPostingAnalysis, previous.candidateAnalysis);
        session.snapshot = previous.snapshot;
        session.previousSession = previous;
        session.round = previous.getRoundNumber() + 1;
        return session;
    }

    /**
     * BASIC·WEAKNESS_REVIEW와 기존 호출부에서 사용하는 생성 메서드.
     * COMPANY_FIT 분석 Entity 연결은 전체 충돌 해결 후 서비스에서 추가한다.
     */
    public static InterviewSession create(QuestionSet questionSet) {
        return create(questionSet, null, null);
    }

    /**
     * COMPANY_FIT 세션 생성 시 분석 결과까지 고정해서 저장하는 생성 메서드.
     */
    public static InterviewSession create(
            QuestionSet questionSet,
            JobPostingAnalysis jobPostingAnalysis,
            CandidateMaterialAnalysis candidateAnalysis
    ) {
        InterviewSession session = new InterviewSession();

        session.user = questionSet.getUser();
        session.questionSet = questionSet;
        session.snapshot = questionSet.getSnapshot();
        session.jobPostingAnalysis = jobPostingAnalysis;
        session.candidateAnalysis = candidateAnalysis;
        session.mode = questionSet.getInterviewMode();
        session.jobCategory = questionSet.getJobCategory();
        session.careerLevel = questionSet.getCareerLevel();
        session.guideContextResult = questionSet.getGuideContextResult();

        if (questionSet.getGuideContextResult() != null) {
            session.guideDocument =
                    questionSet.getGuideContextResult().getGuide();
            session.guideVersion =
                    questionSet.getGuideContextResult().getGuideVersion();
        }

        session.promptVersion = questionSet.getPromptVersion();

        session.status = InterviewSessionStatus.CREATED;
        session.lastActivityAt = LocalDateTime.now();
        session.round = 1;

        return session;
    }

    // 최초 답변 제출 시 IN_PROGRESS로 전환
    public void start() {
        if (status == InterviewSessionStatus.CREATED) {
            status = InterviewSessionStatus.IN_PROGRESS;
            startedAt = LocalDateTime.now();
        }
        touch();
    }

    public void touch() {
        lastActivityAt = LocalDateTime.now();
    }

    public void complete() {
        status = InterviewSessionStatus.COMPLETED;
        completedAt = LocalDateTime.now();
        lastActivityAt = completedAt;
    }

    public void cancel() {
        status = InterviewSessionStatus.CANCELED;
        canceledAt = LocalDateTime.now();
    }

    public boolean isEditable() {
        return status == InterviewSessionStatus.CREATED
                || status == InterviewSessionStatus.IN_PROGRESS;
    }

    // 최종 리포트 팀 코드와 상태 확인 시 사용 가능
    public boolean isCompleted() {
        return status == InterviewSessionStatus.COMPLETED;
    }

    public void softDelete() {
        deletedAt = LocalDateTime.now();
    }
}
