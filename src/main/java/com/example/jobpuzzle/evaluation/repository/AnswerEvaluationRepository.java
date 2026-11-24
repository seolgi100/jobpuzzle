package com.example.jobpuzzle.evaluation.repository;

import com.example.jobpuzzle.evaluation.entity.AnswerEvaluation;
import com.example.jobpuzzle.interview.entity.InterviewMessageType;
import com.example.jobpuzzle.interview.entity.InterviewSessionMode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AnswerEvaluationRepository
        extends JpaRepository<AnswerEvaluation, Long> {

    // 실제 JPA 연관관계 기준 조회
    Optional<AnswerEvaluation> findByAnswerMessage_MessageId(
            Long answerMessageId
    );

    Optional<AnswerEvaluation>
    findByEvaluationIdAndSessionQuestion_Session_User_UserId(
            Long evaluationId,
            Long userId
    );

    List<AnswerEvaluation>
    findBySessionQuestion_SessionQuestionIdOrderByEvaluationIdAsc(
            Long sessionQuestionId
    );

    List<AnswerEvaluation>
    findBySessionQuestion_Session_SessionIdOrderByEvaluationIdAsc(
            Long sessionId
    );

    boolean existsBySessionQuestion_Session_SessionId(Long sessionId);

    // 최종 리포트 팀 코드가 기존 이름으로 호출할 수 있도록 만든 호환 메서드
    default Optional<AnswerEvaluation> findByAnswerMessageId(
            Long answerMessageId
    ) {
        return findByAnswerMessage_MessageId(answerMessageId);
    }

    // 시뮬레이션: 같은 질문(부모 메시지 문장)·같은 답변 종류로 전에 저장한 v2 평가 (최근 것부터)
    @Query("""
            select e from AnswerEvaluation e
            join e.answerMessage a
            where e.sessionQuestion.session.user.userId = :userId
              and e.evaluationVersion = 2
              and e.evaluationMode = :mode
              and a.messageType = :type
              and a.parentMessage.messageText = :questionText
              and a.messageId <> :excludeMessageId
            order by e.evaluationId desc
            """)
    List<AnswerEvaluation> findStoredEvaluations(@Param("userId") Long userId, @Param("mode") InterviewSessionMode mode,
                                                 @Param("type") InterviewMessageType type,
                                                 @Param("questionText") String questionText,
                                                 @Param("excludeMessageId") Long excludeMessageId);
}
