package com.sundry.file;

import com.jayway.jsonpath.JsonPath;
import com.sundry.auth.CapturingSmsSender;
import com.sundry.auth.sms.SmsScene;
import com.sundry.cleanup.FileCleanupJob;
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
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 定时清理：软删除过期、无商品引用、宽限期。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FileCleanupIntegrationTest {

    private static final String SUPER_PHONE = "13900000000";
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
    private FakeDigitalUploadGateway gateway;

    @Autowired
    private InMemoryObjectStorage imageStorage;

    @Autowired
    private FileCleanupJob cleanupJob;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM order_item");
        jdbcTemplate.update("DELETE FROM orders");
        jdbcTemplate.update("DELETE FROM product");
        jdbcTemplate.update("DELETE FROM file_object");
        jdbcTemplate.update("DELETE FROM app_user");
        clear(redis.keys("auth:*"));
        clear(redis.keys("upload:digital:*"));
        clear(redis.keys("job:*"));
        smsSender.clear();
        gateway.clear();
        imageStorage.clear();
    }

    @Test
    void softDeletedDigitalFileIsCleanedAfterGrace() throws Exception {
        String token = register(SUPER_PHONE);
        Uploaded uploaded = uploadDigital(token, 1024);
        softDelete(token, uploaded.id());
        ageDeletedAt(uploaded.id(), 8);

        cleanupJob.run();

        assertThat(fileExists(uploaded.id())).isFalse();
        assertThat(gateway.exists(uploaded.key())).isFalse();
    }

    @Test
    void recentSoftDeletedDigitalFileIsKept() throws Exception {
        String token = register(SUPER_PHONE);
        Uploaded uploaded = uploadDigital(token, 1024);
        softDelete(token, uploaded.id());
        ageDeletedAt(uploaded.id(), 1);

        cleanupJob.run();

        assertThat(fileExists(uploaded.id())).isTrue();
        assertThat(gateway.exists(uploaded.key())).isTrue();
    }

    @Test
    void orphanActiveDigitalFileIsCleanedAfterGrace() throws Exception {
        String token = register(SUPER_PHONE);
        Uploaded uploaded = uploadDigital(token, 1024);
        ageCreatedAt(uploaded.id(), 8);

        cleanupJob.run();

        assertThat(fileExists(uploaded.id())).isFalse();
        assertThat(gateway.exists(uploaded.key())).isFalse();
    }

    @Test
    void referencedDigitalFileIsKept() throws Exception {
        String token = register(SUPER_PHONE);
        Uploaded uploaded = uploadDigital(token, 1024);
        bindProduct(token, uploaded.id());
        ageCreatedAt(uploaded.id(), 8);

        cleanupJob.run();

        assertThat(fileExists(uploaded.id())).isTrue();
        assertThat(gateway.exists(uploaded.key())).isTrue();
    }

    @Test
    void digitalFileReferencedByOrderIsKept() throws Exception {
        String token = register(SUPER_PHONE);
        Long userId = jdbcTemplate.queryForObject(
                "SELECT id FROM app_user WHERE phone = ?", Long.class, SUPER_PHONE);
        Uploaded uploaded = uploadDigital(token, 1024);
        ageCreatedAt(uploaded.id(), 8);

        jdbcTemplate.update("""
                INSERT INTO orders (order_no, user_id, status, total_amount, has_physical)
                VALUES ('TEST-ORDER-1', ?, 'PAID', 0.00, 0)
                """, userId);
        Long orderId = jdbcTemplate.queryForObject(
                "SELECT id FROM orders WHERE order_no = 'TEST-ORDER-1'", Long.class);
        jdbcTemplate.update("""
                INSERT INTO order_item (order_id, product_id, product_name, product_type,
                                        price, quantity, digital_file_id)
                VALUES (?, 0, '数字商品', 'DIGITAL', 0.00, 1, ?)
                """, orderId, uploaded.id());

        cleanupJob.run();

        assertThat(fileExists(uploaded.id())).isTrue();
        assertThat(gateway.exists(uploaded.key())).isTrue();
    }

    @Test
    void orphanImageIsCleanedAfterGrace() throws Exception {
        String token = register(SUPER_PHONE);
        MvcResult uploaded = mockMvc.perform(multipart("/api/admin/files/images")
                        .file(new MockMultipartFile("file", "cover.png", "image/png", TINY_PNG))
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andReturn();
        String body = body(uploaded);
        long fileId = ((Number) JsonPath.read(body, "$.id")).longValue();
        String key = JsonPath.read(body, "$.key");
        ageCreatedAt(fileId, 2);

        cleanupJob.run();

        assertThat(fileExists(fileId)).isFalse();
        assertThat(imageStorage.exists("sundry-public", key)).isFalse();
    }

    private Uploaded uploadDigital(String token, long size) throws Exception {
        MvcResult init = mockMvc.perform(post("/api/admin/uploads/digital/init")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fileName":"project.zip","contentType":"application/zip","sizeBytes":%d}
                                """.formatted(size)))
                .andExpect(status().isOk())
                .andReturn();
        String initBody = body(init);
        String uploadId = JsonPath.read(initBody, "$.uploadId");
        String key = JsonPath.read(initBody, "$.key");

        MvcResult completed = mockMvc.perform(post("/api/admin/uploads/digital/complete")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"uploadId":"%s","key":"%s"}
                                """.formatted(uploadId, key)))
                .andExpect(status().isOk())
                .andReturn();
        String completedBody = body(completed);
        long id = ((Number) JsonPath.read(completedBody, "$.id")).longValue();
        return new Uploaded(id, key);
    }

    private void softDelete(String token, long fileId) throws Exception {
        mockMvc.perform(delete("/api/admin/uploads/digital/{id}", fileId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isNoContent());
    }

    private void bindProduct(String token, long fileId) throws Exception {
        Long categoryId = jdbcTemplate.queryForObject(
                "SELECT id FROM category WHERE name = '软件'", Long.class);
        mockMvc.perform(post("/api/admin/products")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"引用文件","description":"x","price":1.00,
                                 "categoryId":%d,"type":"DIGITAL","digitalFileId":%d}
                                """.formatted(categoryId, fileId)))
                .andExpect(status().isCreated());
    }

    private void ageDeletedAt(long fileId, int days) {
        jdbcTemplate.update("UPDATE file_object SET deleted_at = ? WHERE id = ?",
                Timestamp.valueOf(LocalDateTime.now().minusDays(days)), fileId);
    }

    private void ageCreatedAt(long fileId, int days) {
        jdbcTemplate.update("UPDATE file_object SET created_at = ? WHERE id = ?",
                Timestamp.valueOf(LocalDateTime.now().minusDays(days)), fileId);
    }

    private boolean fileExists(long fileId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM file_object WHERE id = ?", Long.class, fileId);
        return count != null && count > 0;
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

    private void clear(Set<String> keys) {
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private record Uploaded(long id, String key) {
    }
}
