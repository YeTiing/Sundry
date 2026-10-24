package com.sundry.common.api;

import java.util.List;

/**
 * 游标分页结果。nextCursor 为空表示没有下一页。
 */
public record ScrollPage<T>(List<T> items, Long nextCursor, boolean hasMore) {
}
