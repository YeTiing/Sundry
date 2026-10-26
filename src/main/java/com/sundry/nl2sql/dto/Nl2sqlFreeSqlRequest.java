package com.sundry.nl2sql.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record Nl2sqlFreeSqlRequest(@NotBlank @Size(max = 2000) String sql) {
}
