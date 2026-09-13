package com.server.walwang_be.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 요청을 보낸 사람의 IP.
 *
 * 운영에서는 nginx가 같은 호스트에서 127.0.0.1:8080으로 넘기므로 getRemoteAddr()는
 * 항상 루프백이다. 그래서 루프백에서 온 요청일 때만 X-Forwarded-For를 믿는다.
 *
 * X-Forwarded-For는 반드시 "오른쪽 끝" 값을 쓴다.
 * nginx의 $proxy_add_x_forwarded_for는 클라이언트가 보낸 헤더 뒤에 자기가 본 주소를 붙인다.
 * 클라이언트가 "X-Forwarded-For: 허용IP"를 넣어 보내면 nginx를 지나 "허용IP, 실제IP"가 된다.
 * 왼쪽 끝을 쓰면 헤더 한 줄로 IP 허용목록이 뚫린다.
 *
 * 이 전제는 "우리 앞의 프록시는 같은 호스트의 nginx 하나"일 때만 맞다.
 * nginx 앞에 ALB나 Cloudflare를 더 두면 오른쪽 끝이 그쪽 주소가 되어
 * 허용목록에 걸리지 않는다(열리는 쪽이 아니라 닫히는 쪽으로 틀린다). 그때는 이 클래스를 고친다.
 *
 * server.forward-headers-strategy=native(톰캣 RemoteIpValve)이면 톰캣이 먼저
 * getRemoteAddr()를 실제 주소로 바꿔 두므로 루프백이 아니게 되고, 그 값을 그대로 쓴다.
 * framework(ForwardedHeaderFilter)는 왼쪽 끝을 쓰므로 AdminSecurityConfig가 관리자 페이지를 닫는다.
 */
@Component
public class ClientIpResolver {

    /** 이 요청에서 한 번 계산한 값을 담아 둔다. 필터·로그인·기록이 모두 같은 값을 보게 하려는 것이다. */
    static final String ATTRIBUTE = ClientIpResolver.class.getName() + ".IP";

    public static final String UNKNOWN = "unknown";

    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");
    /** 콜론이 들어간 16진수 문자열. 콜론이 있어야 InetAddress가 호스트명으로 보고 DNS를 찾지 않는다. */
    private static final Pattern IPV6 = Pattern.compile("^[0-9a-fA-F:.]*:[0-9a-fA-F:.]*$");

    public String resolve(HttpServletRequest request) {
        Object cached = request.getAttribute(ATTRIBUTE);
        if (cached instanceof String ip) {
            return ip;
        }
        String ip = compute(request);
        request.setAttribute(ATTRIBUTE, ip);
        return ip;
    }

    private String compute(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        if (!isLoopback(remote)) {
            // 프록시를 거치지 않았다. 클라이언트가 보낸 X-Forwarded-For는 거짓일 수 있으니 보지 않는다.
            return remote;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) {
            return remote;
        }
        String[] hops = forwarded.split(",");
        String last = hops[hops.length - 1].trim();
        // nginx가 붙인 값인데 IP 모양이 아니면 설정이 잘못된 것이다. 아무 허용목록에도 걸리지 않는 값으로 둔다.
        return isIpLiteral(last) ? last : UNKNOWN;
    }

    static boolean isIpLiteral(String value) {
        return value != null && (IPV4.matcher(value).matches() || IPV6.matcher(value).matches());
    }

    static boolean isLoopback(String address) {
        return address != null
                && (address.startsWith("127.")
                || address.equals("::1")
                || address.equals("0:0:0:0:0:0:0:1"));
    }
}
