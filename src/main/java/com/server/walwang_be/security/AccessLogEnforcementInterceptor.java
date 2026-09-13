package com.server.walwang_be.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

/**
 * 관리자 화면의 안전망. 핸들러가 접속기록을 남기지 않았으면 뷰를 그리기 전에 예외를 던진다.
 *
 * 기록은 핸들러가 직접 한다(누구의 무엇을 봤는지는 핸들러만 안다). 이 인터셉터는
 * 나중에 화면을 추가하면서 기록을 빠뜨렸을 때 개인정보가 기록 없이 나가는 것을 막는다.
 *
 * postHandle은 뷰 렌더링 직전에 불린다. 그래서 뷰 이름을 돌려주는 핸들러에만 효과가 있다.
 * @ResponseBody 나 ResponseEntity는 postHandle 전에 이미 본문을 써 버리므로
 * 관리자 화면에서는 그런 핸들러를 만들지 않는다.
 */
public class AccessLogEnforcementInterceptor implements HandlerInterceptor {

    private static final String RECORDED = AccessLogEnforcementInterceptor.class.getName() + ".RECORDED";

    public static void markRecorded(HttpServletRequest request) {
        request.setAttribute(RECORDED, Boolean.TRUE);
    }

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response,
                           Object handler, ModelAndView modelAndView) {
        if (handler instanceof HandlerMethod && request.getAttribute(RECORDED) == null) {
            throw new IllegalStateException(
                    "접속기록을 남기지 않은 관리자 화면입니다: " + request.getRequestURI());
        }
    }
}
