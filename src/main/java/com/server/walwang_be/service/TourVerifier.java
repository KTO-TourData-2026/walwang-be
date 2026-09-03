package com.server.walwang_be.service;

import com.server.walwang_be.entity.Store;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 공사 반려동물 동반 목록에 이름이 오른 가게를 가려낸다.
 *
 * 지도는 이 결과로 걸러 내보내고, 코스는 걸러내는 대신 배지로 표시한다 —
 * 성동구 매칭이 37곳뿐이라 코스까지 여기서만 뽑으면 카페·식사 코스가 아예 안 나온다.
 * 두 화면이 같은 판정을 쓰도록 매칭을 한곳에 모아 둔다.
 *
 * 결과는 요청 안에서만 산다. 공사 데이터를 우리가 보관하지 않기 위해서다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TourVerifier {

    private final TourDataProvider tourDataProvider;
    private final TourStoreMatcher matcher;

    /**
     * 후보 중 공사 목록에 오른 가게의 id.
     *
     * 공사 데이터를 못 받으면 빈 집합이다 — 호출한 쪽은 그걸 "확인 안 됨"으로 다루지,
     * 응답을 실패로 만들지는 않는다.
     *
     * pool은 넓을수록 정확하다. 매칭은 공사 지점 하나를 두고 주변 후보끼리 견주므로,
     * 진짜 주인이 pool에 없으면 옆 가게가 대신 뽑힐 수 있다.
     */
    public Set<UUID> verifiedAmong(List<Store> pool) {
        if (pool.isEmpty()) {
            return Set.of();
        }
        List<TourStoreMatcher.TourPoint> points = tourDataProvider.seongdongPoints();
        if (points.isEmpty()) {
            return Set.of();
        }

        long startedAt = System.currentTimeMillis();
        Set<UUID> verified = new LinkedHashSet<>(
                matcher.matchAll(points, pool).matched().values().stream()
                        .map(TourStoreMatcher.Match::storeId)
                        .toList());
        log.info("공사 매칭: 공사 {}건 x 후보 {}건 -> {}건 ({}ms)",
                points.size(), pool.size(), verified.size(),
                System.currentTimeMillis() - startedAt);

        return verified;
    }
}
