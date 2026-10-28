package com.sundry.cleanup;

import com.sundry.file.FileKind;
import com.sundry.file.FileObject;
import com.sundry.file.FileObjectMapper;
import com.sundry.file.digital.DigitalUploadGateway;
import com.sundry.file.digital.UploadSessionMapper;
import com.sundry.storage.ObjectStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 孤儿文件清理：
 * 1. 软删除超过宽限期的文件
 * 2. 没有任何商品引用、且超过宽限期的文件
 *
 * 订单上线后要在清理条件里再加「没有订单引用」。
 */
@Component
public class FileCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(FileCleanupJob.class);
    private static final String LOCK_KEY = "job:file-cleanup";
    private static final Duration LOCK_TTL = Duration.ofMinutes(30);
    private static final Duration PENDING_SESSION_GRACE = Duration.ofHours(24);

    private final CleanupProperties properties;
    private final FileObjectMapper fileObjectMapper;
    private final UploadSessionMapper uploadSessionMapper;
    private final DigitalUploadGateway digitalGateway;
    private final ObjectStorage objectStorage;
    private final StringRedisTemplate redis;

    public FileCleanupJob(CleanupProperties properties, FileObjectMapper fileObjectMapper,
                          UploadSessionMapper uploadSessionMapper,
                          DigitalUploadGateway digitalGateway, ObjectStorage objectStorage,
                          StringRedisTemplate redis) {
        this.properties = properties;
        this.fileObjectMapper = fileObjectMapper;
        this.uploadSessionMapper = uploadSessionMapper;
        this.digitalGateway = digitalGateway;
        this.objectStorage = objectStorage;
        this.redis = redis;
    }

    @Scheduled(cron = "${app.cleanup.cron:0 0 3 * * *}")
    public void scheduledRun() {
        run();
    }

    /**
     * 公开方法，测试可以直接调用；Redis 锁保证多线程/多实例不会重叠执行。
     */
    public void run() {
        if (!properties.isEnabled()) {
            return;
        }
        Boolean acquired = redis.opsForValue().setIfAbsent(LOCK_KEY, "1", LOCK_TTL);
        if (!Boolean.TRUE.equals(acquired)) {
            log.info("file cleanup skipped: another run is active");
            return;
        }
        try {
            int digital = cleanupKind(FileKind.DIGITAL, properties.getDigitalGrace());
            int image = cleanupKind(FileKind.IMAGE, properties.getImageGrace());
            int sessions = uploadSessionMapper.deletePendingBefore(
                    LocalDateTime.now().minus(PENDING_SESSION_GRACE),
                    properties.getBatchSize());
            log.info("file cleanup finished: digital={}, image={}, sessions={}",
                    digital, image, sessions);
        } finally {
            redis.delete(LOCK_KEY);
        }
    }

    private int cleanupKind(FileKind kind, Duration grace) {
        LocalDateTime cutoff = LocalDateTime.now().minus(grace);
        int deleted = 0;
        for (int batch = 0; batch < properties.getMaxBatchesPerRun(); batch++) {
            List<FileObject> files = fileObjectMapper.findCleanupCandidates(
                    kind, cutoff, properties.getBatchSize());
            if (files.isEmpty()) {
                break;
            }
            for (FileObject file : files) {
                try {
                    deleteObject(file);
                    fileObjectMapper.deleteById(file.getId());
                    deleted++;
                } catch (RuntimeException ex) {
                    log.warn("failed to clean file id={}, key={}", file.getId(), file.getObjectKey(), ex);
                }
            }
            if (files.size() < properties.getBatchSize()) {
                break;
            }
        }
        return deleted;
    }

    private void deleteObject(FileObject file) {
        if (file.getKind() == FileKind.DIGITAL) {
            digitalGateway.deleteObject(file.getObjectKey());
        } else {
            objectStorage.delete(file.getBucket(), file.getObjectKey());
        }
    }
}
