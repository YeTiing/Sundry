package com.sundry.cleanup;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 开启定时任务。清理任务在 FileCleanupJob，cron 由 app.cleanup.cron 配置。
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
