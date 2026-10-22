package com.sundry.auth;

import com.jayway.jsonpath.JsonPath;
import com.sundry.auth.sms.SmsScene;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthFlowIntegrationTest {

    private static final String PHONE = "13800138000";
    private static final String PASSWORD = "secret123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private CapturingSmsSender smsSender;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM app_user");
        Set<String> keys = redis.keys("auth:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
        smsSender.clear();
    }

    @Test
    void registerLoginRefreshAndLogout() throws Exception {
        String registerCode = requestCode(PHONE, SmsScene.REGISTER);
        MvcResult registered = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phone":"%s","code":"%s","password":"%s"}
                                """.formatted(PHONE, registerCode, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.user.phone").value(PHONE))
                .andReturn();

        String accessToken = json(registered, "$.accessToken");
        String refreshToken = refreshCookie(registered);

        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").value(PHONE));

        mockMvc.perform(post("/api/auth/login/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phone":"%s","password":"%s"}
                                """.formatted(PHONE, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());

        String loginCode = requestCode(PHONE, SmsScene.LOGIN);
        mockMvc.perform(post("/api/auth/login/code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phone":"%s","code":"%s"}
                                """.formatted(PHONE, loginCode)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());

        MvcResult refreshed = mockMvc.perform(post("/api/auth/refresh")
                        .cookie(new Cookie("sundry_refresh", refreshToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andReturn();
        String rotatedRefresh = refreshCookie(refreshed);
        assertThat(rotatedRefresh).isNotEqualTo(refreshToken);

        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(new Cookie("sundry_refresh", refreshToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));

        String refreshedAccess = json(refreshed, "$.accessToken");
        mockMvc.perform(post("/api/auth/logout")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + refreshedAccess)
                        .cookie(new Cookie("sundry_refresh", rotatedRefresh)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + refreshedAccess))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void registeredPhoneCannotRequestRegisterCode() throws Exception {
        String code = requestCode(PHONE, SmsScene.REGISTER);
        register(PHONE, code);

        mockMvc.perform(post("/api/auth/sms-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phone":"%s","scene":"REGISTER"}
                                """.formatted(PHONE)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PHONE_ALREADY_REGISTERED"));
    }

    @Test
    void duplicatePhoneAtInsertIsRejected() throws Exception {
        String code = requestCode(PHONE, SmsScene.REGISTER);
        jdbcTemplate.update("INSERT INTO app_user (phone, password_hash, status) VALUES (?, ?, 1)",
                PHONE, "placeholder");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phone":"%s","code":"%s","password":"%s"}
                                """.formatted(PHONE, code, PASSWORD)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PHONE_ALREADY_REGISTERED"));
    }

    @Test
    void loginCodeForUnknownPhoneIsRejected() throws Exception {
        mockMvc.perform(post("/api/auth/sms-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phone":"%s","scene":"LOGIN"}
                                """.formatted(PHONE)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void wrongCodeIsRejectedAndCodeStaysUsable() throws Exception {
        String code = requestCode(PHONE, SmsScene.REGISTER);
        String wrong = "000000".equals(code) ? "111111" : "000000";

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phone":"%s","code":"%s","password":"%s"}
                                """.formatted(PHONE, wrong, PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SMS_CODE_INVALID"));

        register(PHONE, code);
    }

    @Test
    void tooManyWrongAttemptsLockTheCode() throws Exception {
        String code = requestCode(PHONE, SmsScene.REGISTER);
        String wrong = "000000".equals(code) ? "111111" : "000000";

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"phone":"%s","code":"%s","password":"%s"}
                                    """.formatted(PHONE, wrong, PASSWORD)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("SMS_CODE_INVALID"));
        }

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phone":"%s","code":"%s","password":"%s"}
                                """.formatted(PHONE, code, PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SMS_CODE_ATTEMPTS_EXCEEDED"));
    }

    @Test
    void smsSendIsRateLimited() throws Exception {
        mockMvc.perform(post("/api/auth/sms-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phone":"%s","scene":"REGISTER"}
                                """.formatted(PHONE)))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/auth/sms-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phone":"%s","scene":"REGISTER"}
                                """.formatted(PHONE)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("SMS_SEND_TOO_FREQUENT"));
    }

    @Test
    void invalidPhoneIsRejected() throws Exception {
        mockMvc.perform(post("/api/auth/sms-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phone":"123","scene":"REGISTER"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void wrongPasswordIsRejected() throws Exception {
        String code = requestCode(PHONE, SmsScene.REGISTER);
        register(PHONE, code);

        mockMvc.perform(post("/api/auth/login/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phone":"%s","password":"wrong123"}
                                """.formatted(PHONE)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void meWithoutTokenIsRejected() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    private void register(String phone, String code) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phone":"%s","code":"%s","password":"%s"}
                                """.formatted(phone, code, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }

    private String requestCode(String phone, SmsScene scene) throws Exception {
        redis.delete("auth:sms:cooldown:" + phone);
        mockMvc.perform(post("/api/auth/sms-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phone":"%s","scene":"%s"}
                                """.formatted(phone, scene)))
                .andExpect(status().isNoContent());
        String code = smsSender.code(scene, phone);
        assertThat(code).as("验证码应已发送").isNotNull();
        return code;
    }

    private String json(MvcResult result, String path) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8), path);
    }

    private String refreshCookie(MvcResult result) {
        String header = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(header).as("应设置 refresh cookie").isNotNull();
        return header.split(";", 2)[0].split("=", 2)[1];
    }
}
