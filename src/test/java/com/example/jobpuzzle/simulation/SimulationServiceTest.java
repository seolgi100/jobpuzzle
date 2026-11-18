package com.example.jobpuzzle.simulation;

import com.example.jobpuzzle.ai.dto.AnswerEvaluationV2Result;
import com.example.jobpuzzle.ai.dto.QuestionGenerationV2Result;
import com.example.jobpuzzle.ai.validation.AiResponseProcessor;
import com.example.jobpuzzle.ai.validation.AnalysisSourceMarkerParser;
import com.example.jobpuzzle.evaluation.entity.AnswerEvaluation;
import com.example.jobpuzzle.evaluation.v2.AnswerEvaluationV2Validator;
import com.example.jobpuzzle.evaluation.v2.EvaluationCriterion;
import com.example.jobpuzzle.interview.entity.FollowUpQuestion;
import com.example.jobpuzzle.interview.entity.InterviewMessage;
import com.example.jobpuzzle.interview.entity.InterviewQuestion;
import com.example.jobpuzzle.interview.entity.InterviewSessionMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// 저장된 질문·평가를 AI 응답 모양으로 다시 만들면, 평소 파서·검증기를 그대로 통과해야 한다
class SimulationServiceTest {

    private final SimulationService service = new SimulationService(new SimulationProperties(), null, null, null, null,
            null, null, new ObjectMapper());
    private final AiResponseProcessor processor = new AiResponseProcessor(new ObjectMapper(), new AnalysisSourceMarkerParser());

    @Test
    void storedEvaluationPassesV2ValidatorWithFollowUpAndComparisonRule() {
        InterviewSessionMode mode = InterviewSessionMode.MATERIAL;
        Map<String, AnswerEvaluation.DimensionEvaluation> details = new LinkedHashMap<>();
        EvaluationCriterion.forMode(mode).forEach(c -> details.put(c.name(), new AnswerEvaluation.DimensionEvaluation(2, c.name() + " 근거")));
        AnswerEvaluation evaluation = mock(AnswerEvaluation.class);
        when(evaluation.getEvaluationMode()).thenReturn(mode);
        when(evaluation.getEvasive()).thenReturn(false);
        when(evaluation.getEvaluationDetail()).thenReturn(details);
        when(evaluation.getSummary()).thenReturn("잘한 점");
        when(evaluation.getImprovementDirection()).thenReturn(List.of("보완할 점"));
        when(evaluation.getRecommendedAnswer()).thenReturn("추천 답변");
        when(evaluation.getComparison()).thenReturn(Map.of("improved", "나아진 점", "toImprove", "더 개선할 점"));
        InterviewMessage followUpMessage = mock(InterviewMessage.class);
        when(followUpMessage.getMessageText()).thenReturn("수치로 말해 주시겠습니까?");
        FollowUpQuestion followUp = mock(FollowUpQuestion.class);
        when(followUp.getQuestionMessage()).thenReturn(followUpMessage);
        when(followUp.getReason()).thenReturn("성과 근거 부족");

        // 첫 회차: comparison을 빼야 검증 통과
        AnswerEvaluationV2Result first = processor.parseAnswerEvaluationV2(service.evaluationJson(evaluation, followUp, false));
        assertThat(AnswerEvaluationV2Validator.validate(first, mode, false)).hasSize(6).containsValue(2);
        assertThat(first.comparison()).isNull();
        assertThat(first.followUp().question()).isEqualTo("수치로 말해 주시겠습니까?");

        // 다음 회차: comparison이 있어야 검증 통과
        AnswerEvaluationV2Result next = processor.parseAnswerEvaluationV2(service.evaluationJson(evaluation, null, true));
        assertThat(AnswerEvaluationV2Validator.validate(next, mode, true)).hasSize(6);
        assertThat(next.comparison().improved()).isEqualTo("나아진 점");
        assertThat(next.followUp()).isNull();
    }

    @Test
    void storedQuestionsParseAsJson12() {
        InterviewQuestion question = mock(InterviewQuestion.class);
        when(question.getQuestion()).thenReturn("API 응답 속도를 어떻게 개선했습니까?");
        when(question.getIntent()).thenReturn("원인 분석 과정 확인");
        when(question.getCompetencyTags()).thenReturn(List.of("PROBLEM_SOLVING"));
        when(question.getRelatedRequirementId()).thenReturn(null);

        QuestionGenerationV2Result result = processor.parseQuestionGenerationV2(service.questionsJson(List.of(question)));

        assertThat(result.questions()).hasSize(1);
        assertThat(result.questions().get(0).question()).isEqualTo("API 응답 속도를 어떻게 개선했습니까?");
        assertThat(result.questions().get(0).competencyTags()).containsExactly("PROBLEM_SOLVING");
        assertThat(result.questions().get(0).relatedRequirementId()).isNull();
    }
}
