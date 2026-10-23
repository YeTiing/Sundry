package com.sundry.user;

import com.jayway.jsonpath.JsonPath;
import com.sundry.auth.CapturingSmsSender;
import com.sundry.auth.sms.SmsScene;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 用户名片与店铺名片。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProfileShopIntegrationTest {

    private static final String SUPER_PHONE = "13900000000";
    private static final String USER_PHONE = "13800138001";
    private static final String PASSWORD = "secret123";
    private static final byte[] TINY_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8/5+hHgAHggJ/PzWvWQAAAABJRU5ErkJggg==");

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
        jdbcTemplate.update("DELETE FROM order_item");
        jdbcTemplate.update("DELETE FROM orders");
        jdbcTemplate.update("DELETE FROM product");
        jdbcTemplate.update("DELETE FROM file_object");
        jdbcTemplate.update("DELETE FROM app_user");
        jdbcTemplate.update("""
                UPDATE shop_profile
                SET shop_name = 'Sundry', description = '自营杂货。',
                    contact = NULL, logo_file_id = NULL
                WHERE id = 1
                """);
        Set<String> keys = redis.keys("auth:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
        smsSender.clear();
    }

    @Test
    void userCanEditProfileAndUploadAvatar() throws Exception {
        Session user = register(USER_PHONE);

        mockMvc.perform(put("/api/profile")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nickname":"小杂货","bio":"只卖自己的东西"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value("小杂货"))
                .andExpect(jsonPath("$.bio").value("只卖自己的东西"));

        MvcResult avatar = mockMvc.perform(multipart("/api/profile/avatar")
                        .file(new MockMultipartFile("file", "avatar.png", "image/png", TINY_PNG))
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avatarUrl").isNotEmpty())
                .andReturn();

        String avatarUrl = JsonPath.read(
                avatar.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.avatarUrl");
        assertThat(avatarUrl).startsWith("http://test.local/sundry-public/");

        mockMvc.perform(get("/api/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value("小杂货"))
                .andExpect(jsonPath("$.avatarUrl").isNotEmpty());
    }

    @Test
    void oversizedAvatarIsRejected() throws Exception {
        Session user = register(USER_PHONE);

        mockMvc.perform(multipart("/api/profile/avatar")
                        .file(new MockMultipartFile("file", "big.png", "image/png",
                                new byte[2 * 1024 * 1024 + 1]))
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("AVATAR_TOO_LARGE"));
    }

    @Test
    void superAdminCanEditShopAndPublicCanReadIt() throws Exception {
        Session superAdmin = register(SUPER_PHONE);

        mockMvc.perform(put("/api/admin/shop")
                        .header(HttpHeaders.AUTHORIZATION, bearer(superAdmin.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"shopName":"测试杂货铺","description":"只卖自己做的","contact":"微信 sundry"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shopName").value("测试杂货铺"))
                .andExpect(jsonPath("$.contact").value("微信 sundry"));

        mockMvc.perform(get("/api/shop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shopName").value("测试杂货铺"))
                .andExpect(jsonPath("$.description").value("只卖自己做的"));
    }

    @Test
    void normalUserCannotEditShop() throws Exception {
        Session user = register(USER_PHONE);

        mockMvc.perform(put("/api/admin/shop")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"shopName":"越权"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void superAdminCanSetShopLogo() throws Exception {
        Session superAdmin = register(SUPER_PHONE);
        MvcResult uploaded = mockMvc.perform(multipart("/api/admin/files/images")
                        .file(new MockMultipartFile("file", "logo.png", "image/png", TINY_PNG))
                        .header(HttpHeaders.AUTHORIZATION, bearer(superAdmin.token())))
                .andExpect(status().isOk())
                .andReturn();
        long fileId = ((Number) JsonPath.read(
                uploaded.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.id")).longValue();

        mockMvc.perform(put("/api/admin/shop")
                        .header(HttpHeaders.AUTHORIZATION, bearer(superAdmin.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"shopName":"Sundry","logoFileId":%d}
                                """.formatted(fileId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.logoUrl").isNotEmpty());

        mockMvc.perform(get("/api/shop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.logoUrl").isNotEmpty());
    }

    private Session register(String phone) throws Exception {
        redis.delete("auth:sms:cooldown:" + phone);
        mockMvc.perform(post("/api/auth/sms-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phone":"%s","scene":"REGISTER"}
                                """.formatted(phone)))
                .andExpect(status().isNoContent());

        String code = smsSender.code(SmsScene.REGISTER, phone);
        assertThat(code).isNotNull();

        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phone":"%s","code":"%s","password":"%s"}
                                """.formatted(phone, code, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        String token = JsonPath.read(body, "$.accessToken");
        Number id = JsonPath.read(body, "$.user.id");
        return new Session(token, id.longValue());
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private record Session(String token, Long id) {
    }
}
