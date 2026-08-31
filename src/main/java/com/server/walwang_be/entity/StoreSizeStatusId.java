package com.server.walwang_be.entity;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * StoreSizeStatus의 복합 키.
 * 필드명은 엔티티의 @Id 필드명과, 타입은 그 대상의 PK 타입과 같아야 한다.
 */
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@EqualsAndHashCode
public class StoreSizeStatusId implements Serializable {

    private Long store;
    private DogSize size;
}
