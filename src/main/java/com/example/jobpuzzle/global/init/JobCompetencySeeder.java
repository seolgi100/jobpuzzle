package com.example.jobpuzzle.global.init;

import com.example.jobpuzzle.competency.dto.CompetencyDtos.QuestionRequest;
import com.example.jobpuzzle.competency.dto.CompetencyDtos.TagRequest;
import com.example.jobpuzzle.global.error.CustomException;
import com.example.jobpuzzle.competency.service.CompetencyService;
import com.example.jobpuzzle.jobcategory.entity.JobCategoryCareerLevel;
import com.example.jobpuzzle.jobcategory.repository.JobCategoryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * 직무별 역량 태그·인성 질문 초기 데이터 (seed/job-competency-seed.json).
 * 항목마다 한 번만 넣고 seed_history에 기록한다. 그래서 관리자가 고치거나 지운 항목은 다시 들어오지 않고,
 * JSON에 새로 추가한 항목만 다음 기동 때 들어간다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE + 10) // JobCategorySeeder 다음
public class JobCompetencySeeder implements ApplicationRunner {

    static final String SEED_PATH = "seed/job-competency-seed.json";

    private final ObjectMapper objectMapper;
    private final CompetencyService competencyService;
    private final JobCategoryRepository jobCategoryRepository;
    private final SeedHistoryRepository seedHistoryRepository;

    record Seed(List<SeedJob> jobs) { }
    record SeedJob(String mainCategory, String subCategory, List<SeedTag> competencyTags, List<SeedQuestion> personalityQuestions) { }
    record SeedTag(String code, String name, String description) { }
    record SeedQuestion(String question, String intent, List<String> competencyTags, JobCategoryCareerLevel careerLevel) { }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        ClassPathResource resource = new ClassPathResource(SEED_PATH);
        if (!resource.exists()) return;
        Seed seed;
        try (InputStream in = resource.getInputStream()) {
            seed = objectMapper.readValue(in, Seed.class);
        }
        int added = 0;
        for (SeedJob job : seed.jobs()) {
            String main = job.mainCategory();
            String sub = job.subCategory();
            if (!jobCategoryRepository.existsByMainCategoryAndSubCategory(main, sub)) {
                log.warn("JobCompetencySeeder: 직무 분류에 없는 직무라 건너뜀 {} / {}", main, sub);
                continue;
            }
            for (SeedTag tag : job.competencyTags()) {
                String key = key(main, sub, "tag", tag.code());
                if (seedHistoryRepository.existsById(key)) continue;
                if (apply(key, () -> competencyService.createTag(new TagRequest(main, sub, tag.code(), tag.name(), tag.description())))) added++;
            }
            for (SeedQuestion q : job.personalityQuestions()) {
                String key = key(main, sub, "question", q.question());
                if (seedHistoryRepository.existsById(key)) continue;
                if (apply(key, () -> competencyService.createQuestion(new QuestionRequest(main, sub, q.question(), q.intent(),
                        q.competencyTags(), q.careerLevel())))) added++;
            }
        }
        if (added > 0) log.info("JobCompetencySeeder 완료: {}건 추가", added);
    }

    // 항목마다 따로 커밋한다. 이미 같은 코드가 있는 등 규칙에 걸리면 기동을 막지 않고 경고만 남긴다.
    // 걸린 항목도 기록해 매 기동마다 같은 경고가 반복되지 않게 한다.
    private boolean apply(String key, Runnable create) {
        boolean created = true;
        try {
            create.run();
        } catch (CustomException e) {
            log.warn("JobCompetencySeeder: 항목을 건너뜀 ({})", e.getMessage());
            created = false;
        }
        seedHistoryRepository.save(new SeedHistory(key));
        return created;
    }

    // 질문 문장이 길어 키 칸에 그대로 넣지 않고 SHA-256(64자)으로 줄인다.
    static String key(String main, String sub, String kind, String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(("competency|" + main + "|" + sub + "|" + kind + "|" + value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
