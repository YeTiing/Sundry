package com.sundry.file;

import com.sundry.auth.security.AuthenticatedUser;
import com.sundry.common.exception.BusinessException;
import com.sundry.common.exception.ErrorCode;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 管理端文件接口。只有管理员和超级管理员能上传。
 */
@RestController
@RequestMapping("/api/admin/files")
public class FileController {

    private final ImageUploadService imageUploadService;

    public FileController(ImageUploadService imageUploadService) {
        this.imageUploadService = imageUploadService;
    }

    @PostMapping("/images")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN')")
    public FileView uploadImage(@RequestPart("file") MultipartFile file,
                                @AuthenticationPrincipal AuthenticatedUser user) {
        if (user == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return imageUploadService.upload(file, user.id());
    }
}
