package com.example.jobpuzzle.interview.repository;

import com.example.jobpuzzle.interview.entity.InterviewSessionQuestion;
import com.example.jobpuzzle.interview.entity.InterviewSessionQuestionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface InterviewSessionQuestionRepository
        extends JpaRepository<InterviewSessionQuestion, Long> {

    // 세션의 질문을 진행 순서대로 조회
    List<InterviewSessionQuestion>
    findBySession_SessionIdOrderByDisplayOrderAsc(
            Long sessionId
    );

    // 사용자 소유권을 포함해 세션 질문 조회
    Optional<InterviewSessionQuestion>
    findBySessionQuestionIdAndSession_User_UserId(
            Long sessionQuestionId,
            Long userId
    );

    // 아직 진행할 질문 중 가장 앞선 질문 조회
    Optional<InterviewSessionQuestion>
    findFirstBySession_SessionIdAndStatusInOrderByDisplayOrderAsc(
            Long sessionId,
            Collection<InterviewSessionQuestionStatus> statuses
    );

    // 세션에 선택된 전체 질문 개수
    long countBySession_SessionId(
            Long sessionId
    );

    // 세션 질문 상태별 개수
    long countBySession_SessionIdAndStatus(
            Long sessionId,
            InterviewSessionQuestionStatus status
    );

    // develop 최종 리포트 코드의 기존 호출 이름을 유지하기 위한 호환 메서드
    default List<InterviewSessionQuestion>
    findBySessionIdOrderByDisplayOrderAsc(
            Long sessionId
    ) {
        return findBySession_SessionIdOrderByDisplayOrderAsc(sessionId);
    }
}
