package com.example.jobpuzzle.interview.service;

import com.example.jobpuzzle.evaluation.entity.WeaknessTagResolveStatus;
import com.example.jobpuzzle.evaluation.repository.AnswerEvaluationRepository;
import com.example.jobpuzzle.evaluation.repository.WeaknessTagLogRepository;
import com.example.jobpuzzle.evaluation.repository.WeaknessTagStatusRepository;
import com.example.jobpuzzle.evaluation.service.AnswerEvaluationService;
import com.example.jobpuzzle.evaluation.service.WeaknessTagNormalizer;
import com.example.jobpuzzle.analysis.service.CompanyFitQuestionSetQueryService;
import com.example.jobpuzzle.global.error.CustomException;
import com.example.jobpuzzle.global.error.ErrorCode;
import com.example.jobpuzzle.interview.dto.SessionCreateRequest;
import com.example.jobpuzzle.interview.dto.AnswerSubmitRequest;
import com.example.jobpuzzle.interview.dto.AnswerSubmitResponse;
import com.example.jobpuzzle.interview.entity.AnswerType;
import com.example.jobpuzzle.interview.entity.InterviewMessage;
import com.example.jobpuzzle.interview.entity.InterviewMessageType;
import com.example.jobpuzzle.interview.entity.InterviewQuestion;
import com.example.jobpuzzle.interview.entity.InterviewSessionQuestion;
import com.example.jobpuzzle.interview.entity.InterviewSessionQuestionStatus;
import com.example.jobpuzzle.interview.entity.InterviewSessionMode;
import com.example.jobpuzzle.interview.entity.InterviewSession;
import com.example.jobpuzzle.interview.entity.QuestionSet;
import com.example.jobpuzzle.interview.entity.QuestionSetStatus;
import com.example.jobpuzzle.interview.repository.FollowUpQuestionRepository;
import com.example.jobpuzzle.interview.repository.InterviewMessageRepository;
import com.example.jobpuzzle.interview.repository.InterviewQuestionRepository;
import com.example.jobpuzzle.interview.repository.InterviewSessionQuestionRepository;
import com.example.jobpuzzle.interview.repository.InterviewSessionRepository;
import com.example.jobpuzzle.interview.repository.QuestionSetRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.isNull;

@ExtendWith(MockitoExtension.class)
class InterviewSessionLifecycleServiceTest {

    @Mock private QuestionSetRepository questionSetRepository;
    @Mock private InterviewQuestionRepository interviewQuestionRepository;
    @Mock private InterviewSessionRepository interviewSessionRepository;
    @Mock private InterviewSessionQuestionRepository sessionQuestionRepository;
    @Mock private InterviewMessageRepository interviewMessageRepository;
    @Mock private FollowUpQuestionRepository followUpQuestionRepository;
    @Mock private CompanyFitQuestionSetQueryService companyFitQuestionSetQueryService;
    @Mock private AnswerEvaluationService answerEvaluationService;
    @Mock private AnswerEvaluationRepository answerEvaluationRepository;
    @Mock private WeaknessTagStatusRepository weaknessTagStatusRepository;
    @Mock private WeaknessTagLogRepository weaknessTagLogRepository;
    @Mock private WeaknessTagNormalizer weaknessTagNormalizer;

    @InjectMocks private InterviewSessionService service;

