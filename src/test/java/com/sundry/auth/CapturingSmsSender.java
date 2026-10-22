package com.sundry.auth;

import com.sundry.auth.sms.SmsScene;
import com.sundry.auth.sms.SmsSender;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 测试用短信通道：不发短信，把验证码留在内存里供断言。
 * @Profile("test") 只在 test profile 下装配，与 LoggingSmsSender 互斥。
 */
@Component
@Profile("test")
public class CapturingSmsSender implements SmsSender {

    private final Map<String, String> codes = new ConcurrentHashMap<>();

    @Override
    public void send(String phone, String code, SmsScene scene) {
        codes.put(key(scene, phone), code);
    }

    public String code(SmsScene scene, String phone) {
        return codes.get(key(scene, phone));
    }

    public void clear() {
        codes.clear();
    }

    private String key(SmsScene scene, String phone) {
        return scene.name() + ":" + phone;
    }
}
