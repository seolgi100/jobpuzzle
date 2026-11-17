package com.example.jobpuzzle.interview.service;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** v2 면접 질문 수 (app.interview). */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.interview")
public class InterviewQuestionProperties {

    // 기초 지식·자료 기반 면접에서 AI에게 만들게 할 질문 수
    private int generatedQuestionCount = 5;
    // 인성 면접에서 관리자 등록 질문 중 고를 수
    private int personalityQuestionCount = 5;
}
