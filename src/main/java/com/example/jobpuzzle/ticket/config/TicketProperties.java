package com.example.jobpuzzle.ticket.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** 모드별 티켓 차감 수, 판매 묶음, 토스페이먼츠 키. 가격·수량은 application.yaml에서 바꾼다. */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.ticket")
public class TicketProperties {

    // v2: 분석, 면접 모드 3종, 종료된 면접 다시 진행(질문 유지·평가만 다시), 질문 다시 생성
    private int analysisCost = 1;
    private int knowledgeCost = 1;
    private int materialCost = 3;
    private int personalityCost = 1;
    private int sessionRetryCost = 1;
    private int questionRegenerateCost = 2;
    private List<Product> products = new ArrayList<>();
    private Toss toss = new Toss();

    @Getter
    @Setter
    public static class Product {
        private String code;
        private String name;
        private int tickets;
        private int price;
    }

    @Getter
    @Setter
    public static class Toss {
        private String clientKey;
        private String secretKey;
        private String baseUrl = "https://api.tosspayments.com";
        private Duration requestTimeout = Duration.ofSeconds(30);
    }
}
