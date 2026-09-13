package com.server.walwang_be.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IP 허용목록이 헤더 한 줄로 뚫리지 않는지 본다.
 * 운영 구성: 인터넷 → nginx(같은 호스트) → 127.0.0.1:8080
 */
class ClientIpResolverTest {

    private final ClientIpResolver resolver = new ClientIpResolver();

    @Test
    void nginx를_거친_요청은_X_Forwarded_For의_오른쪽_끝을_쓴다() {
        assertThat(resolve("127.0.0.1", "203.0.113.10")).isEqualTo("203.0.113.10");
    }

    @Test
    void 클라이언트가_앞에_끼워_넣은_값은_무시한다() {
        // 클라이언트가 "X-Forwarded-For: 허용IP"를 보내면 nginx의 $proxy_add_x_forwarded_for가 뒤에 실제 주소를 붙인다.
        assertThat(resolve("127.0.0.1", "203.0.113.10, 198.51.100.7")).isEqualTo("198.51.100.7");
    }

    @Test
    void 프록시를_거치지_않은_요청의_X_Forwarded_For는_믿지_않는다() {
        assertThat(resolve("198.51.100.7", "203.0.113.10")).isEqualTo("198.51.100.7");
    }

    @Test
    void 헤더가_없으면_remoteAddr_그대로() {
        assertThat(resolve("127.0.0.1", null)).isEqualTo("127.0.0.1");
        assertThat(resolve("0:0:0:0:0:0:0:1", null)).isEqualTo("0:0:0:0:0:0:0:1");
    }

    @Test
    void 오른쪽_끝이_IP_모양이_아니면_어떤_허용목록에도_걸리지_않는_값이_된다() {
        assertThat(resolve("127.0.0.1", "203.0.113.10, evil.example.com")).isEqualTo(ClientIpResolver.UNKNOWN);
        assertThat(resolve("127.0.0.1", "203.0.113.10, unknown")).isEqualTo(ClientIpResolver.UNKNOWN);
    }

    @Test
    void IPv6도_받는다() {
        assertThat(resolve("::1", "2001:db8::1")).isEqualTo("2001:db8::1");
    }

    @Test
    void 허용목록은_단일_주소와_CIDR을_받고_잘못된_항목은_아무것도_허용하지_않는다() {
        IpAllowlist allowlist = IpAllowlist.of(List.of(
                " 203.0.113.10 ", "198.51.100.0/24", "", "admin.example.com", "300.1.1.1/99", "2001:db8::/32"));

        assertThat(allowlist.size()).isEqualTo(3);
        assertThat(allowlist.allows("203.0.113.10")).isTrue();
        assertThat(allowlist.allows("203.0.113.11")).isFalse();
        assertThat(allowlist.allows("198.51.100.200")).isTrue();
        assertThat(allowlist.allows("2001:db8:1::5")).isTrue();
        assertThat(allowlist.allows(ClientIpResolver.UNKNOWN)).isFalse();
        assertThat(allowlist.allows(null)).isFalse();
    }

    @Test
    void 빈_허용목록은_아무도_들이지_않는다() {
        assertThat(IpAllowlist.of(List.of()).allows("203.0.113.10")).isFalse();
        assertThat(IpAllowlist.denyAll().allows("127.0.0.1")).isFalse();
    }

    private String resolve(String remoteAddr, String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        return resolver.resolve(request);
    }
}
