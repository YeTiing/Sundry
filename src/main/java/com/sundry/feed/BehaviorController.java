package com.sundry.feed;

import com.sundry.auth.security.AuthenticatedUser;
import com.sundry.common.exception.BusinessException;
import com.sundry.common.exception.ErrorCode;
import com.sundry.feed.dto.ViewRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户行为上报。目前只记录商品浏览。
 */
@RestController
@RequestMapping("/api/behaviors")
public class BehaviorController {

    private final BehaviorService behaviorService;

    public BehaviorController(BehaviorService behaviorService) {
        this.behaviorService = behaviorService;
    }

    @PostMapping("/view")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void view(@Valid @RequestBody ViewRequest request,
                     @AuthenticationPrincipal AuthenticatedUser user) {
        if (user == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        behaviorService.recordView(user.id(), request.productId());
    }
}
