package com.server.walwang_be.dto;

import com.server.walwang_be.entity.StoreTag;
import com.server.walwang_be.entity.TagCategory;

/**
 * 선택 가능한 해시태그 하나.
 *
 * code가 식별자다 — 리뷰를 올릴 때 이 값을 그대로 tags에 담아 보내면 된다.
 * name은 화면에 보여줄 글자라 언제든 바뀔 수 있으니 식별자로 쓰면 안 된다.
 */
public record TagResponse(
        String code,
        String name,
        TagCategory category
) {
    public static TagResponse from(StoreTag tag) {
        return new TagResponse(tag.code(), tag.label(), tag.category());
    }
}
