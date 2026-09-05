package com.server.walwang_be.entity;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.UUID;

/** StoreTagCount의 복합 키. */
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@EqualsAndHashCode
public class StoreTagCountId implements Serializable {

    private UUID store;
    private StoreTag tag;
    /** 시연 집계와 실제 집계가 같은 가게·태그에 각각 한 행씩 있어야 해서 키에 들어간다. */
    private boolean demo;
}
