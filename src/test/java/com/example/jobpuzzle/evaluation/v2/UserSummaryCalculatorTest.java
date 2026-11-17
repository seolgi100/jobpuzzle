package com.example.jobpuzzle.evaluation.v2;

import com.example.jobpuzzle.evaluation.v2.UserSummaryCalculator.Settings;
import com.example.jobpuzzle.evaluation.v2.UserSummaryCalculator.TagScore;
import com.example.jobpuzzle.evaluation.v2.UserSummaryCalculator.TaggedQuestion;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class UserSummaryCalculatorTest {

    private static final Map<String, Integer> JOBS = new LinkedHashMap<>(Map.of("백엔드 개발", 4));

    /** 같은 태그가 붙은 질문을 점수별로 만든다. */
    private static List<TaggedQuestion> tagged(String tag, int... scores) {
        List<TaggedQuestion> list = new ArrayList<>();
        for (int score : scores) {
            list.add(new TaggedQuestion(Set.of(tag), score));
        }
        return list;
    }

    private static List<TaggedQuestion> sample() {
        List<TaggedQuestion> all = new ArrayList<>();
        all.addAll(tagged("COMMUNICATION", 90, 80, 85));     // 85
        all.addAll(tagged("REASONING", 70, 72, 74));         // 72
        all.addAll(tagged("PROBLEM_SOLVING", 60, 50, 55));   // 55
        all.addAll(tagged("COLLABORATION", 95, 90, 100));    // 95
        all.addAll(tagged("REQUIREMENT_ANALYSIS", 40, 45, 50)); // 45
        return all;
    }

    @Test
    void 보고서가_5개_미만이면_만들지_않는다() {
        assertThat(UserSummaryCalculator.calculate(Settings.DEFAULT, 4, sample(), JOBS)).isEmpty();
        assertThat(UserSummaryCalculator.calculate(Settings.DEFAULT, 5, sample(), JOBS)).isPresent();
    }

    @Test
    void 태그별_평균_상위_2개가_강점_하위_2개가_아쉬운_점() {
        var summary = UserSummaryCalculator.calculate(Settings.DEFAULT, 5, sample(), JOBS).orElseThrow();

        assertThat(summary.strengths()).extracting(TagScore::tag).containsExactly("COLLABORATION", "COMMUNICATION");
        assertThat(summary.strengths()).extracting(TagScore::average).containsExactly(95, 85);
        assertThat(summary.weaknesses()).extracting(TagScore::tag).containsExactly("REQUIREMENT_ANALYSIS", "PROBLEM_SOLVING");
    }

    @Test
    void 질문이_3개_미만인_태그는_집계에서_뺀다() {
        List<TaggedQuestion> questions = new ArrayList<>(sample());
        questions.addAll(tagged("LEARNING_ADAPTABILITY", 100, 100)); // 2개뿐이라 강점 1위가 되면 안 된다
        questions.addAll(tagged("AI_USAGE", 10, 10));                // 2개뿐이라 아쉬운 점에 들면 안 된다

        var summary = UserSummaryCalculator.calculate(Settings.DEFAULT, 5, questions, JOBS).orElseThrow();

        assertThat(summary.strengths()).extracting(TagScore::tag).doesNotContain("LEARNING_ADAPTABILITY");
        assertThat(summary.weaknesses()).extracting(TagScore::tag).doesNotContain("AI_USAGE");
    }

    @Test
    void 질문_하나에_태그가_둘이면_두_태그_모두에_들어간다() {
        List<TaggedQuestion> questions = List.of(
                new TaggedQuestion(Set.of("COMMUNICATION", "REASONING"), 90),
                new TaggedQuestion(Set.of("COMMUNICATION", "REASONING"), 60),
                new TaggedQuestion(Set.of("COMMUNICATION"), 60));

        var summary = UserSummaryCalculator.calculate(Settings.DEFAULT, 5, questions, JOBS).orElseThrow();

        assertThat(summary.strengths()).containsExactly(new TagScore("COMMUNICATION", 70, 3));
    }

    @Test
    void 태그가_적어도_강점과_아쉬운_점이_겹치지_않는다() {
        List<TaggedQuestion> questions = new ArrayList<>();
        questions.addAll(tagged("COMMUNICATION", 90, 90, 90));
        questions.addAll(tagged("REASONING", 70, 70, 70));
        questions.addAll(tagged("PROBLEM_SOLVING", 50, 50, 50));

        var summary = UserSummaryCalculator.calculate(Settings.DEFAULT, 5, questions, JOBS).orElseThrow();

        assertThat(summary.strengths()).extracting(TagScore::tag).containsExactly("COMMUNICATION", "REASONING");
        assertThat(summary.weaknesses()).extracting(TagScore::tag).containsExactly("PROBLEM_SOLVING");
    }

    @Test
    void 선호_직무는_분석과_면접을_가장_많이_한_직무() {
        Map<String, Integer> jobs = new LinkedHashMap<>();
        jobs.put("프론트엔드 개발", 2);
        jobs.put("백엔드 개발", 5);
        jobs.put("데이터 엔지니어", 5);

        var summary = UserSummaryCalculator.calculate(Settings.DEFAULT, 5, sample(), jobs).orElseThrow();

        assertThat(summary.preferredJob()).isEqualTo("백엔드 개발");
    }

    @Test
    void 기준값은_설정으로_바꿀_수_있다() {
        var summary = UserSummaryCalculator.calculate(new Settings(3, 1, 2), 3, sample(), JOBS).orElseThrow();

        assertThat(summary.strengths()).extracting(TagScore::tag).containsExactly("COLLABORATION");
        assertThat(summary.weaknesses()).extracting(TagScore::tag).containsExactly("REQUIREMENT_ANALYSIS");
    }
}
