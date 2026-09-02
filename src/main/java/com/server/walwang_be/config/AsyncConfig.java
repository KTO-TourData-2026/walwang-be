package com.server.walwang_be.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 발도장 생성처럼 오래 걸리는 작업을 요청 스레드 밖으로 뺀다.
 *
 * 큐 길이를 무제한으로 두지 않는 이유는, AI가 느려질 때 요청이 끝없이 쌓이면
 * 메모리만 먹다가 서버가 통째로 죽기 때문이다. 넘치면 그 건은 PENDING으로 남고
 * 뒤에서 도는 재시도가 나중에 다시 집어간다.
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    @Bean("stampExecutor")
    public Executor stampExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("stamp-");
        // 큐가 가득 차면 조용히 버린다. 재시도 스케줄러가 안전망이다.
        executor.setRejectedExecutionHandler((task, rejected) -> {
        });
        executor.initialize();
        return executor;
    }
}
