package com.sundry.seckill;

import com.sundry.seckill.dto.SeckillActivityView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公开的秒杀信息：商品详情用它展示活动价和倒计时。
 */
@RestController
@RequestMapping("/api/seckill")
public class SeckillController {

    private final SeckillActivityService activityService;

    public SeckillController(SeckillActivityService activityService) {
        this.activityService = activityService;
    }

    @GetMapping("/products/{productId}")
    public SeckillActivityView productActivity(@PathVariable Long productId) {
        return activityService.activeForProduct(productId);
    }
}
