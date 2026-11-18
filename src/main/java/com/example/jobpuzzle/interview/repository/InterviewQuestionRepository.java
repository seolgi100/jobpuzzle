package com.example.jobpuzzle.interview.repository;

import com.example.jobpuzzle.interview.entity.InterviewQuestion;
import com.example.jobpuzzle.interview.entity.InterviewSessionMode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface InterviewQuestionRepository extends JpaRepository<InterviewQuestion, Long> {
    List<InterviewQuestion> findByQuestionSet_QuestionSetIdOrderByDisplayOrderAsc(Long questionSetId);
    List<InterviewQuestion> findByQuestionSet_QuestionSetIdAndQuestionIdIn(Long questionSetId, Collection<Long> questionIds);
    void deleteByQuestionSet_QuestionSetId(Long questionSetId);
    void deleteByQuestionSet_Snapshot_User_UserId(Long userId);

    // 시뮬레이션: 같은 분석·같은 모드로 전에 만든 질문들 (최근 질문 세트부터, 세트 안에서는 순서대로)
    @Query("""
            select q from InterviewQuestion q
            where q.questionSet.user.userId = :userId
              and q.questionSet.snapshot.snapshotId = :snapshotId
              and q.questionSet.interviewMode = :mode
            order by q.questionSet.questionSetId desc, q.displayOrder asc
            """)
    List<InterviewQuestion> findStoredQuestions(@Param("userId") Long userId, @Param("snapshotId") Long snapshotId,
                                                @Param("mode") InterviewSessionMode mode);
}
