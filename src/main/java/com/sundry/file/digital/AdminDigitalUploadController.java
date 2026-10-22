package com.sundry.file.digital;

import com.sundry.auth.security.AuthenticatedUser;
import com.sundry.common.exception.BusinessException;
import com.sundry.common.exception.ErrorCode;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 数字商品大文件上传：后端只发预签名，分片字节由浏览器直接写 MinIO 私有桶。
 */
@RestController
@RequestMapping("/api/admin/uploads/digital")
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN')")
public class AdminDigitalUploadController {

    private final DigitalUploadService uploadService;

    public AdminDigitalUploadController(DigitalUploadService uploadService) {
        this.uploadService = uploadService;
    }

    @PostMapping("/init")
    public DigitalUploadView init(@Valid @RequestBody InitDigitalUploadRequest request,
                                  @AuthenticationPrincipal AuthenticatedUser user) {
        return uploadService.init(requireUser(user), request);
    }

    @PostMapping("/complete")
    public DigitalFileView complete(@Valid @RequestBody CompleteDigitalUploadRequest request,
                                    @AuthenticationPrincipal AuthenticatedUser user) {
        return uploadService.complete(requireUser(user), request);
    }

    @PostMapping("/abort")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void abort(@Valid @RequestBody AbortDigitalUploadRequest request,
                      @AuthenticationPrincipal AuthenticatedUser user) {
        uploadService.abort(requireUser(user), request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id, @AuthenticationPrincipal AuthenticatedUser user) {
        if (user == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        uploadService.delete(user.id(), user.role(), id);
    }

    private Long requireUser(AuthenticatedUser user) {
        if (user == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return user.id();
    }
}
