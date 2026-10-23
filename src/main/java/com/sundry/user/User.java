package com.sundry.user;

import java.time.LocalDateTime;

/**
 * app_user 表的行对象。MyBatis 开启 map-underscore-to-camel-case 后，
 * password_hash 会自动映射到 passwordHash。
 */
public class User {

    private Long id;
    private String phone;
    private String nickname;
    private Long avatarFileId;
    private String bio;
    private String passwordHash;
    private Integer status;          // 1=正常，0=禁用
    private Role role;               // MyBatis 默认按枚举名映射 VARCHAR
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // 联表查询带出的头像对象信息，不对应 app_user 表字段
    private String avatarBucket;
    private String avatarObjectKey;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public Long getAvatarFileId() {
        return avatarFileId;
    }

    public void setAvatarFileId(Long avatarFileId) {
        this.avatarFileId = avatarFileId;
    }

    public String getBio() {
        return bio;
    }

    public void setBio(String bio) {
        this.bio = bio;
    }

    public String getAvatarBucket() {
        return avatarBucket;
    }

    public void setAvatarBucket(String avatarBucket) {
        this.avatarBucket = avatarBucket;
    }

    public String getAvatarObjectKey() {
        return avatarObjectKey;
    }

    public void setAvatarObjectKey(String avatarObjectKey) {
        this.avatarObjectKey = avatarObjectKey;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    // 业务判断用，避免各处直接比 status == 1
    public boolean isActive() {
        return status != null && status == 1;
    }
}
