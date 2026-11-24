package com.example.jobpuzzle.evaluation.v2;

import com.example.jobpuzzle.ai.log.AiCallLogErrorType;
import com.example.jobpuzzle.ai.validation.AiProcessingException;
import com.example.jobpuzzle.ai.validation.AiResponseProcessor;
import com.example.jobpuzzle.ai.validation.AnalysisSourceMarkerParser;
import com.example.jobpuzzle.interview.entity.InterviewSessionMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 명세 9-2 stub fixture(정상·비정상)로 v2 평가 응답 파싱·검증을 확인한다. */
public class AnswerEvaluationV2ValidatorTest {

    private final AiResponseProcessor processor = new AiResponseProcessor(new ObjectMapper(), new AnalysisSourceMarkerParser());

    public static String fixture(String path) {
        try (var stream = new ClassPathResource("ai-fixtures/answer-evaluation-v2/" + path).getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @ParameterizedTest
    @EnumSource(value = InterviewSessionMode.class, names = {"KNOWLEDGE", "MATERIAL", "PERSONALITY"})
    void 세_모드의_정상_stub_응답은_검증을_통과한다(InterviewSessionMode mode) {
        for (String kind : new String[]{"normal", "low"}) {
            var scores = AnswerEvaluationV2Validator.validate(
                    processor.parseAnswerEvaluationV2(fixture(mode + "-" + kind + ".json")), mode, false);
            assertThat(scores.keySet()).containsExactlyElementsOf(EvaluationCriterion.forMode(mode));
        }
    }

    @ParameterizedTest
    @CsvSource({
            "missing-criterion.json, 평가 항목 누락",
            "score-out-of-range.json, 점수 범위",
            "mixed-mode.json, 다른 모드의 평가 항목",
            "non-integer-score.json, 정수",
            "duplicate-criterion.json, 중복"
    })
    void 비정상_응답은_검증_실패로_거부한다(String file, String reason) {
        var result = processor.parseAnswerEvaluationV2(fixture("invalid/" + file));

        assertThatThrownBy(() -> AnswerEvaluationV2Validator.validate(result, InterviewSessionMode.MATERIAL, false))
                .isInstanceOfSatisfying(AiProcessingException.class, e -> {
                    assertThat(e.getErrorType()).isEqualTo(AiCallLogErrorType.RESPONSE_VALIDATION_FAILED);
                    assertThat(e.getMessage()).contains(reason);
                });
    }

    @Test
    void JSON_형식이_깨지면_파싱_실패로_거부한다() {
        assertThatThrownBy(() -> processor.parseAnswerEvaluationV2(fixture("invalid/malformed.json")))
                .isInstanceOfSatisfying(AiProcessingException.class,
                        e -> assertThat(e.getErrorType()).isEqualTo(AiCallLogErrorType.RESPONSE_PARSE_FAILED));
    }

    @Test
    void 요청한_모드와_다른_mode는_거부한다() {
        var result = processor.parseAnswerEvaluationV2(fixture("MATERIAL-normal.json"));

        assertThatThrownBy(() -> AnswerEvaluationV2Validator.validate(result, InterviewSessionMode.KNOWLEDGE, false))
                .isInstanceOf(AiProcessingException.class)
                .hasMessageContaining("mode 불일치");
    }

    @Test
    void 첫_회차인데_comparison이_있으면_거부하고_다회차인데_없으면_거부한다() {
        String withComparison = fixture("MATERIAL-normal.json")
                .replace("\"comparison\": null", "\"comparison\": {\"improved\": \"a\", \"toImprove\": \"b\"}");

        assertThatThrownBy(() -> AnswerEvaluationV2Validator.validate(
                processor.parseAnswerEvaluationV2(withComparison), InterviewSessionMode.MATERIAL, false))
                .hasMessageContaining("comparison");
        assertThat(AnswerEvaluationV2Validator.validate(
                processor.parseAnswerEvaluationV2(withComparison), InterviewSessionMode.MATERIAL, true)).hasSize(6);
        assertThatThrownBy(() -> AnswerEvaluationV2Validator.validate(
                processor.parseAnswerEvaluationV2(fixture("MATERIAL-normal.json")), InterviewSessionMode.MATERIAL, true))
                .hasMessageContaining("comparison");
    }
}
