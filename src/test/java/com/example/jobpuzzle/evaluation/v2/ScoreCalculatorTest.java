package com.example.jobpuzzle.evaluation.v2;

import com.example.jobpuzzle.evaluation.v2.ScoreCalculator.Answer;
import com.example.jobpuzzle.evaluation.v2.ScoreCalculator.Question;
import com.example.jobpuzzle.interview.entity.InterviewSessionMode;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.example.jobpuzzle.evaluation.v2.EvaluationCriterion.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScoreCalculatorTest {

    private static final List<EvaluationCriterion> MATERIAL_CRITERIA = EvaluationCriterion.forMode(InterviewSessionMode.MATERIAL);

    /** 자료 기반 6개 항목 점수를 선언 순서대로 받는다. */
    private static Answer answer(int... scores) {
        Map<EvaluationCriterion, Integer> map = new LinkedHashMap<>();
        for (int i = 0; i < scores.length; i++) {
            map.put(MATERIAL_CRITERIA.get(i), scores[i]);
        }
        return Answer.of(map);
    }

    private static Question question(Answer... answers) {
        return new Question(Arrays.asList(answers));
    }

    @Test
    void 모드마다_공통_3개와_모드별_3개_항목을_쓴다() {
        assertThat(EvaluationCriterion.forMode(InterviewSessionMode.KNOWLEDGE)).containsExactly(
                INTENT_UNDERSTANDING, LOGICAL_STRUCTURE, CLARITY, CONCEPT_ACCURACY, PRINCIPLE_UNDERSTANDING, PRACTICAL_APPLICATION);
        assertThat(MATERIAL_CRITERIA).containsExactly(
                INTENT_UNDERSTANDING, LOGICAL_STRUCTURE, CLARITY, EXPERIENCE_SPECIFICITY, OUTCOME, JOB_POSTING_ALIGNMENT);
        assertThat(EvaluationCriterion.forMode(InterviewSessionMode.PERSONALITY)).containsExactly(
                INTENT_UNDERSTANDING, LOGICAL_STRUCTURE, CLARITY, ROLE_FIT, AUTHENTICITY, SELF_AWARENESS);
        assertThatThrownBy(() -> EvaluationCriterion.forMode(InterviewSessionMode.WEAKNESS_REVIEW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 답변_점수는_6개_항목_평균이고_총점은_평균_곱하기_20() {
        // 답변 평균 (4+4+3+4+3+4)/6 = 22/6 = 3.666.. → 73.33 → 73
        var result = ScoreCalculator.calculate(MATERIAL_CRITERIA, List.of(question(answer(4, 4, 3, 4, 3, 4))));

        assertThat(result.questionScores()).containsExactly(73);
        assertThat(result.total()).isEqualTo(73);
    }

    @Test
    void 질문_점수는_본_답변과_꼬리질문_답변_평균이다() {
        // 본 답변 3.0, 꼬리질문 답변 4.0 → 질문 3.5 → 70
        var result = ScoreCalculator.calculate(MATERIAL_CRITERIA, List.of(
                question(answer(3, 3, 3, 3, 3, 3), answer(4, 4, 4, 4, 4, 4))));

        assertThat(result.questionScores()).containsExactly(70);
    }

    @Test
    void 총점은_질문_점수_평균이라_꼬리질문_수가_가중치가_되지_않는다() {
        // 질문1 = (2 + 4) / 2 = 3, 질문2 = 5 → (3 + 5) / 2 = 4 → 80
        // 답변 3개를 그냥 평균하면 (2 + 4 + 5) / 3 = 3.67 → 73 이 되므로 다르다
        var result = ScoreCalculator.calculate(MATERIAL_CRITERIA, List.of(
                question(answer(2, 2, 2, 2, 2, 2), answer(4, 4, 4, 4, 4, 4)),
                question(answer(5, 5, 5, 5, 5, 5))));

        assertThat(result.total()).isEqualTo(80);
    }

    @Test
    void 소수_첫째_자리에서_반올림하고_5는_올린다() {
        // 질문1 = 3 (60), 질문2 = (3 + 23/6) / 2 = 41/12 (68.33 → 68), 총점 77/24 × 20 = 64.17 → 64
        var result = ScoreCalculator.calculate(MATERIAL_CRITERIA, List.of(
                question(answer(3, 3, 3, 3, 3, 3)),
                question(answer(3, 3, 3, 3, 3, 3), answer(4, 4, 4, 4, 3, 4))));
        assertThat(result.questionScores()).containsExactly(60, 68);
        assertThat(result.total()).isEqualTo(64);

        // 정확히 .5에 걸리는 경우: (3 + 19/6 + 3 + 20/6) / 4 = 3.125 → × 20 = 62.5 → 63
        var tie = ScoreCalculator.calculate(MATERIAL_CRITERIA, List.of(
                question(answer(3, 3, 3, 3, 3, 3)),
                question(answer(3, 3, 3, 3, 3, 4)),
                question(answer(3, 3, 3, 3, 3, 3)),
                question(answer(3, 3, 3, 3, 4, 4))));
        assertThat(tie.total()).isEqualTo(63);
    }

    @Test
    void 항목별_총점은_항목_평균_곱하기_20() {
        var result = ScoreCalculator.calculate(MATERIAL_CRITERIA, List.of(
                question(answer(5, 4, 3, 2, 1, 5)),
                question(answer(3, 4, 3, 4, 1, 4))));

        assertThat(result.criterionTotals())
                .containsEntry(INTENT_UNDERSTANDING, 80)
                .containsEntry(LOGICAL_STRUCTURE, 80)
                .containsEntry(CLARITY, 60)
                .containsEntry(EXPERIENCE_SPECIFICITY, 60)
                .containsEntry(OUTCOME, 20)
                .containsEntry(JOB_POSTING_ALIGNMENT, 90)
                .hasSize(6);
    }

    @Test
    void 회피_답변은_전_항목_1점() {
        var result = ScoreCalculator.calculate(MATERIAL_CRITERIA, List.of(question(Answer.ofEvasive())));

        assertThat(result.total()).isEqualTo(20);
        assertThat(result.criterionTotals().values()).containsOnly(20);
    }

    @Test
    void 평가_실패_답변은_점수_계산에서_빠진다() {
        // 꼬리질문 답변이 실패해도 본 답변(4.0)만으로 질문 점수를 낸다
        var result = ScoreCalculator.calculate(MATERIAL_CRITERIA, List.of(
                question(answer(4, 4, 4, 4, 4, 4), Answer.ofFailed()),
                question(Answer.ofFailed())));

        assertThat(result.questionScores()).containsExactly(80, null);
        assertThat(result.total()).isEqualTo(80);
    }

    @Test
    void 평가된_답변이_하나도_없으면_총점이_없다() {
        var result = ScoreCalculator.calculate(MATERIAL_CRITERIA, List.of(question(Answer.ofFailed())));

        assertThat(result.total()).isNull();
        assertThat(result.criterionTotals()).isEmpty();
    }

    @Test
    void 항목이_빠졌거나_1에서_5_밖이면_거부한다() {
        assertThatThrownBy(() -> ScoreCalculator.calculate(MATERIAL_CRITERIA, List.of(question(answer(4, 4, 4, 4, 4)))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ScoreCalculator.calculate(MATERIAL_CRITERIA, List.of(question(answer(6, 4, 4, 4, 4, 4)))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
