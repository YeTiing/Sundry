package com.sundry.assistant;

import com.sundry.user.Role;
import tools.jackson.databind.JsonNode;

import java.util.Map;
import java.util.Set;

/**
 * 可执行工具。模型只负责选工具和填参，权限与执行都在 Java。
 */
public interface AssistantTool {

    String name();

    String description();

    Set<Role> roles();

    Map<String, Object> parameters();

    AssistantToolResult execute(AssistantToolContext context, JsonNode args);
}
