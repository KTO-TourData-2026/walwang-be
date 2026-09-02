package com.server.walwang_be.entity;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.List;

/**
 * 경로 좌표 목록 <-> JSON 문자열.
 *
 * walk_path는 지도에 선을 긋는 용도라 안을 들여다볼 일이 없다.
 * 그래서 좌표마다 행을 만드는 대신 통째로 JSON 한 덩어리로 저장한다.
 *
 * 컬럼 타입은 jsonb가 아니라 text로 둔다 — 드라이버가 문자열을 jsonb에
 * 그대로 넣지 못해 별도 설정이 필요한데, 어차피 JSON 안을 질의하지 않으므로
 * 얻는 것 없이 설정만 늘어난다.
 */
@Converter
public class WalkPathConverter implements AttributeConverter<List<List<Double>>, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<List<Double>>> TYPE = new TypeReference<>() {
    };

    @Override
    public String convertToDatabaseColumn(List<List<Double>> attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(attribute);
        } catch (Exception e) {
            throw new IllegalArgumentException("경로 좌표를 저장할 수 없습니다.", e);
        }
    }

    @Override
    public List<List<Double>> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(dbData, TYPE);
        } catch (Exception e) {
            // 저장된 값이 깨졌다고 코스 조회 전체를 실패시키지는 않는다.
            return List.of();
        }
    }
}
