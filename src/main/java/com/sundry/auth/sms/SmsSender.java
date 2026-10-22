package com.sundry.auth.sms;

/**
 * 短信发送抽象。开发环境用日志实现，生产替换成阿里云 / 腾讯云实现即可。
 */
public interface SmsSender {

    void send(String phone, String code, SmsScene scene);
}
