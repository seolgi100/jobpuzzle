package com.example.jobpuzzle.analysis.service;

import com.example.jobpuzzle.analysis.dto.PreparedQuestionSetResponse;
import com.example.jobpuzzle.analysis.dto.QuestionSetResponse;
import com.example.jobpuzzle.global.error.CustomException;
import com.example.jobpuzzle.global.error.ErrorCode;
import com.example.jobpuzzle.interview.dto.QuestionListResponse;
import com.example.jobpuzzle.interview.entity.*;
import com.example.jobpuzzle.interview.repository.InterviewQuestionRepository;
import com.example.jobpuzzle.interview.repository.InterviewSessionRepository;
import com.example.jobpuzzle.interview.repository.QuestionSetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class QuestionGenerationService {

    // 질문 생성은 v2 QuestionGenerationV2Service가 맡는다. 여기는 만든 묶음 조회만 남았다.
    private static final List<InterviewSessionMode> V2_MODES =
            List.of(InterviewSessionMode.KNOWLEDGE, InterviewSessionMode.MATERIAL, InterviewSessionMode.PERSONALITY);

    private final QuestionSetRepository questionSetRepository;
    private final InterviewQuestionRepository interviewQuestionRepository;
    private final InterviewSessionRepository interviewSessionRepository;

    @Transactional(readOnly = true)
    public QuestionSetResponse getQuestionSet(Long userId, Long questionSetId) {
        QuestionSet set = questionSetRepository
                .findByQuestionSetIdAndUser_UserId(questionSetId, userId)
                .orElseThrow(() -> new CustomException(ErrorCode.QUESTION_SET_NOT_READY));
        List<InterviewQuestion> questions =
                interviewQuestionRepository.findByQuestionSet_QuestionSetIdOrderByDisplayOrderAsc(questionSetId);
        return QuestionSetResponse.from(set, questions);
    }

    // 티켓을 쓰고 만들었지만 아직 면접을 시작하지 않은 v2 질문 묶음 (생성 직후 세션 만들기가 실패한 경우 등)
    @Transactional(readOnly = true)
    public List<PreparedQuestionSetResponse> getUnusedGeneratedQuestionSets(Long userId) {
        return V2_MODES.stream()
                .flatMap(mode -> questionSetRepository
                        .findByUser_UserIdAndInterviewModeAndStatus(userId, mode, QuestionSetStatus.ACTIVE).stream())
                .filter(set -> !interviewSessionRepository.existsByQuestionSet_QuestionSetId(set.getQuestionSetId()))
                .map(set -> PreparedQuestionSetResponse.from(
                        set,
                        interviewQuestionRepository.findByQuestionSet_QuestionSetIdOrderByDisplayOrderAsc(set.getQuestionSetId()).size()))
                .sorted(java.util.Comparator.comparing(PreparedQuestionSetResponse::getQuestionSetId).reversed())
                .toList();
    }

    @Transactional(readOnly = true)
    public List<QuestionListResponse> getQuestionList(Long userId, Long questionSetId) {
        questionSetRepository.findByQuestionSetIdAndUser_UserId(questionSetId, userId)
                .orElseThrow(() -> new CustomException(ErrorCode.QUESTION_SET_NOT_READY));
        return interviewQuestionRepository
                .findByQuestionSet_QuestionSetIdOrderByDisplayOrderAsc(questionSetId)
                .stream()
                .filter(question -> question.getReviewStatus() == InterviewQuestionReviewStatus.PASS)
                .map(QuestionListResponse::from)
                .toList();
    }

}
