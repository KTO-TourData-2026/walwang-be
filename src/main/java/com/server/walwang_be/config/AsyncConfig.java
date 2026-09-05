package com.server.walwang_be.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 오래 걸리는 작업을 요청 스레드 밖으로 뺀다.
 *
 * 큐 길이를 무제한으로 두지 않는 이유는, 외부 서비스가 느려질 때 작업이 끝없이 쌓이면
 * 메모리만 먹다가 서버가 통째로 죽기 때문이다.
 *
 * 발도장 풀은 없앴다 — 도장 생성이 리뷰 등록 트랜잭션 안에서 동기로 끝나
 * 요청 스레드 밖으로 나갈 일이 없어졌다.
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    /**
     * 티맵 구간 조회용.
     *
     * 코스 하나에 구간이 2~4개인데 순차로 부르면 그만큼 시간이 쌓인다.
     * 구간끼리는 서로를 필요로 하지 않아 동시에 불러도 된다.
     *
     * 임베딩과 풀을 나눈다 — 한쪽이 밀린다고 다른 쪽이 굶으면 안 된다.
     */
    @Bean("tmapExecutor")
    public Executor tmapExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("tmap-");
        executor.initialize();
        return executor;
    }

    /**
     * 임베딩 갱신용.
     *
     * 티맵 풀과 나눈다 — 코스 조회가 구간을 한꺼번에 밀어 넣는 사이에
     * 짧게 끝날 갱신 호출까지 같이 밀리면 안 된다.
     */
    @Bean("embeddingExecutor")
    public Executor embeddingExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("embedding-");
        // 큐가 넘치면 그냥 버린다. 다음 리뷰가 들어오면 어차피 다시 갱신된다.
        executor.setRejectedExecutionHandler((task, rejected) -> {
        });
        executor.initialize();
        return executor;
    }

}
