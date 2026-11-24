package com.example.jobpuzzle.interview.entity;

import com.example.jobpuzzle.ai.log.AiCallLog;
import com.example.jobpuzzle.analysis.entity.AnalysisInputSnapshot;
import com.example.jobpuzzle.global.common.BaseTimeEntity;
import com.example.jobpuzzle.guide.entity.GuideContextResult;
import com.example.jobpuzzle.jobcategory.entity.JobCategory;
import com.example.jobpuzzle.jobcategory.entity.JobCategoryCareerLevel;
import com.example.jobpuzzle.user.entity.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;

@Getter
@Entity
@NoArgsConstructor
// v2는 같은 분석 결과로 같은 모드 면접을 여러 번 만들 수 있어 (snapshot_id, mode) 유일 조건을 뺐다.
// 기존 DB는 db/manual/interview/evaluation-v2-question-set.sql로 맞춘다.
@Table(name = "question_set")
public class QuestionSet extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "question_set_id")
    private Long questionSetId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    // QuestionSet은 세션 생성 전에 PASS 질문을 고정하는 분석 결과다.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "snapshot_id")
    private AnalysisInputSnapshot snapshot;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 30)
    private InterviewSessionMode interviewMode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_category_id", nullable = false)
    private JobCategory jobCategory;

    @Enumerated(EnumType.STRING)
    @Column(name = "career_level", nullable = false, length = 20)
    private JobCategoryCareerLevel careerLevel;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "guide_context_id")
    private GuideContextResult guideContextResult;

    @Enumerated(EnumType.STRING)
    @Column(name = "generation_source", nullable = false, length = 30)
    private QuestionSetGenerationSource generationSource;

    @Column(name = "prompt_version", length = 20)
    private String promptVersion;

    @Column(name = "target_weakness_tag", length = 100)
    private String targetWeaknessTag;

    @Column(name = "target_dimension", length = 50)
    private String targetDimension;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "basis_evaluation_ids", columnDefinition = "json")
    private List<Long> basisEvaluationIds;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private QuestionSetStatus status;

    // 인성 면접은 AI를 부르지 않아 null
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ai_call_log_id")
    private AiCallLog aiCallLog;

    public static QuestionSet companyFit(AnalysisInputSnapshot snapshot, GuideContextResult guideContextResult,
                                         AiCallLog aiCallLog) {
        QuestionSet questionSet = new QuestionSet();
        questionSet.user = snapshot.getUser();
        questionSet.snapshot = snapshot;
        questionSet.interviewMode = InterviewSessionMode.COMPANY_FIT;
        questionSet.jobCategory = snapshot.getJobCategory();
        questionSet.careerLevel = snapshot.getJobCategory().getCareerLevel();
        questionSet.guideContextResult = guideContextResult;
        questionSet.generationSource = QuestionSetGenerationSource.AI;
        questionSet.promptVersion = aiCallLog.getPromptVersion();
        questionSet.status = QuestionSetStatus.ACTIVE;
        questionSet.aiCallLog = aiCallLog;
        return questionSet;
    }

    // 면접 기능 추가: BASIC·WEAKNESS_REVIEW 질문 묶음도 세션 생성 전에 ACTIVE 상태로 고정한다.
    public static QuestionSet create(
            User user,
            InterviewSessionMode mode,
            JobCategory jobCategory,
            JobCategoryCareerLevel careerLevel,
            GuideContextResult guideContextResult,
            QuestionSetGenerationSource generationSource,
            String promptVersion,
            String targetWeaknessTag,
            String targetDimension,
            List<Long> basisEvaluationIds,
            AiCallLog aiCallLog
    ) {
        QuestionSet questionSet = new QuestionSet();
        questionSet.user = user;
        questionSet.interviewMode = mode;
        questionSet.jobCategory = jobCategory;
        questionSet.careerLevel = careerLevel;
        questionSet.guideContextResult = guideContextResult;
        questionSet.generationSource = generationSource;
        questionSet.promptVersion = promptVersion;
        questionSet.targetWeaknessTag = targetWeaknessTag;
        questionSet.targetDimension = targetDimension;
        questionSet.basisEvaluationIds = basisEvaluationIds == null ? List.of() : List.copyOf(basisEvaluationIds);
        questionSet.status = QuestionSetStatus.ACTIVE;
        questionSet.aiCallLog = aiCallLog;
        return questionSet;
    }

    /**
     * 평가 명세 v2 질문 묶음. 기초 지식·자료 기반은 사용자가 고른 분석 결과(snapshot)와 AI 호출 기록을,
     * 인성은 둘 다 없이 회원 직무만 가진다.
     */
    public static QuestionSet v2(User user, InterviewSessionMode mode, JobCategory jobCategory,
                                 AnalysisInputSnapshot snapshot, AiCallLog aiCallLog) {
        QuestionSet questionSet = create(user, mode, jobCategory, jobCategory.getCareerLevel(), null,
                aiCallLog == null ? QuestionSetGenerationSource.ADMIN_POOL : QuestionSetGenerationSource.AI,
                aiCallLog == null ? null : aiCallLog.getPromptVersion(), null, null, List.of(), aiCallLog);
        questionSet.snapshot = snapshot;
        return questionSet;
    }

    // 면접 기능 추가: 세션이 정상 완료되면 미선택 질문까지 포함해 재사용을 차단한다.
    public void archive() {
        status = QuestionSetStatus.ARCHIVED;
    }

    public boolean isActive() {
        return status == QuestionSetStatus.ACTIVE;
    }
}
