package com.server.walwang_be.controller;

import com.server.walwang_be.dto.ReviewResponse;
import com.server.walwang_be.dto.StoreCardResponse;
import com.server.walwang_be.dto.StoreDetailResponse;
import com.server.walwang_be.entity.DogSize;
import com.server.walwang_be.service.StoreService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/stores")
@RequiredArgsConstructor
@Validated
public class StoreController {

    private final StoreService storeService;

    /**
     * 지도 핀 조회. 화면 중심 좌표에서 radius(미터) 안의 가게를 준다.
     * size를 주면 그 크기의 상태만, 안 주면 두 크기를 모두 내려준다.
     */
    @GetMapping
    public ResponseEntity<List<StoreCardResponse>> findNearby(
            @RequestParam double lat,
            @RequestParam double lng,
            @RequestParam(defaultValue = "2000") @Min(1) @Max(5000) int radius,
            @RequestParam(required = false) DogSize size) {
        return ResponseEntity.ok(storeService.findNearby(lat, lng, radius, size));
    }

    /** 가게명 부분 일치 검색. */
    @GetMapping("/search")
    public ResponseEntity<List<StoreCardResponse>> search(@RequestParam @NotBlank String keyword) {
        return ResponseEntity.ok(storeService.search(keyword));
    }

    /** 가게 상세. 리뷰 본문은 빠지고 썸네일과 태그만 포함된다. */
    @GetMapping("/{storeId}")
    public ResponseEntity<StoreDetailResponse> findDetail(@PathVariable Long storeId) {
        return ResponseEntity.ok(storeService.findDetail(storeId));
    }

    /** 가게의 리뷰 목록. 최신순. */
    @GetMapping("/{storeId}/reviews")
    public ResponseEntity<List<ReviewResponse>> findReviews(
            @PathVariable Long storeId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok(storeService.findReviews(storeId, page, size));
    }

    /** 가게 저장. 이미 저장돼 있어도 200. */
    @PostMapping("/{storeId}/save")
    public ResponseEntity<Void> saveStore(@AuthenticationPrincipal Long userId,
                                          @PathVariable Long storeId) {
        storeService.saveStore(userId, storeId);
        return ResponseEntity.ok().build();
    }

    /** 저장 해제. 저장돼 있지 않아도 200. */
    @DeleteMapping("/{storeId}/save")
    public ResponseEntity<Void> unsaveStore(@AuthenticationPrincipal Long userId,
                                            @PathVariable Long storeId) {
        storeService.unsaveStore(userId, storeId);
        return ResponseEntity.ok().build();
    }
}
