package com.example.jobpuzzle.evaluation.v2;

import com.example.jobpuzzle.ai.dto.AnswerEvaluationV2Result;
import com.example.jobpuzzle.ai.log.AiCallLogErrorType;
import com.example.jobpuzzle.ai.validation.AiProcessingException;
import com.example.jobpuzzle.interview.entity.InterviewSessionMode;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 평가 명세 v2 5장 서버 검증: 항목 구성(해당 모드 6개, 빠짐·중복·다른 모드 항목 없음), 점수 1~5 정수, 필수 필드.
 * 실패하면 RESPONSE_VALIDATION_FAILED를 던져 호출 쪽이 다시 부르게 한다.
 */
public final class AnswerEvaluationV2Validator {

    private AnswerEvaluationV2Validator() {
    }

    /** 검증을 통과하면 항목별 점수를 모드의 항목 순서대로 돌려준다. */
    public static Map<EvaluationCriterion, Integer> validate(
            AnswerEvaluationV2Result result, InterviewSessionMode mode, boolean comparisonExpected
    ) {
        if (result == null) throw invalid("응답이 비어 있음");
        if (!Integer.valueOf(2).equals(result.evaluationVersion())) throw invalid("evaluationVersion은 2여야 함");
        if (!mode.name().equals(result.mode())) throw invalid("mode 불일치: " + result.mode() + " (기대 " + mode + ")");
        if (result.isEvasive() == null) throw invalid("isEvasive 누락");
        if (result.scores() == null) throw invalid("scores 누락");

        List<EvaluationCriterion> expected = EvaluationCriterion.forMode(mode);
        Set<String> expectedNames = new HashSet<>(expected.stream().map(Enum::name).toList());
        Map<EvaluationCriterion, Integer> scores = new LinkedHashMap<>();
        for (AnswerEvaluationV2Result.Score item : result.scores()) {
            if (item == null || item.criterion() == null) throw invalid("criterion 누락");
            if (!expectedNames.contains(item.criterion())) {
                boolean otherMode = Arrays.stream(EvaluationCriterion.values()).anyMatch(c -> c.name().equals(item.criterion()));
                throw invalid((otherMode ? "다른 모드의 평가 항목: " : "알 수 없는 평가 항목: ") + item.criterion());
            }
            EvaluationCriterion criterion = EvaluationCriterion.valueOf(item.criterion());
            if (scores.containsKey(criterion)) throw invalid("평가 항목 중복: " + criterion);
            scores.put(criterion, integerScore(criterion, item.score()));
            if (isBlank(item.reason())) throw invalid("점수 근거 누락: " + criterion);
        }
        if (scores.size() != expected.size()) {
            throw invalid("평가 항목 누락: " + expected.stream().filter(c -> !scores.containsKey(c)).toList());
        }

        if (isBlank(result.strengths()) || isBlank(result.improvements()) || isBlank(result.recommendedAnswer())) {
            throw invalid("strengths·improvements·recommendedAnswer는 비어 있으면 안 됨");
        }
        if (comparisonExpected) {
            if (result.comparison() == null || isBlank(result.comparison().improved()) || isBlank(result.comparison().toImprove())) {
                throw invalid("다회차 평가에는 comparison이 필요함");
            }
        } else if (result.comparison() != null) {
            throw invalid("첫 회차 평가의 comparison은 null이어야 함");
        }
        if (result.followUp() != null && isBlank(result.followUp().question())) {
            throw invalid("followUp이 있으면 question이 필요함");
        }

        Map<EvaluationCriterion, Integer> ordered = new LinkedHashMap<>();
        expected.forEach(c -> ordered.put(c, scores.get(c)));
        return ordered;
    }

    private static int integerScore(EvaluationCriterion criterion, BigDecimal score) {
        if (score == null) throw invalid("점수 누락: " + criterion);
        if (score.stripTrailingZeros().scale() > 0) throw invalid("점수는 정수여야 함: " + criterion + "=" + score);
        int value = score.intValue();
        if (value < 1 || value > 5) throw invalid("점수 범위(1~5) 밖: " + criterion + "=" + score);
        return value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static AiProcessingException invalid(String message) {
        return new AiProcessingException(AiCallLogErrorType.RESPONSE_VALIDATION_FAILED, "JSON-06 v2 " + message);
    }
}
