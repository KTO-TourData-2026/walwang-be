package com.server.walwang_be.controller;

import com.server.walwang_be.service.AdminTerminationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.ModelAndView;

import java.util.NoSuchElementException;

/**
 * 관리자 화면의 오류 페이지.
 *
 * GlobalExceptionHandler(@RestControllerAdvice)는 모든 컨트롤러에 걸려 JSON을 내보낸다.
 * 이 advice를 먼저(HIGHEST_PRECEDENCE) 매칭시켜 관리자 화면에서는 HTML로 답한다.
 * 오류 화면에는 원래 모델을 싣지 않는다 — 기록 누락으로 막힌 화면의 데이터가 새지 않게.
 */
@Slf4j
@ControllerAdvice(assignableTypes = AdminController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AdminExceptionHandler {

    @ExceptionHandler(NoSuchElementException.class)
    public ModelAndView notFound(NoSuchElementException e) {
        return view(HttpStatus.NOT_FOUND, "대상을 찾을 수 없습니다.");
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class,
            IllegalArgumentException.class})
    public ModelAndView badRequest(Exception e) {
        return view(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다.");
    }

    /**
     * 해지를 실행하지 않고 되돌려보낸 경우. 확인 닉네임이 다르거나 사유가 비었을 때다.
     *
     * 여기만 예외 메시지를 화면에 쓴다 — 무엇을 고쳐야 하는지 알려줘야 관리자가 다시 시도할 수 있다.
     * 그래서 이 예외의 메시지는 코드에 있는 고정 문자열만 담는다(AdminTerminationService 참고).
     */
    @ExceptionHandler(AdminTerminationService.TerminationRejected.class)
    public ModelAndView rejected(AdminTerminationService.TerminationRejected e) {
        return view(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ModelAndView forbidden(AccessDeniedException e) {
        return view(HttpStatus.FORBIDDEN, "관리자 인증이 필요합니다. 다시 로그인하세요.");
    }

    /** 접속기록 저장 실패와 기록 누락(AccessLogEnforcementInterceptor)도 여기로 온다. */
    @ExceptionHandler(Exception.class)
    public ModelAndView internal(Exception e) {
        log.error("관리자 화면 오류", e);
        return view(HttpStatus.INTERNAL_SERVER_ERROR, "처리 중 오류가 발생했습니다. 화면을 표시하지 않았습니다.");
    }

    private static ModelAndView view(HttpStatus status, String message) {
        ModelAndView view = new ModelAndView("admin/error");
        view.setStatus(status);
        view.addObject("title", "오류");
        view.addObject("status", status.value());
        view.addObject("message", message);
        return view;
    }
}