    @Test
    void canceledOrCompletedSessionStillPreventsQuestionSetReuse() {
        QuestionSet questionSet = mock(QuestionSet.class);
        when(questionSet.getQuestionSetId()).thenReturn(41L);
        when(questionSet.isActive()).thenReturn(true);
        when(questionSetRepository.findByQuestionSetIdAndUser_UserId(41L, 7L))
                .thenReturn(Optional.of(questionSet));
        when(interviewSessionRepository.existsByQuestionSet_QuestionSetId(41L)).thenReturn(true);
        SessionCreateRequest request = new SessionCreateRequest();
        ReflectionTestUtils.setField(request, "questionSetId", 41L);
        ReflectionTestUtils.setField(request, "selectedQuestionIds", List.of(101L));

        assertThatThrownBy(() -> service.createSession(7L, request))
                .isInstanceOf(CustomException.class)
                .extracting(error -> ((CustomException) error).getErrorCode())
                .isEqualTo(ErrorCode.INTERVIEW_SESSION_ALREADY_EXISTS);

        verify(interviewSessionRepository).existsByQuestionSet_QuestionSetId(41L);
    }

    @Test
    void completedSessionCannotBeStartedAgain() {
        InterviewSession completed = mock(InterviewSession.class);
        when(completed.isEditable()).thenReturn(false);
        when(interviewSessionRepository.findBySessionIdAndUser_UserIdAndDeletedAtIsNull(90L, 7L))
                .thenReturn(Optional.of(completed));

        assertThatThrownBy(() -> service.startSession(7L, 90L))
                .isInstanceOf(CustomException.class)
                .extracting(error -> ((CustomException) error).getErrorCode())
                .isEqualTo(ErrorCode.SESSION_NOT_EDITABLE);
    }

