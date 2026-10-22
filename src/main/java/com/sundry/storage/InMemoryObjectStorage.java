package com.sundry.storage;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 测试替身：不连 MinIO，把字节留在内存里供断言。
 */
@Service
@Profile("test")
public class InMemoryObjectStorage implements ObjectStorage {

    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

    @Override
    public void put(String bucket, String key, byte[] data, String contentType) {
        objects.put(objectId(bucket, key), data.clone());
    }

    @Override
    public void delete(String bucket, String key) {
        objects.remove(objectId(bucket, key));
    }

    @Override
    public String publicUrl(String bucket, String key) {
        return "http://test.local/" + bucket + "/" + key;
    }

    public boolean exists(String bucket, String key) {
        return objects.containsKey(objectId(bucket, key));
    }

    public Set<String> keys() {
        return Set.copyOf(objects.keySet());
    }

    public void clear() {
        objects.clear();
    }

    private String objectId(String bucket, String key) {
        return bucket + "/" + key;
    }
}
