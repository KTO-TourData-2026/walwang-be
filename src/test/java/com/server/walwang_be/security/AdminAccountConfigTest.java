package com.server.walwang_be.security;

import com.server.walwang_be.config.AdminProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/admin-access-log.md 에 적은 환경변수 이름이 실제로 바인딩되는지,
 * 잘못 넣은 계정이 로그인 가능한 상태로 남지 않는지 본다.
 */
class AdminAccountConfigTest {

    private static final String SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);

    @Test
    void 문서에_적은_환경변수_이름으로_바인딩된다() {
        String hash = encoder.encode("pw");
        AdminProperties properties = bindFromEnvironment(Map.of(
                "ADMIN_ALLOWLIST", "203.0.113.10,198.51.100.0/24",
                "ADMIN_ACCOUNTS_0_USERNAME", "alice",
                "ADMIN_ACCOUNTS_0_BCRYPT", hash,
                "ADMIN_ACCOUNTS_0_TOTP", SECRET,
                "ADMIN_ACCOUNTS_1_USERNAME", "bob",
                "ADMIN_ACCOUNTS_1_BCRYPT", hash,
                "ADMIN_ACCOUNTS_1_TOTP", SECRET));

        assertThat(properties.allowlist()).containsExactly("203.0.113.10", "198.51.100.0/24");
        assertThat(properties.accounts()).extracting(AdminProperties.Account::username)
                .containsExactly("alice", "bob");
        assertThat(properties.accounts().get(0).bcrypt()).isEqualTo(hash);
        assertThat(properties.accounts().get(0).totp()).isEqualTo(SECRET);
        // 시크릿이 로그로 새지 않는다.
        assertThat(properties.accounts().get(0).toString()).doesNotContain(SECRET).doesNotContain(hash);
    }

    @Test
    void 아무것도_없으면_빈_목록이다() {
        AdminProperties properties = bindFromEnvironment(Map.of());

        assertThat(properties.accounts()).isEmpty();
        assertThat(properties.allowlist()).isEmpty();
        assertThat(new AdminAccountRegistry(properties).size()).isZero();
    }

    @Test
    void 형식이_틀린_계정은_건너뛴다() {
        String hash = encoder.encode("pw");
        AdminAccountRegistry registry = new AdminAccountRegistry(new AdminProperties(List.of(
                new AdminProperties.Account("alice", hash, SECRET),
                // '$'가 변수로 해석돼 잘린 해시
                new AdminProperties.Account("cut", "a$10" + hash.substring(6), SECRET),
                new AdminProperties.Account("shortotp", hash, "GEZDGNBV"),
                new AdminProperties.Account("badotp", hash, "not base32!"),
                new AdminProperties.Account("공백 있는 이름", hash, SECRET),
                new AdminProperties.Account("alice", hash, SECRET)
        ), List.of()));

        assertThat(registry.size()).isEqualTo(1);
        assertThat(registry.find("alice")).isPresent();
        assertThat(registry.find("cut")).isEmpty();
        assertThat(registry.find("shortotp")).isEmpty();
        assertThat(registry.find("badotp")).isEmpty();
    }

    @Test
    void htpasswd가_만드는_2y_해시도_검증된다() {
        String hash2a = encoder.encode("correct horse");
        String hash2y = "$2y$" + hash2a.substring(4);

        AdminAccountRegistry registry = new AdminAccountRegistry(new AdminProperties(
                List.of(new AdminProperties.Account("alice", hash2y, SECRET)), List.of()));

        assertThat(registry.find("alice")).isPresent();
        assertThat(encoder.matches("correct horse", hash2y)).isTrue();
        assertThat(encoder.matches("wrong", hash2y)).isFalse();
    }

    private static AdminProperties bindFromEnvironment(Map<String, Object> env) {
        MutablePropertySources sources = new MutablePropertySources();
        sources.addFirst(new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, env));
        return new Binder(ConfigurationPropertySources.from(sources))
                .bindOrCreate("admin", AdminProperties.class);
    }
}
