package com.sundry.admin.api;

import com.sundry.admin.dto.AdminUserView;
import com.sundry.admin.dto.UpdateRoleRequest;
import com.sundry.common.api.PageResponse;
import com.sundry.common.exception.BusinessException;
import com.sundry.common.exception.ErrorCode;
import com.sundry.user.Role;
import com.sundry.user.User;
import com.sundry.user.UserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户管理，只有超级管理员能访问。分页参数有上限，禁止无界查询。
 */
@RestController
@RequestMapping("/api/admin/users")
@Validated
public class AdminUserController {

    private final UserService userService;

    public AdminUserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public PageResponse<AdminUserView> list(@RequestParam(defaultValue = "1") @Min(1) int page,
                                            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        UserService.Page<User> result = userService.findPage(page, size);
        List<AdminUserView> items = result.items().stream().map(AdminUserView::from).toList();
        return new PageResponse<>(items, result.total(), page, size);
    }

    @PatchMapping("/{id}/role")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public AdminUserView updateRole(@PathVariable Long id, @Valid @RequestBody UpdateRoleRequest request) {
        User target = userService.findById(id);
        if (target == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        if (request.role() == Role.SUPER_ADMIN) {
            throw new BusinessException(ErrorCode.INVALID_ROLE);
        }
        if (!userService.changeRole(target, request.role())) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        return AdminUserView.from(userService.requireById(id));
    }
}
