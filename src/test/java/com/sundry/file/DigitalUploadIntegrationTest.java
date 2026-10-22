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
