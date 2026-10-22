package com.sundry.auth.sms;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 开发环境短信通道：只把验证码写日志。生产必须切换为真实通道。
 */
@Component // 注册为 Spring Bean，SmsCodeService 按 SmsSender 接口注入
// 条件装配：只有 app.sms.provider=log（或未配置）时才创建这个实现
@ConditionalOnProperty(name = "app.sms.provider", havingValue = "log", matchIfMissing = true)
public class LoggingSmsSender implements SmsSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingSmsSender.class);

    @Override
    public void send(String phone, String code, SmsScene scene) {
        log.info("[dev-sms] phone={} scene={} code={}", phone, scene, code);
    }
}
