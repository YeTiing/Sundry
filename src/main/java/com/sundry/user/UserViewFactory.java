package com.sundry.user;

import com.sundry.auth.dto.UserView;
import com.sundry.storage.ObjectStorage;
import org.springframework.stereotype.Component;

/**
 * 统一的用户对外视图：手机号、角色、昵称、头像 URL、简介。
 */
@Component
public class UserViewFactory {

    private final ObjectStorage storage;

    public UserViewFactory(ObjectStorage storage) {
        this.storage = storage;
    }

    public UserView from(User user) {
        String avatarUrl = null;
        if (user.getAvatarBucket() != null && user.getAvatarObjectKey() != null) {
            avatarUrl = storage.publicUrl(user.getAvatarBucket(), user.getAvatarObjectKey());
        }
        return new UserView(user.getId(), user.getPhone(), user.getRole(),
                user.getNickname(), avatarUrl, user.getBio());
    }
}
