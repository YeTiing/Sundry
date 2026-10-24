package com.sundry.ai.dto;

import java.util.List;

/**
 * Python 返回的重排结果。Java 会再次校验 ID 是否属于候选集合。
 */
public record RerankResponse(List<RerankItem> items, String model, String promptVersion) {

    public record RerankItem(Long id, String reason) {
    }
}
