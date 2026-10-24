package com.sundry.seckill;

import com.sundry.auth.security.AuthenticatedUser;
import com.sundry.common.exception.BusinessException;
import com.sundry.common.exception.ErrorCode;
import com.sundry.seckill.dto.SeckillResultView;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/seckill/results")
public class SeckillResultController {

    private final SeckillResultService resultService;

    public SeckillResultController(SeckillResultService resultService) {
        this.resultService = resultService;
    }

    @GetMapping("/{requestId}")
    public SeckillResultView result(@PathVariable String requestId,
                                    @AuthenticationPrincipal AuthenticatedUser user) {
        if (user == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        SeckillResultView result = resultService.getForUser(requestId, user.id());
        if (result == null) {
            throw new BusinessException(ErrorCode.SECKILL_RESULT_NOT_FOUND);
        }
        return result;
    }
}
