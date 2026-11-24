package com.example.jobpuzzle.global.init;

import com.example.jobpuzzle.ai.prompt.PromptTemplate;
import com.example.jobpuzzle.ai.prompt.PromptTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * AI 호출에 반드시 필요한 운영 기준 프롬프트를 멱등하게 등록한다.
 * 더미 사용자·공고를 만드는 DataInitializer와 분리하여 실행 여부에 영향을 받지 않게 한다.
 */
@Slf4j
@Component
@Order(20)
@RequiredArgsConstructor
public class PromptTemplateSeeder implements ApplicationRunner {

    private static final String VERSION = "v1.0";

    private final PromptTemplateRepository promptTemplateRepository;

    @Override
    public void run(ApplicationArguments args) {
        List<Seed> seeds = List.of(
                new Seed("INTERVIEW_ANSWER_EVALUATION_V2", "면접 답변 평가 v2 (6개 항목, 1~5점)", "JSON-06-V2",
                        "prompts/json-06-v2-answer-evaluation.txt"),
                new Seed("INTERVIEW_QUESTION_GENERATION_V2", "기초 지식·자료 기반 면접 질문 생성 v2", "JSON-12",
                        "prompts/json-12-v2-question-generation.txt")
        );

        int inserted = 0;
        for (Seed seed : seeds) {
            // 팀원이 이미 같은 JSON 계약의 활성 프롬프트를 등록했다면 덮어쓰거나 우선순위를 바꾸지 않는다.
            if (promptTemplateRepository.existsByPromptCodeAndVersion(seed.promptCode(), VERSION)
                    || promptTemplateRepository.existsByTargetJsonAndIsActiveTrue(seed.targetJson())) {
                continue;
            }
            promptTemplateRepository.save(PromptTemplate.builder()
                    .promptCode(seed.promptCode())
                    .name(seed.name())
                    .version(VERSION)
                    .targetJson(seed.targetJson())
                    .templateText(read(seed.resourcePath()))
                    .forbiddenRules(commonForbiddenRules())
                    .isActive(true)
                    .build());
            inserted++;
        }
        log.info("PromptTemplateSeeder 완료: {}건 추가", inserted);
    }

    private String read(String path) {
        Resource resource = new ClassPathResource(path);
        try (var inputStream = resource.getInputStream()) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("프롬프트 리소스를 읽을 수 없습니다: " + path, exception);
        }
    }

    private String commonForbiddenRules() {
        return """
                입력에 없는 회사명, 경력, 성과 수치 또는 평가 근거를 만들어내지 않는다.
                Markdown 코드 블록이나 설명문 없이 지정된 JSON 객체만 반환한다.
                정의되지 않은 enum 값이나 필드를 추가하지 않는다.
                """;
    }

    private record Seed(
            String promptCode,
            String name,
            String targetJson,
            String resourcePath
    ) {
    }
}
