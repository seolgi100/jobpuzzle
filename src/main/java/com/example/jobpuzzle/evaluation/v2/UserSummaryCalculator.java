package com.example.jobpuzzle.evaluation.v2;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 평가 명세 v2 4-3 사용자 종합 평가. AI 없이 데이터로만 계산한다.
 * - 최종 보고서(v2)가 minReports개 이상일 때만 만든다
 * - 강점·아쉬운 점: 역량 태그별 질문 점수 평균 상위·하위 topN개. 질문이 minQuestionsPerTag개 미만인 태그는 제외
 * - 태그가 적어 상위와 하위가 겹치면 아쉬운 점에서 강점 태그를 뺀다
 * - 선호 직무: 분석·면접을 가장 많이 진행한 직무 (같으면 입력 순서가 앞선 것)
 */
public final class UserSummaryCalculator {

    private UserSummaryCalculator() {
    }

    /** 기준값. 명세 기본값은 보고서 5개, 상위·하위 2개, 태그당 질문 3개. 설정으로 바꾼다. */
    public record Settings(int minReports, int topN, int minQuestionsPerTag) {
        public static final Settings DEFAULT = new Settings(5, 2, 3);
    }

    /** 평가된 질문 하나: 붙은 역량 태그와 질문 점수(100점). */
    public record TaggedQuestion(Set<String> tags, int score) {
    }

    public record TagScore(String tag, int average, int questionCount) {
    }

    public record Summary(List<TagScore> strengths, List<TagScore> weaknesses, String preferredJob) {
    }

    /** jobActivity: 직무별 분석·면접 진행 횟수. 순서를 지키는 맵(LinkedHashMap 등)으로 넘긴다. */
    public static Optional<Summary> calculate(Settings settings, int reportCount, List<TaggedQuestion> questions,
                                              Map<String, Integer> jobActivity) {
        if (reportCount < settings.minReports()) {
            return Optional.empty();
        }
        Map<String, List<Integer>> byTag = new LinkedHashMap<>();
        questions.forEach(q -> q.tags().forEach(tag -> byTag.computeIfAbsent(tag, t -> new ArrayList<>()).add(q.score())));

        List<TagScore> ranked = byTag.entrySet().stream()
                .filter(e -> e.getValue().size() >= settings.minQuestionsPerTag())
                .map(e -> new TagScore(e.getKey(), roundedAverage(e.getValue()), e.getValue().size()))
                .sorted(Comparator.comparingInt(TagScore::average).reversed().thenComparing(TagScore::tag))
                .toList();

        List<TagScore> strengths = ranked.stream().limit(settings.topN()).toList();
        List<TagScore> weaknesses = ranked.reversed().stream()
                .filter(t -> !strengths.contains(t))
                .limit(settings.topN())
                .toList();
        String preferredJob = jobActivity.entrySet().stream()
                .reduce((best, next) -> next.getValue() > best.getValue() ? next : best)
                .map(Map.Entry::getKey)
                .orElse(null);
        return Optional.of(new Summary(strengths, weaknesses, preferredJob));
    }

    private static int roundedAverage(List<Integer> values) {
        long sum = values.stream().mapToLong(Integer::longValue).sum();
        return (int) ((sum * 2 + values.size()) / (values.size() * 2L));
    }
}
