package com.sundry.file;

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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 数字文件分片上传与商品绑定。S3 使用内存替身，流程和权限是真实的。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DigitalUploadIntegrationTest {

    private static final String SUPER_PHONE = "13900000000";
    private static final String USER_PHONE = "13800138001";
    private static final String PASSWORD = "secret123";
    private static final long FILE_SIZE = 33_554_432L; // 32MB，按 16MB 切成 2 片

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private CapturingSmsSender smsSender;

    @Autowired
    private FakeDigitalUploadGateway gateway;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM order_item");
        jdbcTemplate.update("DELETE FROM orders");
        jdbcTemplate.update("DELETE FROM seckill_activity");
        jdbcTemplate.update("DELETE FROM product");
        jdbcTemplate.update("DELETE FROM file_object");
        jdbcTemplate.update("DELETE FROM app_user");
        Set<String> authKeys = redis.keys("auth:*");
        if (authKeys != null && !authKeys.isEmpty()) {
            redis.delete(authKeys);
        }
        Set<String> uploadKeys = redis.keys("upload:digital:*");
        if (uploadKeys != null && !uploadKeys.isEmpty()) {
            redis.delete(uploadKeys);
        }
        smsSender.clear();
        gateway.clear();
    }

    @Test
    void adminCanInitAndCompleteMultipartUpload() throws Exception {
        String token = register(SUPER_PHONE);

        MvcResult init = mockMvc.perform(post("/api/admin/uploads/digital/init")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fileName":"project.zip","contentType":"application/zip","sizeBytes":%d}
                                """.formatted(FILE_SIZE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partSize").value(16_777_216))
                .andExpect(jsonPath("$.partCount").value(2))
                .andExpect(jsonPath("$.parts.length()").value(2))
                .andReturn();

        String initBody = body(init);
        String uploadId = JsonPath.read(initBody, "$.uploadId");
        String key = JsonPath.read(initBody, "$.key");
        assertThat(key).startsWith("digital/").endsWith(".zip");

        MvcResult completed = mockMvc.perform(post("/api/admin/uploads/digital/complete")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"uploadId":"%s","key":"%s"}
                                """.formatted(uploadId, key)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.originalName").value("project.zip"))
                .andExpect(jsonPath("$.sizeBytes").value(FILE_SIZE))
                .andReturn();

        long fileId = ((Number) JsonPath.read(body(completed), "$.id")).longValue();
        assertThat(gateway.exists(key)).isTrue();

        Long categoryId = jdbcTemplate.queryForObject(
                "SELECT id FROM category WHERE name = '软件'", Long.class);
        MvcResult created = mockMvc.perform(post("/api/admin/products")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"源码包","description":"数字商品","price":199.00,
                                 "categoryId":%d,"type":"DIGITAL","digitalFileId":%d,
                                 "status":"ON_SALE"}
                                """.formatted(categoryId, fileId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("DIGITAL"))
                .andExpect(jsonPath("$.digitalFileId").value(fileId))
                .andReturn();
        long productId = ((Number) JsonPath.read(body(created), "$.id")).longValue();

        mockMvc.perform(get("/api/products/{id}", productId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("DIGITAL"))
                .andExpect(jsonPath("$.digitalFileId").value(fileId));
    }

    @Test
    void adminCanDownloadDigitalProductFile() throws Exception {
        String admin = register(SUPER_PHONE);
        String user = register(USER_PHONE);

        MvcResult init = mockMvc.perform(post("/api/admin/uploads/digital/init")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fileName":"admin-download.zip","contentType":"application/zip","sizeBytes":%d}
                                """.formatted(FILE_SIZE)))
                .andExpect(status().isOk())
                .andReturn();
        String initBody = body(init);
        MvcResult completed = mockMvc.perform(post("/api/admin/uploads/digital/complete")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"uploadId":"%s","key":"%s"}
                                """.formatted(JsonPath.read(initBody, "$.uploadId"),
                                JsonPath.read(initBody, "$.key"))))
                .andExpect(status().isOk())
                .andReturn();
        long fileId = ((Number) JsonPath.read(body(completed), "$.id")).longValue();

        Long softwareCategory = jdbcTemplate.queryForObject(
                "SELECT id FROM category WHERE name = '软件'", Long.class);
        MvcResult created = mockMvc.perform(post("/api/admin/products")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"管理员下载测试","description":"数字商品","price":99.00,
                                 "categoryId":%d,"type":"DIGITAL","digitalFileId":%d,
                                 "status":"ON_SALE"}
                                """.formatted(softwareCategory, fileId)))
                .andExpect(status().isCreated())
                .andReturn();
        long productId = ((Number) JsonPath.read(body(created), "$.id")).longValue();

        mockMvc.perform(get("/api/admin/products/{id}/download", productId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value(org.hamcrest.Matchers.startsWith(
                        "http://test-minio/download/")))
                .andExpect(jsonPath("$.expiresInSeconds").value(600));

        mockMvc.perform(get("/api/admin/products/{id}/download", productId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isForbidden());

        Long physicalCategory = jdbcTemplate.queryForObject(
                "SELECT id FROM category WHERE name = '实物'", Long.class);
        MvcResult physical = mockMvc.perform(post("/api/admin/products")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"实物不可下载","description":"测试","price":10.00,"stock":5,
                                 "categoryId":%d,"type":"PHYSICAL","status":"ON_SALE"}
                                """.formatted(physicalCategory)))
                .andExpect(status().isCreated())
                .andReturn();
        long physicalId = ((Number) JsonPath.read(body(physical), "$.id")).longValue();

        mockMvc.perform(get("/api/admin/products/{id}/download", physicalId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PRODUCT_DOWNLOAD_NOT_SUPPORTED"));
    }

    @Test
    void oversizedDigitalFileIsRejected() throws Exception {
        String token = register(SUPER_PHONE);

        mockMvc.perform(post("/api/admin/uploads/digital/init")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fileName":"huge.zip","contentType":"application/zip","sizeBytes":2147483649}
                                """))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("DIGITAL_FILE_TOO_LARGE"));
    }

    @Test
    void normalUserCannotUploadDigitalFile() throws Exception {
        String token = register(USER_PHONE);

        mockMvc.perform(post("/api/admin/uploads/digital/init")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fileName":"project.zip","contentType":"application/zip","sizeBytes":1024}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void completeWithoutInitIsRejected() throws Exception {
        String token = register(SUPER_PHONE);

        mockMvc.perform(post("/api/admin/uploads/digital/complete")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"uploadId":"missing","key":"digital/x.zip"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UPLOAD_SESSION_NOT_FOUND"));
    }

    @Test
    void abortInvalidatesSession() throws Exception {
        String token = register(SUPER_PHONE);
        String[] init = initUpload(token, 1024);

        mockMvc.perform(post("/api/admin/uploads/digital/abort")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"uploadId":"%s","key":"%s"}
                                """.formatted(init[0], init[1])))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/admin/uploads/digital/complete")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"uploadId":"%s","key":"%s"}
                                """.formatted(init[0], init[1])))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UPLOAD_SESSION_NOT_FOUND"));
    }

    @Test
    void digitalProductRequiresDigitalFile() throws Exception {
        String token = register(SUPER_PHONE);
        Long categoryId = jdbcTemplate.queryForObject(
                "SELECT id FROM category WHERE name = '软件'", Long.class);

        mockMvc.perform(post("/api/admin/products")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"缺文件","description":"x","price":1.00,
                                 "categoryId":%d,"type":"DIGITAL"}
                                """.formatted(categoryId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DIGITAL_FILE_REQUIRED"));
    }

    @Test
    void physicalProductCannotBindDigitalFile() throws Exception {
        String token = register(SUPER_PHONE);
        String[] init = initUpload(token, 1024);
        MvcResult completed = completeUpload(token, init[0], init[1]);
        long fileId = ((Number) JsonPath.read(body(completed), "$.id")).longValue();
        Long categoryId = jdbcTemplate.queryForObject(
                "SELECT id FROM category WHERE name = '实物'", Long.class);

        mockMvc.perform(post("/api/admin/products")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"实物绑定文件","description":"x","price":1.00,
                                 "categoryId":%d,"type":"PHYSICAL","digitalFileId":%d}
                                """.formatted(categoryId, fileId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DIGITAL_FILE_INVALID"));
    }

    @Test
    void adminSoftDeletesUnusedDigitalFile() throws Exception {
        String token = register(SUPER_PHONE);
        String[] init = initUpload(token, 1024);
        MvcResult completed = completeUpload(token, init[0], init[1]);
        String completedBody = body(completed);
        long fileId = ((Number) JsonPath.read(completedBody, "$.id")).longValue();
        String key = JsonPath.read(completedBody, "$.key");

        mockMvc.perform(delete("/api/admin/uploads/digital/{id}", fileId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isNoContent());

        // 软删除：对象和记录都保留，7 天内可复用
        assertThat(gateway.exists(key)).isTrue();
        Long softDeleted = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM file_object WHERE id = ? AND status = 0 AND deleted_at IS NOT NULL",
                Long.class, fileId);
        assertThat(softDeleted).isEqualTo(1);
    }

    @Test
    void completeIsIdempotent() throws Exception {
        String token = register(SUPER_PHONE);
        String[] init = initUpload(token, 1024);

        long firstId = ((Number) JsonPath.read(body(completeUpload(token, init[0], init[1])), "$.id")).longValue();
        long secondId = ((Number) JsonPath.read(body(completeUpload(token, init[0], init[1])), "$.id")).longValue();

        assertThat(secondId).isEqualTo(firstId);
        Long rows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM file_object", Long.class);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void softDeletedFileIsRestoredWhenUploadedAgain() throws Exception {
        String token = register(SUPER_PHONE);
        String[] first = initUpload(token, 1024);
        MvcResult firstComplete = completeUpload(token, first[0], first[1]);
        String firstBody = body(firstComplete);
        long firstId = ((Number) JsonPath.read(firstBody, "$.id")).longValue();
        String firstKey = JsonPath.read(firstBody, "$.key");

        mockMvc.perform(delete("/api/admin/uploads/digital/{id}", firstId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isNoContent());

        String[] second = initUpload(token, 1024);
        MvcResult secondComplete = completeUpload(token, second[0], second[1]);
        String secondBody = body(secondComplete);
        long secondId = ((Number) JsonPath.read(secondBody, "$.id")).longValue();
        String secondKey = JsonPath.read(secondBody, "$.key");

        // 同大小同 ETag：复用旧记录并恢复，新对象被删掉
        assertThat(secondId).isEqualTo(firstId);
        assertThat(secondKey).isEqualTo(firstKey);       // 响应返回的是被复用的旧 key
        assertThat(gateway.exists(firstKey)).isTrue();
        assertThat(gateway.exists(second[1])).isFalse();  // 第二次上传的新对象已删除

        Long rows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM file_object", Long.class);
        assertThat(rows).isEqualTo(1);
        Integer status = jdbcTemplate.queryForObject(
                "SELECT status FROM file_object WHERE id = ?", Integer.class, firstId);
        assertThat(status).isEqualTo(1);
    }

    @Test
    void reusableListIncludesSoftDeletedFileAndCanRestore() throws Exception {
        String token = register(SUPER_PHONE);
        String[] init = initUpload(token, 1024);
        MvcResult completed = completeUpload(token, init[0], init[1]);
        long fileId = ((Number) JsonPath.read(body(completed), "$.id")).longValue();

        mockMvc.perform(delete("/api/admin/uploads/digital/{id}", fileId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/admin/uploads/digital/reusable")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(fileId))
                .andExpect(jsonPath("$[0].status").value(0));

        mockMvc.perform(post("/api/admin/uploads/digital/{id}/reuse", fileId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(fileId));

        Integer status = jdbcTemplate.queryForObject(
                "SELECT status FROM file_object WHERE id = ?", Integer.class, fileId);
        assertThat(status).isEqualTo(1);
    }

    @Test
    void expiredSoftDeletedFileIsNotListed() throws Exception {
        String token = register(SUPER_PHONE);
        String[] init = initUpload(token, 1024);
        MvcResult completed = completeUpload(token, init[0], init[1]);
        long fileId = ((Number) JsonPath.read(body(completed), "$.id")).longValue();

        mockMvc.perform(delete("/api/admin/uploads/digital/{id}", fileId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isNoContent());
        jdbcTemplate.update("UPDATE file_object SET deleted_at = ? WHERE id = ?",
                java.sql.Timestamp.valueOf(java.time.LocalDateTime.now().minusDays(8)), fileId);

        mockMvc.perform(get("/api/admin/uploads/digital/reusable")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void normalUserCannotListReusableFiles() throws Exception {
        String token = register(USER_PHONE);

        mockMvc.perform(get("/api/admin/uploads/digital/reusable")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isForbidden());
    }

    @Test
    void cannotDeleteFileUsedByProduct() throws Exception {
        String token = register(SUPER_PHONE);
        String[] init = initUpload(token, 1024);
        MvcResult completed = completeUpload(token, init[0], init[1]);
        long fileId = ((Number) JsonPath.read(body(completed), "$.id")).longValue();
        Long categoryId = jdbcTemplate.queryForObject(
                "SELECT id FROM category WHERE name = '软件'", Long.class);

        mockMvc.perform(post("/api/admin/products")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"已绑定商品","description":"x","price":1.00,
                                 "categoryId":%d,"type":"DIGITAL","digitalFileId":%d}
                                """.formatted(categoryId, fileId)))
                .andExpect(status().isCreated());

        mockMvc.perform(delete("/api/admin/uploads/digital/{id}", fileId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FILE_IN_USE"));
    }

    @Test
    void normalUserCannotDeleteDigitalFile() throws Exception {
        String token = register(USER_PHONE);

        mockMvc.perform(delete("/api/admin/uploads/digital/{id}", 1)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isForbidden());
    }

    private String register(String phone) throws Exception {
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
        return JsonPath.read(body(result), "$.accessToken");
    }

    private String[] initUpload(String token, long size) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/admin/uploads/digital/init")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fileName":"project.zip","contentType":"application/zip","sizeBytes":%d}
                                """.formatted(size)))
                .andExpect(status().isOk())
                .andReturn();
        String body = body(result);
        return new String[]{JsonPath.read(body, "$.uploadId"), JsonPath.read(body, "$.key")};
    }

    private MvcResult completeUpload(String token, String uploadId, String key) throws Exception {
        return mockMvc.perform(post("/api/admin/uploads/digital/complete")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"uploadId":"%s","key":"%s"}
                                """.formatted(uploadId, key)))
                .andExpect(status().isOk())
                .andReturn();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
