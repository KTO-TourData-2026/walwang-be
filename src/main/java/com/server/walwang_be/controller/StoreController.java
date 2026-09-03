package com.server.walwang_be.controller;

import com.server.walwang_be.service.AlternativeStoreService;
import com.server.walwang_be.dto.CourseStoreResponse;
import com.server.walwang_be.dto.ReviewResponse;
import com.server.walwang_be.dto.StoreCardResponse;
import com.server.walwang_be.dto.StoreDetailResponse;
import com.server.walwang_be.entity.DogSize;
import com.server.walwang_be.service.MapStoreService;
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
import java.util.UUID;

@RestController
@RequestMapping("/stores")
@RequiredArgsConstructor
@Validated
public class StoreController {

    private final StoreService storeService;
    private final AlternativeStoreService alternativeStoreService;
    private final MapStoreService mapStoreService;

    /**
     * 지도 핀 조회. 공사 데이터와 매칭된 가게만 준다 —
     * 반려동물 동반이 확인된 곳이라 지도가 훨씬 읽힌다.
     *
     * 공사 API를 요청마다 호출하며, 실패하면 빈 목록을 준다(지도는 떠야 한다).
     * size를 주면 그 크기로 동반 불가인 곳을 뺀다.
     */
    @GetMapping
    public ResponseEntity<List<StoreCardResponse>> findNearby(
            @RequestParam double lat,
            @RequestParam double lng,
            @RequestParam(defaultValue = "2000") @Min(1) @Max(5000) int radius,
            @RequestParam(required = false) DogSize size,
            @RequestParam(defaultValue = "false") boolean demo) {
        return ResponseEntity.ok(mapStoreService.findVerifiedNearby(lat, lng, radius, size, demo));
    }

    /** 가게명 부분 일치 검색. */
    @GetMapping("/search")
    public ResponseEntity<List<StoreCardResponse>> search(
            @RequestParam @NotBlank String keyword,
            @RequestParam(defaultValue = "false") boolean demo) {
        return ResponseEntity.ok(storeService.search(keyword, demo));
    }

    /** 가게 상세. 리뷰 본문은 빠지고 썸네일과 태그만 포함된다. */
    @GetMapping("/{storeId}")
    public ResponseEntity<StoreDetailResponse> findDetail(
            @PathVariable UUID storeId,
            @RequestParam(defaultValue = "false") boolean demo) {
        return ResponseEntity.ok(storeService.findDetail(storeId, demo));
    }

    /** 가게의 리뷰 목록. 최신순. */
    @GetMapping("/{storeId}/reviews")
    public ResponseEntity<List<ReviewResponse>> findReviews(
            @PathVariable UUID storeId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "false") boolean demo) {
        return ResponseEntity.ok(storeService.findReviews(storeId, page, size, demo));
    }

    /** 가게 저장. 이미 저장돼 있어도 200. */
    @PostMapping("/{storeId}/save")
    public ResponseEntity<Void> saveStore(@AuthenticationPrincipal UUID userId,
                                          @PathVariable UUID storeId) {
        storeService.saveStore(userId, storeId);
        return ResponseEntity.ok().build();
    }

    /** 저장 해제. 저장돼 있지 않아도 200. */
    @DeleteMapping("/{storeId}/save")
    public ResponseEntity<Void> unsaveStore(@AuthenticationPrincipal UUID userId,
                                            @PathVariable UUID storeId) {
        storeService.unsaveStore(userId, storeId);
        return ResponseEntity.ok().build();
    }
    /**
     * 거절당했을 때의 대안. 기준 가게 반경 500m 안, 같은 업종, 가까운 순 최대 3곳.
     *
     * 코스 추천과 같은 근거 기준을 쓴다 — 공사 확인이나 리뷰 확인 중 하나는 있어야 하고,
     * 해당 크기로 동반 불가인 곳은 뺀다. 조건에 맞는 곳이 없으면 빈 배열이다.
     */
    @GetMapping("/{storeId}/alternatives")
    public ResponseEntity<List<CourseStoreResponse>> findAlternatives(
            @PathVariable UUID storeId,
            @RequestParam DogSize size,
            @RequestParam(defaultValue = "false") boolean demo) {
        return ResponseEntity.ok(alternativeStoreService.findAlternatives(storeId, size, demo));
    }
}
