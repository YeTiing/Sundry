package com.sundry.storage;

/**
 * 存储层失败（网络、凭据、桶不存在等）。由业务层转成统一错误码。
 */
public class StorageException extends RuntimeException {

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
