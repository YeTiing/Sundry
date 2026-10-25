package com.sundry.cleanup;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 开启定时任务与异步事件。清理任务在 FileCleanupJob，cron 由 app.cleanup.cron 配置。
 */
@Configuration
@EnableScheduling
@EnableAsync
public class SchedulingConfig {
}
