package com.example.jobpuzzle.simulation;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 시연용 시뮬레이션 계정. 이 아이디로 로그인하면 분석·질문 생성·답변 평가에서 AI를 부르지 않고
 * 그 계정에 이미 저장된 결과를 잠시 기다린 뒤 다시 보여 준다 (SimulationService).
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.simulation")
public class SimulationProperties {

    private List<String> loginIds = new ArrayList<>();
    // 로딩 흉내: 이 범위에서 무작위로 기다린다
    private Duration minDelay = Duration.ofSeconds(5);
    private Duration maxDelay = Duration.ofSeconds(8);
}
