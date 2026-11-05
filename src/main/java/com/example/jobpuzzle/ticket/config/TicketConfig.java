package com.example.jobpuzzle.ticket.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(TicketProperties.class)
public class TicketConfig {

    // 시크릿 키는 결제 승인 호출 때만 헤더에 넣어, 키가 없는 로컬 환경에서도 Bean 생성이 실패하지 않게 한다.
    @Bean("tossRestClient")
    RestClient tossRestClient(TicketProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getToss().getRequestTimeout());
        factory.setReadTimeout(properties.getToss().getRequestTimeout());
        return RestClient.builder()
                .baseUrl(properties.getToss().getBaseUrl())
                .requestFactory(factory)
                .build();
    }
}
