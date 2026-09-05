package com.server.walwang_be.controller;

import com.server.walwang_be.dto.ReceiptVerifyResponse;
import com.server.walwang_be.dto.ReviewCreateRequest;
import com.server.walwang_be.dto.ReviewCreateResponse;
import com.server.walwang_be.dto.ReviewReportRequest;
import com.server.walwang_be.service.ReviewReportService;
import com.server.walwang_be.service.ReviewService;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Encoding;
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
     *
     * demo=true면 영수증 인증을 건너뛴다. 대신 그 리뷰는 verified=false로 남아
     * 작성자 본인에게만 보이고 점수·임베딩에는 들어가지 않는다. 도장은 정상적으로 찍힌다.
     *
     * @Encoding 은 문서에만 쓰이고 서버 동작에는 영향이 없다. 이게 없으면 springdoc 이
     * 만든 문서에 data 파트의 Content-Type 이 비어 나가고, Swagger UI 가 그 파트를
     * JSON 으로 보내지 않아 415가 난다 — 스펙상 기본값이 application/json 이라도
     * UI 가 그렇게 채워 주지는 않는다. curl 처럼 파트 타입을 직접 붙이는 쪽은 원래 잘 됐다.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            content = @Content(
                    mediaType = MediaType.MULTIPART_FORM_DATA_VALUE,
                    encoding = @Encoding(
                            name = "data",
                            contentType = MediaType.APPLICATION_JSON_VALUE)))
    public ResponseEntity<ReviewCreateResponse> create(
            @AuthenticationPrincipal UUID userId,
            @RequestPart("data") @Valid ReviewCreateRequest data,
            @RequestPart(value = "photo", required = false) MultipartFile photo,
            @RequestParam(defaultValue = "false") boolean demo) {
        return ResponseEntity.ok(reviewService.create(userId, data, photo, demo));
    }

    /**
     * 영수증 인증. 통과하면 30분짜리 receipt_token을 발급하고,
     * 리뷰 등록은 그 토큰이 있어야 통과한다.
     *
     * 상호명이 안 맞는 건 실패가 아니라 verified=false인 200 응답이다.
     *
     * demo=true면 응답의 matchedName이 가명으로 나간다 — 대조 자체는 실제 상호로 한다.
     */
    @PostMapping(value = "/receipt-verify", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ReceiptVerifyResponse> verifyReceipt(
            @AuthenticationPrincipal UUID userId,
            @RequestPart("receipt") MultipartFile receipt,
            @RequestParam("store_id") UUID storeId,
            @RequestParam(defaultValue = "false") boolean demo) {
        return ResponseEntity.ok(reviewService.verifyReceipt(userId, storeId, receipt, demo));
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
