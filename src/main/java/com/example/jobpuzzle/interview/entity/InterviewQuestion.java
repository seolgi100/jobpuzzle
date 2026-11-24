package com.example.jobpuzzle.interview.entity;

import com.example.jobpuzzle.analysis.entity.AnalysisSourceReference;
import com.example.jobpuzzle.analysis.entity.MatchAnalysisResult;
import com.example.jobpuzzle.global.common.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;

@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "interview_question", uniqueConstraints = {
        @UniqueConstraint(name = "uk_interview_question_set_key", columnNames = {"question_set_id", "question_key"}),
        @UniqueConstraint(name = "uk_interview_question_set_order", columnNames = {"question_set_id", "display_order"})
})
public class InterviewQuestion extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "question_id")
    private Long questionId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "question_set_id", nullable = false)
    private QuestionSet questionSet;

    @Column(name = "question_key", nullable = false, length = 100)
    private String questionKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "question_type", nullable = false, length = 30)
    private InterviewQuestionType questionType;

    @Lob
    @Column(name = "question_text", nullable = false, columnDefinition = "TEXT")
    private String question;

    @Lob
    @Column(name = "intent", nullable = false, columnDefinition = "TEXT")
    private String intent;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evaluation_focus", nullable = false, columnDefinition = "json")
    private List<InterviewQuestionEvaluationFocus> evaluationFocus;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "related_match_id")
    private MatchAnalysisResult relatedMatch;

    @Column(name = "related_requirement_id", length = 100)
    private String relatedRequirementId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "source_refs", nullable = false, columnDefinition = "json")
    private List<AnalysisSourceReference> sourceRefs;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", nullable = false, length = 20)
    private InterviewQuestionReviewStatus reviewStatus;

    @Lob
    @Column(name = "review_note", columnDefinition = "TEXT")
    private String reviewNote;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    // 평가 명세 4-2: 질문이 확인하는 역량 태그 코드 1~2개. v1 질문은 null
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "competency_tags", columnDefinition = "json")
    private List<String> competencyTags;

    /** 평가 명세 v2 질문 (기초 지식·자료 기반·인성). 태그 검증은 만드는 쪽에서 끝낸 뒤 넘긴다. */
    public static InterviewQuestion v2(QuestionSet questionSet, String questionKey, InterviewQuestionType questionType,
                                       String questionText, String intent, List<String> competencyTags,
                                       String relatedRequirementId, int displayOrder) {
        InterviewQuestion question = create(questionSet, questionKey, questionType, questionText, intent,
                List.of(), displayOrder);
        question.competencyTags = List.copyOf(competencyTags);
        question.relatedRequirementId = relatedRequirementId;
        return question;
    }

    public static InterviewQuestion create(
            QuestionSet questionSet,
            String questionKey,
            InterviewQuestionType questionType,
            String questionText,
            String intent,
            List<InterviewQuestionEvaluationFocus> evaluationFocus,
            int displayOrder
    ) {
        InterviewQuestion question = new InterviewQuestion();
        question.questionSet = questionSet;
        question.questionKey = questionKey;
        question.questionType = questionType;
        question.question = questionText;
        question.intent = intent;
        question.evaluationFocus = evaluationFocus == null ? List.of() : List.copyOf(evaluationFocus);
        question.sourceRefs = List.of();
        question.reviewStatus = InterviewQuestionReviewStatus.PASS;
        question.displayOrder = displayOrder;
        return question;
    }

}
