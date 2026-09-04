package com.server.walwang_be.entity;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.UUID;

/**
 * SavedStore의 복합 키.
 * 필드 이름이 SavedStore의 @Id 필드와 하나씩 맞아야 한다(user, store, demo).
 */
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@EqualsAndHashCode
public class SavedStoreId implements Serializable {

    private UUID user;
    private UUID store;
    /** 시연 쪽과 실제 쪽에 같은 가게를 각각 저장할 수 있어야 해서 키에 들어간다. */
    private boolean demo;
}
