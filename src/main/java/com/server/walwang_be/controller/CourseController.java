package com.server.walwang_be.controller;

import com.server.walwang_be.dto.CourseCreateResponse;
import com.server.walwang_be.dto.CourseRecommendRequest;
import com.server.walwang_be.dto.CourseResponse;
import com.server.walwang_be.dto.CourseSaveRequest;
import com.server.walwang_be.service.CourseService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 클래스 단위 @RequestMapping을 두지 않는다 —
 * 삭제만 /course(단수)로 열기로 해서, 경로를 메서드마다 그대로 적는 편이 헷갈리지 않는다.
 */
@RestController
@RequiredArgsConstructor
public class CourseController {

    private final CourseService courseService;

    /**
     * 코스 추천. 저장하지 않고 결과만 돌려준다 —
     * 마음에 들면 클라이언트가 그 값을 그대로 POST /courses 로 보내 저장한다.
     */
    @PostMapping("/courses/recommend")
    public ResponseEntity<CourseResponse> recommend(@Valid @RequestBody CourseRecommendRequest request) {
        return ResponseEntity.ok(courseService.recommend(request));
    }

    /** 코스 저장. storeIds의 순서가 곧 방문 순서다. */
    @PostMapping("/courses")
    public ResponseEntity<CourseCreateResponse> save(@AuthenticationPrincipal Long userId,
                                                     @Valid @RequestBody CourseSaveRequest request) {
        return ResponseEntity.ok(new CourseCreateResponse(courseService.save(userId, request)));
    }

    /** 저장한 코스 상세. 남의 코스면 403. */
    @GetMapping("/courses/{courseId}")
    public ResponseEntity<CourseResponse> findOne(@AuthenticationPrincipal Long userId,
                                                  @PathVariable Long courseId) {
        return ResponseEntity.ok(courseService.findOne(userId, courseId));
    }

    /** 저장한 코스 삭제. 남의 코스면 403. 명세대로 여기만 /course(단수)다. */
    @DeleteMapping("/course/{courseId}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Long userId,
                                       @PathVariable Long courseId) {
        courseService.delete(userId, courseId);
        return ResponseEntity.ok().build();
    }
}
