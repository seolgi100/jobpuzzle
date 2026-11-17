package com.example.jobpuzzle.evaluation.service;

import com.example.jobpuzzle.ai.client.AiClient;
import com.example.jobpuzzle.ai.config.AiGenerationProperties;
import com.example.jobpuzzle.ai.log.*;
import com.example.jobpuzzle.ai.prompt.PromptTemplate;
import com.example.jobpuzzle.ai.prompt.PromptTemplateRepository;
import com.example.jobpuzzle.ai.service.AiClientService;
import com.example.jobpuzzle.ai.service.GenerationClientSelection;
import com.example.jobpuzzle.ai.validation.AiProcessingException;
import com.example.jobpuzzle.ai.validation.AiResponseProcessor;
import com.example.jobpuzzle.ai.validation.AnalysisSourceMarkerParser;
import com.example.jobpuzzle.evaluation.entity.AnswerEvaluation;
import com.example.jobpuzzle.evaluation.repository.AnswerEvaluationRepository;
import com.example.jobpuzzle.interview.entity.*;
import com.example.jobpuzzle.interview.repository.FollowUpQuestionRepository;
import com.example.jobpuzzle.interview.repository.InterviewMessageRepository;
import com.example.jobpuzzle.interview.repository.InterviewSessionQuestionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static com.example.jobpuzzle.evaluation.v2.AnswerEvaluationV2ValidatorTest.fixture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 명세 9-5 2단계: 검증 실패 → 재호출 → 횟수 초과 시 FAILED, 타임아웃 → 실패 처리. stub 응답(fixture)으로 확인한다. */
class AnswerEvaluationV2ServiceTest {

    private final AnswerEvaluationRepository evaluations = mock(AnswerEvaluationRepository.class);
    private final InterviewMessageRepository messages = mock(InterviewMessageRepository.class);
    private final FollowUpQuestionRepository followUps = mock(FollowUpQuestionRepository.class);
    private final AiCallLogRepository callLogs = mock(AiCallLogRepository.class);
    private final AiClientService aiClientService = mock(AiClientService.class);
    private final AiGenerationProperties properties = new AiGenerationProperties();
    private final InterviewMessage answer = mock(InterviewMessage.class);
    private AnswerEvaluationV2Service service;
    private GenerationClientSelection selection;

    @BeforeEach
    void setUp() {
        PromptTemplateRepository templates = mock(PromptTemplateRepository.class);
        when(templates.findFirstByTargetJsonAndIsActiveTrueOrderByPromptTemplateIdDesc("JSON-06-V2")).thenReturn(Optional.of(
                PromptTemplate.builder().promptCode("T").name("t").targetJson("JSON-06-V2").templateText("평가 지시").build()));
        selection = new GenerationClientSelection(AiExecutionStage.ANSWER_EVALUATION, mock(AiClient.class), AiProvider.MOCK, "stub", 4096);
        when(aiClientService.resolve(AiExecutionStage.ANSWER_EVALUATION)).thenReturn(selection);
        when(evaluations.save(any())).thenAnswer(i -> i.getArgument(0));
        when(callLogs.save(any())).thenAnswer(i -> i.getArgument(0));
        when(messages.save(any())).thenAnswer(i -> i.getArgument(0));

        InterviewSession session = mock(InterviewSession.class);
        when(session.getMode()).thenReturn(InterviewSessionMode.MATERIAL);
        InterviewSessionQuestion question = mock(InterviewSessionQuestion.class);
        when(question.getSession()).thenReturn(session);
        when(question.getSessionQuestionId()).thenReturn(10L);
        when(question.getQuestionTextSnapshot()).thenReturn("성능을 개선한 경험을 말해 주십시오.");
        when(question.getIntentSnapshot()).thenReturn("본인 기여와 결과 확인");
        com.example.jobpuzzle.interview.entity.InterviewQuestion source = mock(com.example.jobpuzzle.interview.entity.InterviewQuestion.class);
        when(source.getCompetencyTags()).thenReturn(java.util.List.of("PROBLEM_SOLVING"));
        when(question.getQuestion()).thenReturn(source);
        when(answer.getSessionQuestion()).thenReturn(question);
        when(answer.getMessageId()).thenReturn(1L);
        when(answer.getMessageText()).thenReturn("API 응답 속도를 개선했습니다.");

        service = new AnswerEvaluationV2Service(evaluations, messages, mock(InterviewSessionQuestionRepository.class), followUps,
                templates, callLogs, aiClientService,
                new AiResponseProcessor(new ObjectMapper(), new AnalysisSourceMarkerParser()), properties, new ObjectMapper());
    }

