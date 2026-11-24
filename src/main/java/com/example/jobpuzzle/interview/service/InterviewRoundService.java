package com.example.jobpuzzle.interview.service;

import com.example.jobpuzzle.global.error.CustomException;
import com.example.jobpuzzle.global.error.ErrorCode;
import com.example.jobpuzzle.interview.dto.SessionResponse;
import com.example.jobpuzzle.interview.entity.InterviewMessage;
import com.example.jobpuzzle.interview.entity.InterviewQuestion;
import com.example.jobpuzzle.interview.entity.InterviewSession;
import com.example.jobpuzzle.interview.entity.InterviewSessionMode;
import com.example.jobpuzzle.interview.entity.InterviewSessionQuestion;
import com.example.jobpuzzle.interview.entity.InterviewSessionStatus;
import com.example.jobpuzzle.interview.entity.QuestionSet;
import com.example.jobpuzzle.interview.repository.InterviewMessageRepository;
import com.example.jobpuzzle.interview.repository.InterviewQuestionRepository;
import com.example.jobpuzzle.interview.repository.InterviewSessionQuestionRepository;
import com.example.jobpuzzle.interview.repository.InterviewSessionRepository;
import com.example.jobpuzzle.interview.repository.QuestionSetRepository;
import com.example.jobpuzzle.ticket.entity.TicketUsage;
import com.example.jobpuzzle.ticket.service.TicketService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 평가 명세 v2 다회차 (1장 티켓 표).
 * - 다시 진행: 질문은 그대로, 답변과 평가만 새로 (1장). 같은 질문끼리 이전 회차와 비교한다.
 * - 질문 다시 생성: 새 질문으로 다음 회차 (2장, 인성은 관리자 등록 질문에서 다시 골라 0장 — 수정사항 16번).
 * 회차는 한 줄로만 이어진다: 가장 최근에 끝난 회차에서만 다음 회차를 만들 수 있다.
 * AI 질문 생성이 최종 실패해도 실패 기록은 남아야 해서 CustomException으로는 롤백하지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(noRollbackFor = CustomException.class)
public class InterviewRoundService {

    private final InterviewSessionRepository sessionRepository;
    private final InterviewSessionQuestionRepository sessionQuestionRepository;
    private final InterviewMessageRepository messageRepository;
    private final InterviewQuestionRepository questionRepository;
    private final QuestionSetRepository questionSetRepository;
    private final QuestionGenerationV2Service questionGenerationV2Service;
    private final TicketService ticketService;

    public SessionResponse retry(Long userId, Long sessionId) {
        InterviewSession previous = latestCompletedRound(userId, sessionId);
        List<InterviewQuestion> questions = sessionQuestionRepository
                .findBySession_SessionIdOrderByDisplayOrderAsc(sessionId).stream()
                .map(InterviewSessionQuestion::getQuestion)
                .toList();
        return ticketService.spend(userId, TicketUsage.SESSION_RETRY, ticketService.cost(TicketUsage.SESSION_RETRY),
                () -> startRound(previous, previous.getQuestionSet(), questions));
    }

    public SessionResponse regenerate(Long userId, Long sessionId) {
        InterviewSession previous = latestCompletedRound(userId, sessionId);
        List<InterviewQuestion> asked = sessionQuestionRepository
                .findBySession_SessionIdOrderByDisplayOrderAsc(sessionId).stream()
                .map(InterviewSessionQuestion::getQuestion)
                .toList();
        if (previous.getMode() == InterviewSessionMode.PERSONALITY) {
            Long setId = questionGenerationV2Service.generatePersonality(userId, asked.stream()
                    .map(InterviewQuestion::getQuestionKey).collect(Collectors.toSet())).getQuestionSetId();
            return startRound(previous, setId);
        }
        Long analysisCaseId = previous.getSnapshot().getAnalysisCase().getAnalysisCaseId();
        List<String> avoid = asked.stream().map(InterviewQuestion::getQuestion).toList();
        return ticketService.spend(userId, TicketUsage.QUESTION_REGENERATE,
                ticketService.cost(TicketUsage.QUESTION_REGENERATE),
                () -> startRound(previous, questionGenerationV2Service
                        .generate(userId, previous.getMode(), analysisCaseId, avoid).getQuestionSetId()));
    }

    private SessionResponse startRound(InterviewSession previous, Long newQuestionSetId) {
        QuestionSet set = questionSetRepository.findById(newQuestionSetId).orElseThrow();
        return startRound(previous, set, questionRepository.findByQuestionSet_QuestionSetIdOrderByDisplayOrderAsc(newQuestionSetId));
    }

    private SessionResponse startRound(InterviewSession previous, QuestionSet set, List<InterviewQuestion> questions) {
        InterviewSession session = sessionRepository.save(InterviewSession.nextRound(previous, set));
        int order = 1;
        for (InterviewQuestion question : questions) {
            InterviewSessionQuestion snapshot = sessionQuestionRepository.save(
                    InterviewSessionQuestion.snapshot(session, question, order++));
            messageRepository.save(InterviewMessage.originalQuestion(snapshot));
        }
        return SessionResponse.from(session, questions.size());
    }

    // ponytail: 두 번 눌러 동시에 들어오면 둘 다 통과할 수 있음. 문제가 되면 앞 회차 행을 잠그고 검사
    private InterviewSession latestCompletedRound(Long userId, Long sessionId) {
        InterviewSession session = sessionRepository.findBySessionIdAndUser_UserIdAndDeletedAtIsNull(sessionId, userId)
                .orElseThrow(() -> new CustomException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND));
        if (!session.isCompleted() || !session.getMode().isV2()) {
            throw new CustomException(ErrorCode.ROUND_NOT_ALLOWED);
        }
        if (sessionRepository.existsByPreviousSession_SessionIdAndStatusNot(sessionId, InterviewSessionStatus.CANCELED)) {
            throw new CustomException(ErrorCode.ROUND_ALREADY_EXISTS);
        }
        return session;
    }
}
