package com.example.jobpuzzle.ai.config;

import com.example.jobpuzzle.ai.log.AiExecutionStage;
import com.example.jobpuzzle.ai.log.AiProvider;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

/**
 * 생성 AI 전용 설정이다. RAG embedding 설정과 분리해 생성 모델 변경이 vector 계약에 영향을 주지 않게 한다.
 * mode(평가 명세 9-1): stub = 미리 쓴 응답(MockAiClient), live = 실제 Claude. record·replay는 아직 없다.
 * stageOverrides는 application-ai-e2e.yaml에서만 사용하며, 지정한 stage만 mode와 다른 provider를 쓴다.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.ai.generation")
public class AiGenerationProperties {

    private AiMode mode = AiMode.STUB;
    // 응답 검증에 실패했을 때 다시 부르는 횟수 (명세 5장). 첫 호출 + 이 횟수까지 시도한다.
    private int maxRetries = 2;
    private Map<AiExecutionStage, AiProvider> stageOverrides = new EnumMap<>(AiExecutionStage.class);
    // stage별 thinking 정책은 기본값을 바꾸지 않고, 명시된 stage에만 Messages API body를 추가한다.
    private Map<AiExecutionStage, ThinkingMode> thinkingByStage = new EnumMap<>(AiExecutionStage.class);
    private Anthropic anthropic = new Anthropic();
    private InputLimits inputLimits = new InputLimits();

    public AiProvider providerFor(AiExecutionStage stage) {
        if (stage == null) throw new IllegalArgumentException("AI execution stage is required");
        AiProvider override = stageOverrides.get(stage);
        if (override != null) return override;
        return switch (mode) {
            case STUB -> AiProvider.MOCK;
            case LIVE -> AiProvider.ANTHROPIC;
            // ponytail: record·replay는 명세 9-5의 5·6단계에서 붙인다
            case RECORD, REPLAY -> throw new IllegalStateException("AI 호출 모드 " + mode + "는 아직 지원하지 않습니다");
        };
    }

    public enum AiMode {
        STUB, RECORD, REPLAY, LIVE
    }

    public ThinkingMode thinkingFor(AiExecutionStage stage) {
        if (stage == null) throw new IllegalArgumentException("AI execution stage is required");
        return thinkingByStage.getOrDefault(stage, ThinkingMode.DEFAULT);
    }

    // partition 재사용은 provider/model뿐 아니라 출력 생성 정책도 같을 때만 허용한다.
    public String generationPolicyFingerprintMaterial(AiExecutionStage stage) {
        return stage + "|maxOutputTokens=" + anthropic.getMaxOutputTokens().forStage(stage)
                + "|thinking=" + thinkingFor(stage);
    }

    public enum ThinkingMode {
        DEFAULT,
        DISABLED
    }

    @Getter
    @Setter
    public static class Anthropic {
        // mock 선택 시에는 검증하지 않는다. 실제 Anthropic 선택 시에만 필수값으로 검사한다.
        private String apiKey = "";
        private String model = "";
        private String baseUrl = "https://api.anthropic.com";
        // Messages API version은 HTTP 계약이므로 model 설정과 분리해 명시적으로 관리한다.
        private String apiVersion = "2023-06-01";
        private Duration requestTimeout = Duration.ofSeconds(30);
        private MaxOutputTokens maxOutputTokens = new MaxOutputTokens();
    }

    @Getter
    @Setter
    public static class MaxOutputTokens {
        private int json01 = 4_096;
        private int json02 = 6_144;
        private int json05 = 8_192;
        private int json06 = 4_096;
        private int json12 = 4_096;

        public int forStage(AiExecutionStage stage) {
            return switch (stage) {
                case JOB_POSTING_ANALYSIS -> json01;
                case CANDIDATE_MATERIAL_ANALYSIS -> json02;
                case CUSTOMIZED_SYNTHESIS -> json05;
                case ANSWER_EVALUATION -> json06;
                case QUESTION_GENERATION -> json12;
                default -> throw new IllegalArgumentException("generation output token policy is unavailable for " + stage);
            };
        }
    }

    @Getter
    @Setter
    public static class InputLimits {
        // 기본값은 fixture 8·9 측정값보다 충분히 크되, 원문 전체가 무제한 prompt로 전달되는 것은 차단한다.
        private int json01RenderedPromptChars = 40_000;
        private int json02RenderedPromptChars = 50_000;
        // JSON-02 분할 분석 전용 기준이다. marker를 버리지 않고 이 기준에서만 새 partition을 만든다.
        private int json02PartitionMarkerContentChars = 12_000;
        private int json02PartitionMaxMarkers = 8;
        private int json05RequirementCount = 20;
        private int json05RetrievalChars = 36_000;
        private int json05RenderedPromptChars = 64_000;

        public int renderedPromptLimit(AiExecutionStage stage) {
            return switch (stage) {
                case JOB_POSTING_ANALYSIS -> json01RenderedPromptChars;
                case CANDIDATE_MATERIAL_ANALYSIS -> json02RenderedPromptChars;
                case CUSTOMIZED_SYNTHESIS -> json05RenderedPromptChars;
                default -> throw new IllegalArgumentException("generation input limit is unavailable for " + stage);
            };
        }
    }
}
