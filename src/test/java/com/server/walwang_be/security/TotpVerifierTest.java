package com.server.walwang_be.security;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RFC 6238 부록 B의 SHA-1 테스트 벡터로 구현을 고정한다.
 * RFC는 8자리 값을 주므로 뒤 6자리를 비교한다(자릿수만 다르고 계산은 같다).
 */
class TotpVerifierTest {

    /** RFC 6238의 SHA-1 시드 "12345678901234567890"을 Base32로 적은 것. */
    private static final String RFC_SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    @Test
    void RFC_6238_테스트_벡터와_같은_코드를_만든다() {
        byte[] key = TotpVerifier.decodeBase32(RFC_SECRET);

        assertThat(TotpVerifier.generate(key, 59L / 30)).isEqualTo("287082");
        assertThat(TotpVerifier.generate(key, 1111111109L / 30)).isEqualTo("081804");
        assertThat(TotpVerifier.generate(key, 1234567890L / 30)).isEqualTo("005924");
        assertThat(TotpVerifier.generate(key, 2000000000L / 30)).isEqualTo("279037");
    }

    @Test
    void Base32는_인증_앱이_보여주는_형식을_모두_받는다() {
        byte[] expected = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

        assertThat(TotpVerifier.decodeBase32(RFC_SECRET)).isEqualTo(expected);
        assertThat(TotpVerifier.decodeBase32("gezd gnbv gy3t qojq gezd gnbv gy3t qojq")).isEqualTo(expected);
        assertThatThrownBy(() -> TotpVerifier.decodeBase32("GEZD1NBV"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 앞뒤_한_칸까지만_받아주고_맞은_칸_번호를_돌려준다() {
        byte[] key = TotpVerifier.decodeBase32(RFC_SECRET);
        long now = 1234567890L;
        TotpVerifier verifier = new TotpVerifier(Clock.fixed(Instant.ofEpochSecond(now), ZoneOffset.UTC));
        long step = now / 30;

        assertThat(verifier.verify(key, TotpVerifier.generate(key, step))).hasValue(step);
        assertThat(verifier.verify(key, TotpVerifier.generate(key, step - 1))).hasValue(step - 1);
        assertThat(verifier.verify(key, TotpVerifier.generate(key, step + 1))).hasValue(step + 1);
        assertThat(verifier.verify(key, TotpVerifier.generate(key, step - 2))).isEmpty();
        assertThat(verifier.verify(key, TotpVerifier.generate(key, step + 2))).isEmpty();
    }

    @Test
    void 여섯_자리_숫자가_아니면_계산하지_않고_거절한다() {
        byte[] key = TotpVerifier.decodeBase32(RFC_SECRET);
        TotpVerifier verifier = new TotpVerifier(Clock.fixed(Instant.ofEpochSecond(1234567890L), ZoneOffset.UTC));

        assertThat(verifier.verify(key, null)).isEmpty();
        assertThat(verifier.verify(key, "")).isEmpty();
        assertThat(verifier.verify(key, "00592")).isEmpty();
        assertThat(verifier.verify(key, "0059244")).isEmpty();
        assertThat(verifier.verify(key, "abcdef")).isEmpty();
    }
}
