package com.sundry.user;

import com.sundry.auth.dto.UserView;
import com.sundry.auth.security.AuthenticatedUser;
import com.sundry.common.exception.BusinessException;
import com.sundry.common.exception.ErrorCode;
import com.sundry.file.FileView;
import com.sundry.file.ImageUploadService;
import com.sundry.user.dto.UpdateProfileRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 当前登录用户的个人名片，任何登录用户都能访问。
 */
@RestController
@RequestMapping("/api/profile")
public class ProfileController {

    private static final long MAX_AVATAR_BYTES = 2L * 1024 * 1024;

    private final UserService userService;
    private final UserViewFactory userViewFactory;
    private final ImageUploadService imageUploadService;

    public ProfileController(UserService userService, UserViewFactory userViewFactory,
                             ImageUploadService imageUploadService) {
        this.userService = userService;
        this.userViewFactory = userViewFactory;
        this.imageUploadService = imageUploadService;
    }

    @GetMapping
    public UserView me(@AuthenticationPrincipal AuthenticatedUser user) {
        return userViewFactory.from(userService.requireById(requireUser(user)));
    }

    @PutMapping
    public UserView update(@Valid @RequestBody UpdateProfileRequest request,
                           @AuthenticationPrincipal AuthenticatedUser user) {
        User updated = userService.updateProfile(requireUser(user), request.nickname(), request.bio());
        return userViewFactory.from(updated);
    }

    @PostMapping("/avatar")
    public UserView uploadAvatar(@RequestPart("file") MultipartFile file,
                                 @AuthenticationPrincipal AuthenticatedUser user) {
        Long userId = requireUser(user);
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.FILE_EMPTY);
        }
        if (file.getSize() > MAX_AVATAR_BYTES) {
            throw new BusinessException(ErrorCode.AVATAR_TOO_LARGE);
        }
        FileView uploaded = imageUploadService.upload(file, userId);
        User updated = userService.updateAvatar(userId, uploaded.id());
        return userViewFactory.from(updated);
    }

    private Long requireUser(AuthenticatedUser user) {
        if (user == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return user.id();
    }
}
