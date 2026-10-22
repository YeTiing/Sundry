package com.sundry.auth.sms;

public interface SmsSender {

    void send(String phone, String code, SmsScene scene);
}
