package com.example.jobpuzzle.evaluation.entity;

import com.example.jobpuzzle.ai.log.AiCallLog;
import com.example.jobpuzzle.global.common.BaseTimeEntity;
import com.example.jobpuzzle.interview.entity.InterviewMessage;
import com.example.jobpuzzle.interview.entity.InterviewSessionMode;
import com.example.jobpuzzle.interview.entity.InterviewSessionQuestion;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;
import java.util.Map;

// 사용자 답변 메시지별 평가 결과. 원 답변과 꼬리답변을 각각 저장
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "answer_evaluation")
@Check(constraints = "score >= 0 AND score <= 100")
public class AnswerEvaluation extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "evaluation_id")
    private Long evaluationId;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "answer_message_id", nullable = false, unique = true)
    private InterviewMessage answerMessage;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_question_id", nullable = false)
    private InterviewSessionQuestion sessionQuestion;

    @Enumerated(EnumType.STRING)
    @Column(name = "evaluation_mode", nullable = false, length = 30)
    private InterviewSessionMode evaluationMode;

    @Column(name = "score", nullable = false)
    private int score;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evaluation_detail", columnDefinition = "json")
    private Map<String, DimensionEvaluation> evaluationDetail;

    @Lob
    @Column(name = "summary", columnDefinition = "TEXT")
    private String summary;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(
            name = "improvement_direction",
            nullable = false,
            columnDefinition = "json"
    )
    private List<String> improvementDirection;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ai_call_log_id", nullable = false)
    private AiCallLog aiCallLog;

    // 평가 명세 v2 (7장): 평가 계약 버전 (지금은 2)
    @Column(name = "evaluation_version")
    private Integer evaluationVersion;

    // v2: 무응답·회피 답변 (전 항목 1점)
    @Column(name = "evasive")
    private Boolean evasive;

    // v2: 추천 답변 방향
    @Lob
    @Column(name = "recommended_answer", columnDefinition = "TEXT")
    private String recommendedAnswer;

    // v2 다회차: improved(이전 회차보다 나아진 점), toImprove(더 개선하면 좋을 점). 첫 회차는 null
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "comparison", columnDefinition = "json")
    private Map<String, String> comparison;

    /**
     * v2 평가 저장. score는 답변 점수(6개 항목 평균)를 100점으로 환산한 값, evaluationDetail은 항목 code → (1~5점, 근거).
     * 잘한 점은 summary, 보완할 점은 improvementDirection 첫 칸에 둔다.
     */
    public static AnswerEvaluation createV2(
            InterviewMessage answerMessage,
            InterviewSessionQuestion sessionQuestion,
            InterviewSessionMode mode,
            int score,
            Map<String, DimensionEvaluation> evaluationDetail,
            boolean evasive,
            String strengths,
            String improvements,
            String recommendedAnswer,
            Map<String, String> comparison,
            AiCallLog aiCallLog
    ) {
        AnswerEvaluation evaluation = new AnswerEvaluation();
        evaluation.answerMessage = answerMessage;
        evaluation.sessionQuestion = sessionQuestion;
        evaluation.evaluationMode = mode;
        evaluation.score = score;
        evaluation.evaluationDetail = evaluationDetail == null ? Map.of() : Map.copyOf(evaluationDetail);
        evaluation.summary = strengths;
        evaluation.improvementDirection = List.of(improvements);
        evaluation.aiCallLog = aiCallLog;
        evaluation.evaluationVersion = 2;
        evaluation.evasive = evasive;
        evaluation.recommendedAnswer = recommendedAnswer;
        evaluation.comparison = comparison;
        return evaluation;
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DimensionEvaluation {

        private Integer score;
        private String comment;
    }
}
