package com.server.walwang_be.controller;

import com.server.walwang_be.dto.ReceiptVerifyResponse;
import com.server.walwang_be.dto.ReviewCreateRequest;
import com.server.walwang_be.dto.ReviewCreateResponse;
import com.server.walwang_be.dto.ReviewReportRequest;
import com.server.walwang_be.service.ReviewReportService;
import com.server.walwang_be.service.ReviewService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@RestController
@RequestMapping("/reviews")
@RequiredArgsConstructor
@Validated
public class ReviewController {

    private final ReviewService reviewService;
    private final ReviewReportService reviewReportService;

    /**
     * 리뷰 등록.
     *
     * data 파트는 Content-Type이 application/json이어야 한다 —
     * 안 그러면 Spring이 문자열로 받아 역직렬화하지 못한다.
     * photo는 선택이다(거절 리뷰에는 사진이 없다).
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ReviewCreateResponse> create(
            @AuthenticationPrincipal UUID userId,
            @RequestPart("data") @Valid ReviewCreateRequest data,
            @RequestPart(value = "photo", required = false) MultipartFile photo) {
        return ResponseEntity.ok(reviewService.create(userId, data, photo));
    }

    /**
     * 영수증 인증. 통과하면 30분짜리 receipt_token을 발급하고,
     * 리뷰 등록은 그 토큰이 있어야 통과한다.
     *
     * 상호명이 안 맞는 건 실패가 아니라 verified=false인 200 응답이다.
     */
    @PostMapping(value = "/receipt-verify", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ReceiptVerifyResponse> verifyReceipt(
            @AuthenticationPrincipal UUID userId,
            @RequestPart("receipt") MultipartFile receipt,
            @RequestParam("store_id") UUID storeId) {
        return ResponseEntity.ok(reviewService.verifyReceipt(userId, storeId, receipt));
    }

    /**
     * 리뷰 신고. 약관 11조(정보통신망법 44조의2)가 정한 접수 창구다.
     *
     * 접수만 하고 노출은 그대로 둔다 — 가릴지는 관리자가 내용을 보고 정한다.
     * 없는 리뷰 404, 본인 리뷰 400, 이미 신고한 리뷰 409.
     */
    @PostMapping("/{reviewId}/reports")
    @ResponseStatus(HttpStatus.CREATED)
    public void report(@AuthenticationPrincipal UUID userId,
                       @PathVariable UUID reviewId,
                       @Valid @RequestBody ReviewReportRequest request) {
        reviewReportService.report(userId, reviewId, request);
    }

    /** 내 리뷰 삭제. 남의 리뷰면 403. */
    @DeleteMapping("/{reviewId}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal UUID userId,
                                       @PathVariable UUID reviewId) {
        reviewService.delete(userId, reviewId);
        return ResponseEntity.ok().build();
    }
}
