package com.server.walwang_be.controller;

import com.server.walwang_be.dto.ReceiptVerifyResponse;
import com.server.walwang_be.dto.ReviewCreateRequest;
import com.server.walwang_be.dto.ReviewCreateResponse;
import com.server.walwang_be.service.ReviewService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/reviews")
@RequiredArgsConstructor
@Validated
public class ReviewController {

    private final ReviewService reviewService;

    /**
     * 리뷰 등록.
     *
     * data 파트는 Content-Type이 application/json이어야 한다 —
     * 안 그러면 Spring이 문자열로 받아 역직렬화하지 못한다.
     * photo는 선택이다(거절 리뷰에는 사진이 없다).
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ReviewCreateResponse> create(
            @AuthenticationPrincipal Long userId,
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
            @AuthenticationPrincipal Long userId,
            @RequestPart("receipt") MultipartFile receipt,
            @RequestParam("store_id") Long storeId) {
        return ResponseEntity.ok(reviewService.verifyReceipt(userId, storeId, receipt));
    }

    /** 내 리뷰 삭제. 남의 리뷰면 403. */
    @DeleteMapping("/{reviewId}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Long userId,
                                       @PathVariable Long reviewId) {
        reviewService.delete(userId, reviewId);
        return ResponseEntity.ok().build();
    }
}
