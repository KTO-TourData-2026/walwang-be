package com.server.walwang_be.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.web.util.matcher.IpAddressMatcher;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 관리자 페이지에 들어올 수 있는 IP. 단일 주소와 CIDR(203.0.113.0/24)을 받는다.
 *
 * 잘못 적은 항목은 건너뛰고 로그를 남긴다. 한 줄 오타로 앱 전체가 뜨지 않으면
 * 앱 사용자까지 피해를 보기 때문이다. 대신 그 항목은 아무것도 허용하지 않는다.
 * 목록이 비면 아무도 들어오지 못한다.
 */
@Slf4j
public final class IpAllowlist {

    private final List<IpAddressMatcher> matchers;

    private IpAllowlist(List<IpAddressMatcher> matchers) {
        this.matchers = matchers;
    }

    public static IpAllowlist of(Collection<String> entries) {
        List<IpAddressMatcher> matchers = new ArrayList<>();
        for (String raw : entries) {
            String entry = raw == null ? "" : raw.trim();
            if (entry.isEmpty()) {
                continue;
            }
            String address = entry.contains("/") ? entry.substring(0, entry.indexOf('/')) : entry;
            // 호스트명을 받으면 IpAddressMatcher가 DNS를 찾는다. 주소 모양만 받는다.
            if (!ClientIpResolver.isIpLiteral(address)) {
                log.error("관리자 IP 허용목록 항목을 무시합니다(IP 형식이 아님): '{}'", entry);
                continue;
            }
            try {
                matchers.add(new IpAddressMatcher(entry));
            } catch (IllegalArgumentException e) {
                log.error("관리자 IP 허용목록 항목을 무시합니다: '{}' ({})", entry, e.getMessage());
                continue;
            }
            if (ClientIpResolver.isLoopback(address)) {
                // 운영에서 루프백을 허용하면 nginx를 거치지 않은 같은 호스트의 요청이 모두 통과한다.
                log.warn("관리자 IP 허용목록에 루프백({})이 있습니다. 로컬 개발용이 아니라면 빼세요.", entry);
            }
            if (entry.endsWith("/0")) {
                log.warn("관리자 IP 허용목록에 /0 ({})이 있습니다. 모든 주소가 허용됩니다.", entry);
            }
        }
        return new IpAllowlist(List.copyOf(matchers));
    }

    public static IpAllowlist denyAll() {
        return new IpAllowlist(List.of());
    }

    public boolean allows(String ip) {
        if (!ClientIpResolver.isIpLiteral(ip)) {
            return false;
        }
        return matchers.stream().anyMatch(matcher -> matcher.matches(ip));
    }

    public int size() {
        return matchers.size();
    }
}
