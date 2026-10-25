package com.sundry.assistant;

import com.sundry.user.Role;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 工具注册表：按角色裁剪后下发给模型，执行前再校验一次。
 */
@Component
public class AssistantToolRegistry {

    private final List<AssistantTool> tools;

    public AssistantToolRegistry(List<AssistantTool> tools) {
        this.tools = List.copyOf(tools);
    }

    public List<AssistantToolSpec> specsFor(Role role) {
        return tools.stream()
                .filter(tool -> tool.roles().contains(role))
                .map(this::spec)
                .toList();
    }

    public AssistantTool find(String name, Role role) {
        return tools.stream()
                .filter(tool -> tool.name().equals(name) && tool.roles().contains(role))
                .findFirst()
                .orElse(null);
    }

    public boolean exists(String name) {
        return tools.stream().anyMatch(tool -> tool.name().equals(name));
    }

    private AssistantToolSpec spec(AssistantTool tool) {
        return new AssistantToolSpec("function", new AssistantToolSpec.Function(
                tool.name(), tool.description(), tool.parameters()));
    }
}
