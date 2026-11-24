package com.example.jobpuzzle.ai.prompt;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PromptTemplateDeploymentArtifactTest {

    private static final Path MANUAL_SQL = Path.of("src/main/resources/db/manual");

    @Test
    void latestJson01SqlTemplateTextMatchesCanonicalPromptFile() throws IOException {
        // JSON-01 최신 v1.3 SQL 본문과 무관 조건 제외 계약이 현재 정본과 일치하는지 검증한다.
        String sql = Files.readString(MANUAL_SQL.resolve("activate-json-01-v1.3.sql"));

        assertThat(sql).contains(
                "'v1.3'",
                "학력 무관, 경력 무관, 성별 무관, 연령 무관",
                "UPDATE prompt_template",
                "WHERE target_json = 'JSON-01' AND is_active = TRUE"
        );
        assertThat(sqlLiteral("activate-json-01-v1.3.sql", "PT-JOB-001"))
                .isEqualTo(prompt("json-01-v1.3.txt"));
    }

    @Test
    void latestJson02SqlTemplateTextMatchesCanonicalPromptFile() throws IOException {
        // JSON-02 최신 v1.6 SQL 본문이 현재 정본 프롬프트와 일치하는지 검증한다.
        assertThat(sqlLiteral("activate-json-02-v1.6.sql", "PT-CAND-001"))
                .isEqualTo(prompt("json-02-v1.6.txt"));
    }

    @Test
    void latestJson05SqlTemplateTextMatchesCanonicalPromptFile() throws IOException {
        // JSON-05 최신 v1.10 SQL 본문이 현재 정본 프롬프트와 일치하는지 검증한다.
        assertThat(sqlLiteral("activate-json-05-v1.10.sql", "PT-JSON05-001"))
                .isEqualTo(prompt("json-05-v1.10.txt"));
    }

    private String prompt(String filename) throws IOException {
        // UTF-8 정본의 마지막 줄바꿈 차이는 SQL literal 비교에서 제거한다.
        return Files.readString(Path.of("src/main/resources/prompts", filename))
                .replace("\r\n", "\n").trim();
    }

    private String sqlLiteral(String filename, String promptCode) throws IOException {
        // INSERT/SELECT의 다섯 번째 문자열(template_text)을 SQL quote 규칙에 맞춰 추출한다.
        String sql = Files.readString(MANUAL_SQL.resolve(filename));
        int row = sql.indexOf("'" + promptCode + "'", sql.indexOf("INSERT INTO"));
        assertThat(row).isGreaterThanOrEqualTo(0);
        int cursor = row;
        Literal value = null;
        for (int index = 0; index < 5; index++) {
            value = nextLiteral(sql, cursor);
            cursor = value.end();
        }
        return value.value().replace("\r\n", "\n");
    }

    private Literal nextLiteral(String sql, int from) {
        int start = sql.indexOf('\'', from);
        int end = start + 1;
        StringBuilder value = new StringBuilder();
        while (end < sql.length()) {
            char current = sql.charAt(end++);
            if (current != '\'') {
                value.append(current);
            } else if (end < sql.length() && sql.charAt(end) == '\'') {
                value.append('\'');
                end++;
            } else {
                return new Literal(value.toString(), end);
            }
        }
        throw new AssertionError("unterminated SQL literal");
    }

    private record Literal(String value, int end) {
    }
}
