package com.example.jobpuzzle.evaluation.service;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 평가 명세 v2 4-3 사용자 종합 평가 기준값 (app.summary). 명세가 설정값으로 관리하라고 한 값들. */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.summary")
public class SummaryProperties {

    // 종합 평가를 만드는 최소 최종 보고서 수
    private int minReports = 5;
    // 강점·아쉬운 점으로 보여 줄 태그 수
    private int topN = 2;
    // 태그별 집계에 필요한 최소 질문 수 (적으면 신뢰도 부족으로 제외)
    private int minQuestionsPerTag = 3;
}
