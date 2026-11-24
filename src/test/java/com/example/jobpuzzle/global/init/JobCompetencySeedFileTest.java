package com.example.jobpuzzle.global.init;

import com.example.jobpuzzle.competency.entity.JobCompetencyTag;
import com.example.jobpuzzle.competency.repository.JobCompetencyTagRepository;
import com.example.jobpuzzle.competency.repository.PersonalityQuestionRepository;
import com.example.jobpuzzle.competency.service.CompetencyService;
import com.example.jobpuzzle.jobcategory.repository.JobCategoryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 초기 데이터 JSON을 바꿔도 직무 이름·태그 참조 규칙이 깨지지 않았는지 확인한다 (시더는 규칙 위반을 경고만 하고 넘어가므로). */
class JobCompetencySeedFileTest {

    // JobCategorySeeder.CATEGORIES와 같은 직무
    private static final Set<String> APP_JOBS = Set.of(
            "IT·개발|백엔드 개발", "IT·개발|프론트엔드 개발", "IT·개발|데이터 분석",
            "경영·사무|인사 채용", "경영·사무|마케팅", "디자인|UX/UI 디자인", "디자인|BX 디자인");

    @Test
    void everySeedItemPassesTheAdminRules() throws Exception {
        JobCompetencySeeder.Seed seed;
        try (InputStream in = new ClassPathResource(JobCompetencySeeder.SEED_PATH).getInputStream()) {
            seed = new ObjectMapper().readValue(in, JobCompetencySeeder.Seed.class);
        }
        Set<String> questions = new HashSet<>();
        for (JobCompetencySeeder.SeedJob job : seed.jobs()) {
            assertThat(APP_JOBS).contains(job.mainCategory() + "|" + job.subCategory());

            JobCompetencyTagRepository tagRepository = mock(JobCompetencyTagRepository.class);
            when(tagRepository.findByMainCategoryAndSubCategoryOrderByJobCompetencyTagIdAsc(job.mainCategory(), job.subCategory()))
                    .thenReturn(job.competencyTags().stream()
                            .map(t -> new JobCompetencyTag(job.mainCategory(), job.subCategory(), t.code(), t.name(), t.description()))
                            .toList());
            CompetencyService service = new CompetencyService(tagRepository,
                    mock(PersonalityQuestionRepository.class), mock(JobCategoryRepository.class));

            for (JobCompetencySeeder.SeedQuestion q : job.personalityQuestions()) {
                assertThat(q.careerLevel()).as(q.question()).isNotNull();
                assertThat(q.intent()).as(q.question()).isNotBlank();
                assertThat(questions.add(q.question())).as("중복 질문: " + q.question()).isTrue();
                assertThatCode(() -> service.validateTags(job.mainCategory(), job.subCategory(), q.competencyTags()))
                        .as(q.question()).doesNotThrowAnyException();
            }
        }
        assertThat(seed.jobs()).hasSize(APP_JOBS.size());
    }

    @Test
    void historyKeyIsStableAndFitsTheColumn() {
        String key = JobCompetencySeeder.key("IT·개발", "백엔드 개발", "question", "아주 긴 질문 ".repeat(50));
        assertThat(key).hasSize(64).isEqualTo(JobCompetencySeeder.key("IT·개발", "백엔드 개발", "question", "아주 긴 질문 ".repeat(50)));
        assertThat(key).isNotEqualTo(JobCompetencySeeder.key("IT·개발", "백엔드 개발", "tag", "아주 긴 질문 ".repeat(50)));
    }
}
