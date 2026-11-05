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

    private int basicCost = 1;
    private int weaknessCost = 1;
    // 공고 맞춤: 이력서 1종이면 최소값, 지원자 자료 종류가 늘 때마다 1장씩, 최대값까지
    private int companyFitMinCost = 3;
    private int companyFitMaxCost = 5;
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
