package com.sundry.file;

import com.sundry.auth.security.AuthenticatedUser;
import com.sundry.common.exception.BusinessException;
import com.sundry.common.exception.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 通用文件删除：图片和数字文件都走这里，软删除。
 */
@RestController
@RequestMapping("/api/admin/files")
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN')")
public class AdminFileController {

    private final FileDeletionService deletionService;
    private final ImageReuseService imageReuseService;

    public AdminFileController(FileDeletionService deletionService, ImageReuseService imageReuseService) {
        this.deletionService = deletionService;
        this.imageReuseService = imageReuseService;
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id, @AuthenticationPrincipal AuthenticatedUser user) {
        if (user == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        deletionService.delete(user.id(), user.role(), id);
    }

    @GetMapping("/images/reusable")
    public java.util.List<ReusableImageView> reusableImages(
            @AuthenticationPrincipal AuthenticatedUser user) {
        if (user == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return imageReuseService.reusable(user.id());
    }

    @PostMapping("/images/{id}/reuse")
    public ReusableImageView reuseImage(@PathVariable Long id,
                                        @AuthenticationPrincipal AuthenticatedUser user) {
        if (user == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return imageReuseService.reuse(user.id(), user.role(), id);
    }
}
