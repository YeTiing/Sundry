package com.sundry.admin;

import com.jayway.jsonpath.JsonPath;
import com.sundry.auth.CapturingSmsSender;
import com.sundry.auth.sms.SmsScene;
import com.sundry.storage.InMemoryObjectStorage;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 角色与图片上传的集成测试：真实 MySQL + Redis，存储使用内存替身。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminFlowIntegrationTest {

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

    @Autowired
    private InMemoryObjectStorage storage;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM seckill_activity");
        jdbcTemplate.update("DELETE FROM product");
        jdbcTemplate.update("DELETE FROM file_object");
        jdbcTemplate.update("DELETE FROM order_item");
        jdbcTemplate.update("DELETE FROM orders");
        jdbcTemplate.update("DELETE FROM app_user");
        Set<String> keys = redis.keys("auth:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
        smsSender.clear();
        storage.clear();
    }

    @Test
    void superAdminPhoneRegistersAsSuperAdmin() throws Exception {
        Session superAdmin = register(SUPER_PHONE);

        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(superAdmin.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("SUPER_ADMIN"));
    }

    @Test
    void normalUserRegistersAsUser() throws Exception {
        Session user = register(USER_PHONE);

        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("USER"));
    }

    @Test
    void adminCanUploadImage() throws Exception {
        Session superAdmin = register(SUPER_PHONE);

        mockMvc.perform(multipart("/api/admin/files/images")
                        .file(new MockMultipartFile("file", "tiny.png", "image/png", TINY_PNG))
                        .header(HttpHeaders.AUTHORIZATION, bearer(superAdmin.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").isNotEmpty())
                .andExpect(jsonPath("$.contentType").value("image/png"));

        assertThat(storage.keys()).anyMatch(id -> id.startsWith("sundry-public/products/"));
    }

    @Test
    void normalUserCannotUpload() throws Exception {
        Session user = register(USER_PHONE);

        mockMvc.perform(multipart("/api/admin/files/images")
                        .file(new MockMultipartFile("file", "tiny.png", "image/png", TINY_PNG))
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void unauthenticatedUploadIsRejected() throws Exception {
        mockMvc.perform(multipart("/api/admin/files/images")
                        .file(new MockMultipartFile("file", "tiny.png", "image/png", TINY_PNG)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void nonImageContentIsRejected() throws Exception {
        Session superAdmin = register(SUPER_PHONE);

        mockMvc.perform(multipart("/api/admin/files/images")
                        .file(new MockMultipartFile("file", "fake.png", "image/png",
                                "not an image".getBytes(StandardCharsets.UTF_8)))
                        .header(HttpHeaders.AUTHORIZATION, bearer(superAdmin.token())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_FILE_TYPE"));
    }

    @Test
    void oversizedImageIsRejected() throws Exception {
        Session superAdmin = register(SUPER_PHONE);
        byte[] oversized = new byte[5 * 1024 * 1024 + 1];

        mockMvc.perform(multipart("/api/admin/files/images")
                        .file(new MockMultipartFile("file", "big.png", "image/png", oversized))
                        .header(HttpHeaders.AUTHORIZATION, bearer(superAdmin.token())))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("FILE_TOO_LARGE"));
    }

    @Test
    void promotedUserCanUploadImmediately() throws Exception {
        Session superAdmin = register(SUPER_PHONE);
        Session user = register(USER_PHONE);

        // 提升前 403
        mockMvc.perform(multipart("/api/admin/files/images")
                        .file(new MockMultipartFile("file", "tiny.png", "image/png", TINY_PNG))
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isForbidden());

        mockMvc.perform(patch("/api/admin/users/{id}/role", user.id())
                        .header(HttpHeaders.AUTHORIZATION, bearer(superAdmin.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role":"ADMIN"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));

        // 角色从数据库实时读取，旧 token 不用重新登录就能上传
        mockMvc.perform(multipart("/api/admin/files/images")
                        .file(new MockMultipartFile("file", "tiny.png", "image/png", TINY_PNG))
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isOk());
    }

    @Test
    void normalUserCannotListOrChangeRoles() throws Exception {
        Session user = register(USER_PHONE);

        mockMvc.perform(get("/api/admin/users")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isForbidden());

        mockMvc.perform(patch("/api/admin/users/{id}/role", user.id())
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role":"ADMIN"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void superAdminCannotBeDemotedOrGrantedByApi() throws Exception {
        Session superAdmin = register(SUPER_PHONE);
        Session user = register(USER_PHONE);

        mockMvc.perform(patch("/api/admin/users/{id}/role", superAdmin.id())
                        .header(HttpHeaders.AUTHORIZATION, bearer(superAdmin.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role":"ADMIN"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ROLE_CHANGE_FORBIDDEN"));

        mockMvc.perform(patch("/api/admin/users/{id}/role", user.id())
                        .header(HttpHeaders.AUTHORIZATION, bearer(superAdmin.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role":"SUPER_ADMIN"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ROLE"));
    }

    @Test
    void userListIsPaged() throws Exception {
        Session superAdmin = register(SUPER_PHONE);
        register(USER_PHONE);
        register("13800138002");

        mockMvc.perform(get("/api/admin/users")
                        .param("page", "1")
                        .param("size", "2")
                        .header(HttpHeaders.AUTHORIZATION, bearer(superAdmin.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(2));
    }

    @Test
    void userListRejectsUnboundedSize() throws Exception {
        Session superAdmin = register(SUPER_PHONE);

        mockMvc.perform(get("/api/admin/users")
                        .param("size", "1000")
                        .header(HttpHeaders.AUTHORIZATION, bearer(superAdmin.token())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void adminSoftDeletesUploadedImage() throws Exception {
        Session superAdmin = register(SUPER_PHONE);

        MvcResult uploaded = mockMvc.perform(multipart("/api/admin/files/images")
                        .file(new MockMultipartFile("file", "cover.png", "image/png", TINY_PNG))
                        .header(HttpHeaders.AUTHORIZATION, bearer(superAdmin.token())))
                .andExpect(status().isOk())
                .andReturn();
        String uploadedBody = uploaded.getResponse().getContentAsString(StandardCharsets.UTF_8);
        long fileId = ((Number) JsonPath.read(uploadedBody, "$.id")).longValue();
        String key = JsonPath.read(uploadedBody, "$.key");

        mockMvc.perform(delete("/api/admin/files/{id}", fileId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(superAdmin.token())))
                .andExpect(status().isNoContent());

        Long softDeleted = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM file_object WHERE id = ? AND status = 0 AND deleted_at IS NOT NULL",
                Long.class, fileId);
        assertThat(softDeleted).isEqualTo(1);
        assertThat(storage.exists("sundry-public", key)).isTrue();
    }

    @Test
    void imageUsedAsProductCoverCannotBeDeleted() throws Exception {
        Session superAdmin = register(SUPER_PHONE);
        MvcResult uploaded = mockMvc.perform(multipart("/api/admin/files/images")
                        .file(new MockMultipartFile("file", "cover.png", "image/png", TINY_PNG))
                        .header(HttpHeaders.AUTHORIZATION, bearer(superAdmin.token())))
                .andExpect(status().isOk())
                .andReturn();
        String uploadedBody = uploaded.getResponse().getContentAsString(StandardCharsets.UTF_8);
        long fileId = ((Number) JsonPath.read(uploadedBody, "$.id")).longValue();
        Long categoryId = jdbcTemplate.queryForObject(
                "SELECT id FROM category WHERE name = '软件'", Long.class);

        mockMvc.perform(post("/api/admin/products")
                        .header(HttpHeaders.AUTHORIZATION, bearer(superAdmin.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"封面商品","description":"x","price":1.00,
                                 "categoryId":%d,"type":"PHYSICAL","coverFileId":%d}
                                """.formatted(categoryId, fileId)))
                .andExpect(status().isCreated());

        mockMvc.perform(delete("/api/admin/files/{id}", fileId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(superAdmin.token())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FILE_IN_USE"));
    }

    @Test
    void normalUserCannotDeleteFile() throws Exception {
        Session user = register(USER_PHONE);

        mockMvc.perform(delete("/api/admin/files/{id}", 1)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanReuseSoftDeletedImage() throws Exception {
        Session superAdmin = register(SUPER_PHONE);
        MvcResult uploaded = mockMvc.perform(multipart("/api/admin/files/images")
                        .file(new MockMultipartFile("file", "cover.png", "image/png", TINY_PNG))
                        .header(HttpHeaders.AUTHORIZATION, bearer(superAdmin.token())))
                .andExpect(status().isOk())
                .andReturn();
        long fileId = ((Number) JsonPath.read(
                uploaded.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.id")).longValue();

        mockMvc.perform(delete("/api/admin/files/{id}", fileId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(superAdmin.token())))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/admin/files/images/reusable")
                        .header(HttpHeaders.AUTHORIZATION, bearer(superAdmin.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(fileId))
                .andExpect(jsonPath("$[0].status").value(0))
                .andExpect(jsonPath("$[0].url").isNotEmpty());

        mockMvc.perform(post("/api/admin/files/images/{id}/reuse", fileId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(superAdmin.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(fileId));

        Integer status = jdbcTemplate.queryForObject(
                "SELECT status FROM file_object WHERE id = ?", Integer.class, fileId);
        assertThat(status).isEqualTo(1);
    }

    @Test
    void normalUserCannotReuseImages() throws Exception {
        Session user = register(USER_PHONE);

        mockMvc.perform(get("/api/admin/files/images/reusable")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isForbidden());
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
        assertThat(code).as("验证码应已发送").isNotNull();

        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phone":"%s","code":"%s","password":"%s"}
                                """.formatted(phone, code, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
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