    @Test
    void unusableAnswerUsesRetryAnswerFlowWithoutCallingAiEvaluation() {
        InterviewSessionQuestion sessionQuestion = mock(InterviewSessionQuestion.class);
        InterviewSession session = mock(InterviewSession.class);
        InterviewQuestion question = mock(InterviewQuestion.class);
        when(sessionQuestion.getSession()).thenReturn(session);
        when(sessionQuestion.getSessionQuestionId()).thenReturn(13L);
        when(sessionQuestion.getQuestion()).thenReturn(question);
        when(question.getQuestionId()).thenReturn(101L);
        when(session.isEditable()).thenReturn(true);
        when(sessionQuestionRepository.findBySessionQuestionIdAndSession_User_UserId(13L, 7L))
                .thenReturn(Optional.of(sessionQuestion));

        InterviewMessage originalQuestion = InterviewMessage.originalQuestion(sessionQuestion);
        when(interviewMessageRepository.findBySessionQuestion_SessionQuestionIdOrderByMessageIdAsc(13L))
                .thenReturn(List.of(originalQuestion));
        when(interviewMessageRepository.countByParentMessage_MessageIdAndSenderAndMessageTypeIn(
                isNull(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyList()
        )).thenReturn(0L);
        when(interviewMessageRepository.existsByParentMessage_MessageIdAndSenderAndMessageTypeIn(
                isNull(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyList()
        )).thenReturn(false);
        when(interviewMessageRepository.save(org.mockito.ArgumentMatchers.any(InterviewMessage.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AnswerSubmitRequest request = new AnswerSubmitRequest();
        ReflectionTestUtils.setField(request, "messageText", "기모띠");
        ReflectionTestUtils.setField(request, "answerType", AnswerType.ORIGINAL_ANSWER);

        AnswerSubmitResponse response = service.submitAnswer(7L, 13L, request);

        assertThat(response.isRetryAnswerRequired()).isTrue();
        assertThat(response.isEvaluationRetryRequired()).isFalse();
        verify(answerEvaluationService, never()).evaluateAnswer(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void evaluationFailureDoesNotConsumeAUserRetry() {
        InterviewSessionQuestion sessionQuestion = mock(InterviewSessionQuestion.class);
        InterviewSession session = mock(InterviewSession.class);
        InterviewQuestion question = mock(InterviewQuestion.class);
        when(sessionQuestion.getSession()).thenReturn(session);
        when(sessionQuestion.getSessionQuestionId()).thenReturn(14L);
        when(sessionQuestion.getQuestion()).thenReturn(question);
        when(question.getQuestionId()).thenReturn(102L);
        when(session.isEditable()).thenReturn(true);
        when(sessionQuestionRepository.findBySessionQuestionIdAndSession_User_UserId(14L, 7L))
                .thenReturn(Optional.of(sessionQuestion));

        InterviewMessage originalQuestion = InterviewMessage.originalQuestion(sessionQuestion);
        when(interviewMessageRepository.findBySessionQuestion_SessionQuestionIdOrderByMessageIdAsc(14L))
                .thenReturn(List.of(originalQuestion));
        when(interviewMessageRepository.countByParentMessage_MessageIdAndSenderAndMessageTypeIn(
                isNull(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyList()
        )).thenAnswer(invocation -> {
            List<?> types = invocation.getArgument(2);
            return types.contains(InterviewMessageType.EVALUATION_FAILED_ANSWER) ? 2L : 0L;
        });
        when(interviewMessageRepository.existsByParentMessage_MessageIdAndSenderAndMessageTypeIn(
                isNull(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyList()
        )).thenReturn(false);
        when(interviewMessageRepository.save(org.mockito.ArgumentMatchers.any(InterviewMessage.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(answerEvaluationService.evaluateAnswer(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new AnswerEvaluationService.EvaluationOutcome(null, null, true, "trace"));

        AnswerSubmitRequest request = new AnswerSubmitRequest();
        ReflectionTestUtils.setField(request, "messageText", "인증 실패 시 사용자가 다시 시도할 수 있도록 안내했습니다.");
        ReflectionTestUtils.setField(request, "answerType", AnswerType.ORIGINAL_ANSWER);

        AnswerSubmitResponse response = service.submitAnswer(7L, 14L, request);

        assertThat(response.isEvaluationRetryRequired()).isTrue();
        assertThat(response.isAnswerAttemptsExhausted()).isFalse();
    }

    @Test
    void unansweredQuestionCanBeSkippedDuringInterview() {
        InterviewSession session = mock(InterviewSession.class);
        when(session.isEditable()).thenReturn(true);
        InterviewSessionQuestion sessionQuestion =
                InterviewSessionQuestion.snapshot(session, mock(InterviewQuestion.class), 1);
        when(sessionQuestionRepository.findBySessionQuestionIdAndSession_User_UserId(21L, 7L))
                .thenReturn(Optional.of(sessionQuestion));
        when(interviewMessageRepository.findBySessionQuestion_SessionQuestionIdAndMessageType(
                21L, InterviewMessageType.ORIGINAL_ANSWER)).thenReturn(Optional.empty());

        service.skipQuestion(7L, 21L);

        assertThat(sessionQuestion.getStatus()).isEqualTo(InterviewSessionQuestionStatus.SKIPPED);
        verify(session).touch();
    }

    @Test
    void answeredQuestionCannotBeSkipped() {
        InterviewSessionQuestion sessionQuestion = mock(InterviewSessionQuestion.class);
        InterviewSession session = mock(InterviewSession.class);
        when(sessionQuestion.getSession()).thenReturn(session);
        when(sessionQuestion.getStatus()).thenReturn(InterviewSessionQuestionStatus.IN_PROGRESS);
        when(session.isEditable()).thenReturn(true);
        when(sessionQuestionRepository.findBySessionQuestionIdAndSession_User_UserId(22L, 7L))
                .thenReturn(Optional.of(sessionQuestion));
        when(interviewMessageRepository.findBySessionQuestion_SessionQuestionIdAndMessageType(
                22L, InterviewMessageType.ORIGINAL_ANSWER))
                .thenReturn(Optional.of(mock(InterviewMessage.class)));

        assertThatThrownBy(() -> service.skipQuestion(7L, 22L))
                .isInstanceOf(CustomException.class)
                .extracting(error -> ((CustomException) error).getErrorCode())
                .isEqualTo(ErrorCode.ANSWER_ALREADY_CONFIRMED);
        verify(sessionQuestion, never()).skip();
    }
}
