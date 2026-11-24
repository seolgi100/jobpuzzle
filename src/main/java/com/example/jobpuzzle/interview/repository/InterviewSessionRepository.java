package com.example.jobpuzzle.interview.repository;

import com.example.jobpuzzle.interview.entity.InterviewSession;
import com.example.jobpuzzle.interview.entity.InterviewSessionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface InterviewSessionRepository extends JpaRepository<InterviewSession, Long> {
    Optional<InterviewSession> findBySessionIdAndUser_UserIdAndDeletedAtIsNull(Long sessionId, Long userId);

    // 면접 기능 추가: 하나의 QuestionSet에 동시에 하나의 미완료 세션만 허용한다.
    boolean existsByQuestionSet_QuestionSetIdAndStatusIn(
            Long questionSetId,
            Collection<InterviewSessionStatus> statuses
    );
    boolean existsByQuestionSet_QuestionSetId(Long questionSetId);

    // 다회차: 이 면접을 앞 회차로 둔 다음 회차가 있는지 (취소된 다음 회차는 없는 것으로 본다)
    boolean existsByPreviousSession_SessionIdAndStatusNot(Long previousSessionId, InterviewSessionStatus status);

    Optional<InterviewSession> findFirstByQuestionSet_QuestionSetIdOrderByCreatedAtDesc(
            Long questionSetId
    );

    Optional<InterviewSession> findFirstByUser_UserIdAndStatusInAndDeletedAtIsNullOrderByCreatedAtDesc(
            Long userId,
            Collection<InterviewSessionStatus> statuses
    );

    List<InterviewSession> findByUser_UserIdAndStatusInAndDeletedAtIsNullOrderByCreatedAtDesc(
            Long userId,
            Collection<InterviewSessionStatus> statuses
    );

    List<InterviewSession> findByUser_UserIdAndStatusAndDeletedAtIsNullOrderByCompletedAtDesc(
            Long userId,
            InterviewSessionStatus status
    );
}