    private AiCallLog savedCallLog() {
        ArgumentCaptor<AiCallLog> captor = ArgumentCaptor.forClass(AiCallLog.class);
        verify(callLogs).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void 정상_응답이면_6개_항목과_100점_환산_점수를_저장한다() {
        when(aiClientService.evaluateAnswer(eq(selection), anyString())).thenReturn(fixture("MATERIAL-normal.json"));

        var outcome = service.evaluate(answer);

        AnswerEvaluation saved = outcome.evaluation();
        assertThat(outcome.evaluationFailed()).isFalse();
        assertThat(saved.getEvaluationVersion()).isEqualTo(2);
        assertThat(saved.getEvaluationDetail()).hasSize(6).containsKeys("INTENT_UNDERSTANDING", "JOB_POSTING_ALIGNMENT");
        assertThat(saved.getScore()).isEqualTo(73); // (4+4+3+4+3+4)/6 × 20 = 73.3
        assertThat(outcome.followUpMessage()).isNull();
        assertThat(savedCallLog().getStatus()).isEqualTo(AiCallLogStatus.SUCCEEDED);
    }

    @Test
    void 검증에_실패하면_다시_불러_정상_응답을_쓴다() {
        when(aiClientService.evaluateAnswer(eq(selection), anyString())).thenReturn(
                fixture("invalid/missing-criterion.json"), fixture("invalid/mixed-mode.json"), fixture("MATERIAL-normal.json"));

        var outcome = service.evaluate(answer);

        assertThat(outcome.evaluationFailed()).isFalse();
        verify(aiClientService, times(3)).evaluateAnswer(eq(selection), anyString());
    }

    @Test
    void 재호출_횟수를_넘기면_FAILED로_남기고_저장하지_않는다() {
        when(aiClientService.evaluateAnswer(eq(selection), anyString())).thenReturn(fixture("invalid/score-out-of-range.json"));

        var outcome = service.evaluate(answer);

        assertThat(outcome.evaluationFailed()).isTrue();
        assertThat(outcome.failureTraceId()).isNotBlank();
        verify(aiClientService, times(3)).evaluateAnswer(eq(selection), anyString()); // 첫 호출 + 재호출 2번
        verify(evaluations, never()).save(any());
        AiCallLog log = savedCallLog();
        assertThat(log.getStatus()).isEqualTo(AiCallLogStatus.FAILED);
        assertThat(log.getErrorType()).isEqualTo(AiCallLogErrorType.RESPONSE_VALIDATION_FAILED);
    }

    @Test
    void 재호출_횟수는_설정으로_바꾼다() {
        properties.setMaxRetries(0);
        when(aiClientService.evaluateAnswer(eq(selection), anyString())).thenReturn(fixture("invalid/malformed.json"));

        assertThat(service.evaluate(answer).evaluationFailed()).isTrue();
        verify(aiClientService, times(1)).evaluateAnswer(eq(selection), anyString());
        assertThat(savedCallLog().getErrorType()).isEqualTo(AiCallLogErrorType.RESPONSE_PARSE_FAILED);
    }

    @Test
    void 시간_초과도_재호출하고_끝내_실패하면_FAILED() {
        when(aiClientService.evaluateAnswer(eq(selection), anyString()))
                .thenThrow(new AiProcessingException(AiCallLogErrorType.TIMEOUT, "provider timeout"));

        var outcome = service.evaluate(answer);

        assertThat(outcome.evaluationFailed()).isTrue();
        verify(aiClientService, times(3)).evaluateAnswer(eq(selection), anyString());
        assertThat(savedCallLog().getErrorType()).isEqualTo(AiCallLogErrorType.TIMEOUT);
    }

    @Test
    void 답변_점수가_3점5_미만이면_꼬리질문을_만든다() {
        when(aiClientService.evaluateAnswer(eq(selection), anyString())).thenReturn(fixture("MATERIAL-low.json"));

        var outcome = service.evaluate(answer);

        assertThat(outcome.evaluation().getScore()).isEqualTo(50); // (3+2+3+2+2+3)/6 × 20
        assertThat(outcome.followUpMessage()).isNotNull();
        verify(followUps).save(any());
    }

    @Test
    void 꼬리질문이_이미_2번이면_더_만들지_않는다() {
        when(messages.countBySessionQuestion_SessionQuestionIdAndMessageType(10L, InterviewMessageType.FOLLOW_UP_QUESTION)).thenReturn(2L);
        when(aiClientService.evaluateAnswer(eq(selection), anyString())).thenReturn(fixture("MATERIAL-low.json"));

        assertThat(service.evaluate(answer).followUpMessage()).isNull();
        verify(followUps, never()).save(any());
    }

    @Test
    void 회피_답변은_전_항목_1점으로_저장한다() {
        when(aiClientService.evaluateAnswer(eq(selection), anyString()))
                .thenReturn(fixture("MATERIAL-normal.json").replace("\"isEvasive\": false", "\"isEvasive\": true"));

        AnswerEvaluation saved = service.evaluate(answer).evaluation();

        assertThat(saved.getEvasive()).isTrue();
        assertThat(saved.getScore()).isEqualTo(20);
        assertThat(saved.getEvaluationDetail().values()).allSatisfy(d -> assertThat(d.getScore()).isEqualTo(1));
    }
}
