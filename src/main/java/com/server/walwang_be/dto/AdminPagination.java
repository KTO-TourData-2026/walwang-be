package com.server.walwang_be.dto;

import org.springframework.data.domain.Page;

import java.util.function.IntFunction;

/** 관리자 화면의 이전/다음 링크. 검색 조건을 유지한 URL을 미리 만들어 넘긴다. */
public record AdminPagination(
        int page,
        int totalPages,
        long totalElements,
        boolean hasPrev,
        String prevUrl,
        boolean hasNext,
        String nextUrl
) {
    /**
     * @param urlForPage 0부터 세는 페이지 번호를 받아 그 페이지의 URL을 만든다
     */
    public static AdminPagination of(Page<?> page, IntFunction<String> urlForPage) {
        int current = page.getNumber();
        return new AdminPagination(
                current + 1,
                Math.max(page.getTotalPages(), 1),
                page.getTotalElements(),
                page.hasPrevious(),
                page.hasPrevious() ? urlForPage.apply(current - 1) : "",
                page.hasNext(),
                page.hasNext() ? urlForPage.apply(current + 1) : "");
    }
}
