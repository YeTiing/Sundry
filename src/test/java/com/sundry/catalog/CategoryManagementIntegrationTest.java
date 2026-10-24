package com.sundry.catalog;

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
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 分类管理：增删改、排序、停用、重名和引用保护。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CategoryManagementIntegrationTest {

    private static final String ADMIN_PHONE = "13900000000";
    private static final String USER_PHONE = "13800138001";
    private static final String PASSWORD = "secret123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private CapturingSmsSender smsSender;

    @Autowired
    private ProductCacheService productCacheService;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM seckill_activity");
        jdbcTemplate.update("DELETE FROM order_refund");
        jdbcTemplate.update("DELETE FROM order_item");
        jdbcTemplate.update("DELETE FROM orders");
        jdbcTemplate.update("DELETE FROM cart_item");
        jdbcTemplate.update("DELETE FROM user_address");
        jdbcTemplate.update("DELETE FROM product");
        jdbcTemplate.update("DELETE FROM category WHERE name NOT IN ('秒杀券', '软件', '实物')");
        jdbcTemplate.update("DELETE FROM file_object");
        jdbcTemplate.update("DELETE FROM app_user");
        clear(redis.keys("auth:*"));
        clear(redis.keys("cache:*"));
        productCacheService.clearLocalCaches();
        smsSender.clear();
    }

    @Test
    void adminCanCreateAndPublicListSeesIt() throws Exception {
        Session admin = register(ADMIN_PHONE);

        // 先读一次公开分类，让缓存生效
        mockMvc.perform(get("/api/categories")).andExpect(status().isOk());

        mockMvc.perform(post("/api/admin/categories")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"测试分类A","sortOrder":5,"status":1}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("测试分类A"))
                .andExpect(jsonPath("$.sortOrder").value(5))
                .andExpect(jsonPath("$.status").value(1))
                .andExpect(jsonPath("$.productCount").value(0));

        mockMvc.perform(get("/api/admin/categories")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == '测试分类A')]").isNotEmpty());

        // 缓存已失效，公开列表立即能看到
        mockMvc.perform(get("/api/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("测试分类A"));
    }

    @Test
    void disableHidesFromPublicButKeepsAdminEntry() throws Exception {
        Session admin = register(ADMIN_PHONE);
        long id = createCategory(admin.token(), "测试分类B", 50, 1);

        mockMvc.perform(get("/api/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == '测试分类B')]").isNotEmpty());

        mockMvc.perform(put("/api/admin/categories/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"测试分类B-改名","sortOrder":50,"status":0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("测试分类B-改名"))
                .andExpect(jsonPath("$.status").value(0));

        mockMvc.perform(get("/api/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == '测试分类B-改名')]").isEmpty());

        mockMvc.perform(get("/api/admin/categories")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == '测试分类B-改名')]").isNotEmpty());
    }

    @Test
    void duplicateNameIsRejected() throws Exception {
        Session admin = register(ADMIN_PHONE);
        long first = createCategory(admin.token(), "测试分类C", 50, 1);
        long second = createCategory(admin.token(), "测试分类D", 51, 1);

        mockMvc.perform(post("/api/admin/categories")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"测试分类C"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATEGORY_NAME_DUPLICATED"));

        mockMvc.perform(put("/api/admin/categories/{id}", second)
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"测试分类C"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATEGORY_NAME_DUPLICATED"));

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void deleteOnlyAllowedWhenNoProducts() throws Exception {
        Session admin = register(ADMIN_PHONE);
        long emptyId = createCategory(admin.token(), "测试分类E", 60, 1);

        mockMvc.perform(delete("/api/admin/categories/{id}", emptyId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token())))
                .andExpect(status().isNoContent());

        long usedId = createCategory(admin.token(), "测试分类F", 61, 1);
        createProduct(admin.token(), usedId, "分类引用商品");

        mockMvc.perform(delete("/api/admin/categories/{id}", usedId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATEGORY_IN_USE"));

        mockMvc.perform(get("/api/admin/categories")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == %d)]".formatted(usedId)).isNotEmpty());
    }

    @Test
    void normalUserCannotManageCategories() throws Exception {
        Session user = register(USER_PHONE);

        mockMvc.perform(get("/api/admin/categories")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/admin/categories")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"越权分类"}
                                """))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/categories"))
                .andExpect(status().isOk());
    }

    private long createCategory(String token, String name, int sortOrder, int status) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/admin/categories")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","sortOrder":%d,"status":%d}
                                """.formatted(name, sortOrder, status)))
                .andExpect(status().isCreated())
                .andReturn();
        return ((Number) JsonPath.read(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.id")).longValue();
    }

    private long createProduct(String token, long categoryId, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/admin/products")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","description":"测试","price":10.00,"stock":5,
                                 "categoryId":%d,"type":"PHYSICAL","status":"ON_SALE"}
                                """.formatted(name, categoryId)))
                .andExpect(status().isCreated())
                .andReturn();
        return ((Number) JsonPath.read(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.id")).longValue();
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
        return new Session(JsonPath.read(body, "$.accessToken"));
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private void clear(Set<String> keys) {
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    private record Session(String token) {
    }
}
