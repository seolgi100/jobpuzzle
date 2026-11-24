package com.example.jobpuzzle.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableAsync
public class AsyncConfig {

    // PDF/OCR 추출처럼 오래 걸리는 작업을 요청 스레드와 분리해서 실행하기 위한 전용 풀
    @Bean(name = "documentExtractionExecutor")
    public Executor documentExtractionExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("doc-extract-");
        executor.initialize();
        return executor;
    }

    // AI 분석은 한 건에 수십 초~수 분 걸리므로 HTTP 요청 스레드가 아닌 전용 풀에서 실행한다.
    // 동시에 도는 분석 수를 묶어 AI 제공자 요청 한도와 DB 연결을 보호하고, 대기열이 차면 접수를 거절한다.
    @Bean(name = "analysisExecutor")
    public ThreadPoolTaskExecutor analysisExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("analysis-run-");
        executor.initialize();
        return executor;
    }

    // 자료 분석(JSON-02)의 분할 호출을 동시에 실행하는 풀. 모든 분석이 함께 쓰므로 동시 호출 수의 상한이 된다.
    // 풀이 바쁘면 거절하지 않고 호출한 스레드가 직접 실행해(CallerRunsPolicy) 분할 결과를 잃지 않는다.
    @Bean(name = "json02PartitionExecutor")
    public ThreadPoolTaskExecutor json02PartitionExecutor(
            @Value("${app.ai.generation.json02-partition-concurrency:4}") int concurrency) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(concurrency);
        executor.setMaxPoolSize(concurrency);
        executor.setQueueCapacity(50);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setThreadNamePrefix("json02-partition-");
        executor.initialize();
        return executor;
    }
}