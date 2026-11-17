package com.example.jobpuzzle.evaluation.v2;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 평가 명세 v2 3장 점수 계산. AI는 항목 점수(1~5)만 주고 평균·환산은 모두 여기서 한다.
 * - 답변 점수 = 6개 항목 평균, 질문 점수 = 본 답변·꼬리질문 답변 점수 평균
 * - 면접 총점 = 질문 점수 평균 × 20, 항목별 총점 = 질문별 항목 평균의 평균 × 20 (둘 다 소수 첫째 자리 반올림)
 * - 회피 답변은 전 항목 1점, 평가 실패(FAILED) 답변은 계산에서 제외
 * 항목별 총점도 질문 단위로 먼저 평균 내므로, 6개 항목 총점의 평균이 면접 총점과 같다 (반올림 차이 제외).
 * 평균의 평균이라 .5 경계가 소수 오차로 뒤집히지 않게 분수로 계산한다.
 */
public final class ScoreCalculator {

    private ScoreCalculator() {
    }

    /** 답변 하나. failed면 scores는 무시된다. evasive면 scores 대신 전 항목 1점. */
    public record Answer(Map<EvaluationCriterion, Integer> scores, boolean evasive, boolean failed) {
        public static Answer of(Map<EvaluationCriterion, Integer> scores) {
            return new Answer(scores, false, false);
        }

        public static Answer ofEvasive() {
            return new Answer(Map.of(), true, false);
        }

        public static Answer ofFailed() {
            return new Answer(Map.of(), false, true);
        }
    }

    /** 질문 하나 = 본 답변 + 꼬리질문 답변들. */
    public record Question(List<Answer> answers) {
    }

    /**
     * 모든 점수는 100점 척도. questionScores는 질문 순서대로이고 답변이 모두 실패한 질문은 null.
     * 평가된 질문이 하나도 없으면 total은 null, criterionTotals는 빈 맵.
     */
    public record Result(List<Integer> questionScores, Integer total, Map<EvaluationCriterion, Integer> criterionTotals) {
    }

    public static Result calculate(List<EvaluationCriterion> criteria, List<Question> questions) {
        List<Integer> questionScores = new ArrayList<>();
        List<Ratio> evaluated = new ArrayList<>();
        Map<EvaluationCriterion, List<Ratio>> perCriterion = new LinkedHashMap<>();
        criteria.forEach(c -> perCriterion.put(c, new ArrayList<>()));

        for (Question question : questions) {
            List<Map<EvaluationCriterion, Integer>> answers = question.answers().stream()
                    .filter(a -> !a.failed())
                    .map(a -> resolve(criteria, a))
                    .toList();
            if (answers.isEmpty()) {
                questionScores.add(null);
                continue;
            }
            Ratio score = average(answers.stream().map(a -> average(a.values().stream().map(Ratio::of).toList())).toList());
            evaluated.add(score);
            questionScores.add(score.toHundred());
            for (EvaluationCriterion c : criteria) {
                perCriterion.get(c).add(average(answers.stream().map(a -> Ratio.of(a.get(c))).toList()));
            }
        }

        if (evaluated.isEmpty()) {
            return new Result(questionScores, null, Map.of());
        }
        Map<EvaluationCriterion, Integer> criterionTotals = new LinkedHashMap<>();
        perCriterion.forEach((c, values) -> criterionTotals.put(c, average(values).toHundred()));
        return new Result(questionScores, average(evaluated).toHundred(), criterionTotals);
    }

    private static Map<EvaluationCriterion, Integer> resolve(List<EvaluationCriterion> criteria, Answer answer) {
        Map<EvaluationCriterion, Integer> scores = new LinkedHashMap<>();
        for (EvaluationCriterion c : criteria) {
            Integer score = answer.evasive() ? Integer.valueOf(1) : answer.scores().get(c);
            if (score == null || score < 1 || score > 5) {
                throw new IllegalArgumentException("항목 점수가 없거나 1~5 밖: " + c + "=" + score);
            }
            scores.put(c, score);
        }
        return scores;
    }

    private static Ratio average(List<Ratio> values) {
        Ratio sum = values.stream().reduce(Ratio.of(0), Ratio::plus);
        return new Ratio(sum.num, sum.den * values.size()).reduced();
    }

    /** 1~5 점수용 작은 분수. 분모는 (항목 수 × 답변 수 × 질문 수)의 곱 정도라 long으로 충분하다. */
    private record Ratio(long num, long den) {
        static Ratio of(long value) {
            return new Ratio(value, 1);
        }

        Ratio plus(Ratio other) {
            return new Ratio(num * other.den + other.num * den, den * other.den).reduced();
        }

        Ratio reduced() {
            long g = gcd(num, den);
            return g == 0 ? this : new Ratio(num / g, den / g);
        }

        /** × 20 후 소수 첫째 자리에서 반올림 (양수만 다룸). */
        int toHundred() {
            return (int) ((num * 20 * 2 + den) / (den * 2));
        }

        private static long gcd(long a, long b) {
            return b == 0 ? Math.abs(a) : gcd(b, a % b);
        }
    }
}
