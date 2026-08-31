package com.server.walwang_be.controller;

import com.server.walwang_be.client.ReceiptOcrClient;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.NoSuchElementException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 로그인 정보 불일치 → 401. */
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<Map<String, String>> handleBadCredentials(BadCredentialsException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("message", e.getMessage()));
    }

    /** 이메일·닉네임 중복 → 409. */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleIllegalState(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("message", e.getMessage()));
    }

    /** 영수증 토큰 만료·불일치, 남의 리뷰 삭제 시도 등 → 403. */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, String>> handleAccessDenied(AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("message", e.getMessage()));
    }

    /**
     * 영수증에서 글자를 읽지 못함 → 422.
     * 400과 나누는 이유는 대응이 다르기 때문이다 — 파일은 멀쩡하니 다시 찍으면 된다.
     */
    @ExceptionHandler(ReceiptOcrClient.OcrUnreadableException.class)
    public ResponseEntity<Map<String, String>> handleOcrUnreadable(ReceiptOcrClient.OcrUnreadableException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                .body(Map.of("message", e.getMessage()));
    }

    /** OCR 서버 장애·타임아웃 → 503. 사용자 잘못이 아니라 잠시 후 재시도할 일이다. */
    @ExceptionHandler(ReceiptOcrClient.OcrUnavailableException.class)
    public ResponseEntity<Map<String, String>> handleOcrUnavailable(ReceiptOcrClient.OcrUnavailableException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("message", "영수증 인증 서버가 응답하지 않습니다. 잠시 후 다시 시도해 주세요."));
    }

    /** 없는 가게·리뷰 등 대상이 존재하지 않음 → 404. */
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("message", e.getMessage()));
    }

    /** 필수 약관 미동의 등 → 400. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
    }

    /** 본문 JSON 자체를 못 읽음(문법 오류·타입 불일치 등) → 400. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> handleUnreadableBody(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(Map.of("message", "요청 본문을 읽을 수 없습니다."));
    }

    /** 본문(@Valid) 검증 실패 → 400. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleBodyValidation(MethodArgumentNotValidException e) {
        FieldError fieldError = e.getBindingResult().getFieldError();
        String message = fieldError != null ? fieldError.getDefaultMessage() : "잘못된 요청입니다.";
        return ResponseEntity.badRequest().body(Map.of("message", message));
    }

    /** 쿼리 파라미터(@Validated) 검증 실패 → 400. */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, String>> handleParamValidation(ConstraintViolationException e) {
        return ResponseEntity.badRequest().body(Map.of("message", "잘못된 요청입니다."));
    }
}
