package com.example.jobpuzzle.global.config;

import com.example.jobpuzzle.ai.config.AiGenerationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class AiProviderConfig {
    /**
     * 실제 요청을 보내지 않는 한 API key는 사용되지 않는다. key는 전역 header가 아니라 adapter 호출 시에만 넣어
     * mock 기본 환경에서도 Anthropic 설정 누락으로 Bean 생성이 실패하지 않게 한다.
     */
    @Bean("anthropicRestClient")
    RestClient anthropicRestClient(AiGenerationProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getAnthropic().getRequestTimeout());
        factory.setReadTimeout(properties.getAnthropic().getRequestTimeout());
        return RestClient.builder()
                .baseUrl(properties.getAnthropic().getBaseUrl())
                .requestFactory(factory)
                .build();
    }
}
