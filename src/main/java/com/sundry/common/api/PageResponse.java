package com.sundry.common.api;

import java.util.List;

/**
 * 统一分页响应。列表接口不允许无界查询，必须带 total/page/size。
 */
public record PageResponse<T>(List<T> items, long total, int page, int size) {
}
