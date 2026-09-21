package com.server.walwang_be.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AvailabilityStatusTest {

    @Test
    void 리뷰가_없는_행은_UNKNOWN이다() {
        // 가게마다 0인 행을 미리 만들어 두므로(seed-store-size-status.sql) 가장 흔한 입력이다.
        // 비율을 먼저 계산하면 0으로 나눠 NaN 이 되고, NaN 비교는 어느 쪽도 참이 아니라
        // 조용히 UNKNOWN 이 나온다 — 건수를 먼저 보는지 여기서 확인한다.
        assertThat(AvailabilityStatus.of(0, 0, 0, 0)).isEqualTo(AvailabilityStatus.UNKNOWN);
    }

    @Test
    void 건수가_모자라면_한쪽으로_치우쳐도_UNKNOWN이다() {
        // 가능 3건은 전부 가능이어도 4건 하한에 못 미친다.
        assertThat(AvailabilityStatus.of(3, 0, 3, 0)).isEqualTo(AvailabilityStatus.UNKNOWN);
        // 거절 2건으로는 가게를 추천에서 빼지 않는다.
        assertThat(AvailabilityStatus.of(0, 2, 0, 2)).isEqualTo(AvailabilityStatus.UNKNOWN);
    }

    @Test
    void 건수를_채웠어도_비율이_모자라면_UNKNOWN이다() {
        // 4:2 = 0.667 < 0.70
        assertThat(AvailabilityStatus.of(4, 2, 4, 2)).isEqualTo(AvailabilityStatus.UNKNOWN);
        // 3:3 = 0.5 < 0.55. 팽팽하면 어느 쪽도 아니다.
        assertThat(AvailabilityStatus.of(3, 3, 3, 3)).isEqualTo(AvailabilityStatus.UNKNOWN);
    }

    @Test
    void 건수와_비율을_모두_채우면_등급이_매겨진다() {
        // 5:2 = 0.714 >= 0.70
        assertThat(AvailabilityStatus.of(5, 2, 5, 2)).isEqualTo(AvailabilityStatus.POSSIBLE);
        // 2:3 = 0.6 >= 0.55. 불가는 세 건부터라 가능이 더 많아도 불가가 된다.
        assertThat(AvailabilityStatus.of(2, 3, 2, 3)).isEqualTo(AvailabilityStatus.IMPOSSIBLE);
    }

    @Test
    void 오래된_리뷰만_남아_점수가_작아도_등급은_유지된다() {
        // 가중치 하한(0.3)에 눌린 가능 4건. 총점은 1.2 뿐이지만 비율이 1.0 이라 POSSIBLE 이다 —
        // 절대 점수 하한을 두지 않기로 한 이유가 이것이다.
        assertThat(AvailabilityStatus.of(1.2, 0, 4, 0)).isEqualTo(AvailabilityStatus.POSSIBLE);
    }

    @Test
    void 건수만_있고_점수가_0인_행은_UNKNOWN이다() {
        // 재집계를 거치지 않고 SQL 로 직접 넣은 행에서 나올 수 있는 모양이다.
        // 0 으로 나누지 않고 등급을 매기지도 않는다.
        assertThat(AvailabilityStatus.of(0, 0, 5, 0)).isEqualTo(AvailabilityStatus.UNKNOWN);
    }
}
