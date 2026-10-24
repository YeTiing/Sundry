package com.sundry.seckill;

import com.sundry.common.api.PageResponse;
import com.sundry.seckill.dto.SaveSeckillActivityRequest;
import com.sundry.seckill.dto.SeckillActivityView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台秒杀活动管理。
 */
@RestController
@RequestMapping("/api/admin/seckill/activities")
@Validated
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN')")
public class AdminSeckillController {

    private final SeckillActivityService activityService;

    public AdminSeckillController(SeckillActivityService activityService) {
        this.activityService = activityService;
    }

    @GetMapping
    public PageResponse<SeckillActivityView> list(
            @RequestParam(required = false) SeckillStatus status,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return activityService.adminPage(status, page, size);
    }

    @GetMapping("/{id}")
    public SeckillActivityView detail(@PathVariable Long id) {
        return activityService.adminDetail(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SeckillActivityView create(@Valid @RequestBody SaveSeckillActivityRequest request) {
        return activityService.create(request);
    }

    @PutMapping("/{id}")
    public SeckillActivityView update(@PathVariable Long id,
                                      @Valid @RequestBody SaveSeckillActivityRequest request) {
        return activityService.update(id, request);
    }

    @PostMapping("/{id}/activate")
    public SeckillActivityView activate(@PathVariable Long id) {
        return activityService.activate(id);
    }

    @PostMapping("/{id}/cancel")
    public SeckillActivityView cancel(@PathVariable Long id) {
        return activityService.cancel(id);
    }

    @PostMapping("/{id}/end")
    public SeckillActivityView end(@PathVariable Long id) {
        return activityService.end(id);
    }
}
