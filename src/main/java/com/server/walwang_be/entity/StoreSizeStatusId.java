package com.server.walwang_be.entity;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.UUID;

/**
 * StoreSizeStatus의 복합 키.
 * 필드명은 엔티티의 @Id 필드명과, 타입은 그 대상의 PK 타입과 같아야 한다.
 */
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@EqualsAndHashCode
public class StoreSizeStatusId implements Serializable {

    private UUID store;
    private DogSize size;
    /** 시연 집계와 실제 집계가 같은 가게·크기에 각각 한 행씩 있어야 해서 키에 들어간다. */
    private boolean demo;
}
