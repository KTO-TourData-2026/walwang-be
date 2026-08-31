package com.server.walwang_be.client;

import org.springframework.stereotype.Component;

/**
 * 세그멘테이션이 붙기 전까지 항상 기본 발도장을 돌려준다.
 *
 * 실제 모델을 붙일 때는 그 구현체에 @Primary를 달거나 이 클래스를 지운다.
 * 빈이 둘 다 등록된 채로 두면 주입이 실패한다.
 */
@Component
public class DefaultPawStampClient implements PawStampClient {

    @Override
    public String createStamp(String photoUrl) {
        return DEFAULT_STAMP_URL;
    }
}
