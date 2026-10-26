package com.sundry.nl2sql;

import java.util.List;

public record QuerySpec(String sql, List<Object> params) {
}
