package com.server.walwang_be.controller;

import com.server.walwang_be.dto.TagListResponse;
import com.server.walwang_be.dto.TagResponse;
import com.server.walwang_be.entity.StoreTag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;

/**
 * 리뷰 작성 화면에서 고를 수 있는 해시태그 목록.
 * 태그가 enum이라 DB를 거치지 않고 그대로 내려준다.
 */
@RestController
@RequestMapping("/tags")
public class TagController {

    @GetMapping
    public ResponseEntity<TagListResponse> findAll() {
        return ResponseEntity.ok(new TagListResponse(
                Arrays.stream(StoreTag.values())
                        .map(TagResponse::from)
                        .toList()));
    }
}
