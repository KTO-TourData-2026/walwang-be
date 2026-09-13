package com.server.walwang_be.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.ModelAndView;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 나중에 관리자 화면을 추가하면서 기록을 빠뜨려도 뷰가 그려지기 전에 막히는지 본다.
 * (지금 있는 화면은 모두 기록하므로 컨트롤러로는 이 경로를 재현할 수 없어 인터셉터를 직접 부른다.)
 */
class AccessLogEnforcementInterceptorTest {

    private final AccessLogEnforcementInterceptor interceptor = new AccessLogEnforcementInterceptor();

    @Test
    void 기록하지_않은_핸들러는_렌더링_전에_예외로_막힌다() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/new-screen");
        HandlerMethod handler = new HandlerMethod(new ForgetfulController(), "screen");

        assertThatThrownBy(() -> interceptor.postHandle(
                request, new MockHttpServletResponse(), handler, new ModelAndView("admin/users")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("/admin/new-screen");
    }

    @Test
    void 기록한_핸들러는_통과한다() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/users");
        AccessLogEnforcementInterceptor.markRecorded(request);
        HandlerMethod handler = new HandlerMethod(new ForgetfulController(), "screen");

        assertThatCode(() -> interceptor.postHandle(
                request, new MockHttpServletResponse(), handler, new ModelAndView("admin/users")))
                .doesNotThrowAnyException();
    }

    @Test
    void 정적_리소스는_핸들러_메서드가_아니라서_보지_않는다() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/admin.css");

        assertThatCode(() -> interceptor.postHandle(request, new MockHttpServletResponse(), new Object(), null))
                .doesNotThrowAnyException();
    }

    static class ForgetfulController {
        public String screen() {
            return "admin/users";
        }
    }
}
