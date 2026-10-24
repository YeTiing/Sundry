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
 * 商品展示链路：草稿不可见、上架可见、下架隐藏、分类筛选、权限和分页。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CatalogFlowIntegrationTest {

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
    private ProductCacheService productCacheService;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM order_item");
        jdbcTemplate.update("DELETE FROM orders");
        jdbcTemplate.update("DELETE FROM seckill_activity");
        jdbcTemplate.update("DELETE FROM product");
        jdbcTemplate.update("DELETE FROM category WHERE name NOT IN ('秒杀券', '软件', '实物')");
        jdbcTemplate.update("DELETE FROM file_object");
        jdbcTemplate.update("DELETE FROM app_user");
        Set<String> keys = redis.keys("auth:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
        Set<String> cacheKeys = redis.keys("cache:*");
        if (cacheKeys != null && !cacheKeys.isEmpty()) {
            redis.delete(cacheKeys);
        }
        productCacheService.clearLocalCaches();
        smsSender.clear();
    }

    @Test
    void categoriesAreSeeded() throws Exception {
        mockMvc.perform(get("/api/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].name").value("秒杀券"))
                .andExpect(jsonPath("$[1].name").value("软件"))
                .andExpect(jsonPath("$[2].name").value("实物"));
    }

    @Test
    void draftProductIsHiddenUntilOnSale() throws Exception {
        Session admin = register(SUPER_PHONE);
        Long categoryId = categoryId("软件");

        MvcResult created = createProduct(admin.token(), "草稿商品", "99.90", categoryId, "DRAFT", null);
        long productId = ((Number) JsonPath.read(body(created), "$.id")).longValue();

        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));

        mockMvc.perform(get("/api/products/{id}", productId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));

        mockMvc.perform(patch("/api/admin/products/{id}/status", productId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"ON_SALE"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ON_SALE"));

        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].name").value("草稿商品"))
                .andExpect(jsonPath("$.items[0].price").value(99.90));

        mockMvc.perform(get("/api/products/{id}", productId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categoryName").value("软件"));

        mockMvc.perform(patch("/api/admin/products/{id}/status", productId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"OFF_SHELF"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void publicListSupportsCategoryFilterAndPagination() throws Exception {
        Session admin = register(SUPER_PHONE);
        Long softwareId = categoryId("软件");
        Long couponId = categoryId("秒杀券");

        createProduct(admin.token(), "软件一", "10.00", softwareId, "ON_SALE", null);
        createProduct(admin.token(), "软件二", "20.00", softwareId, "ON_SALE", null);
        createProduct(admin.token(), "软件三", "30.00", softwareId, "ON_SALE", null);
        createProduct(admin.token(), "秒杀券一", "1.00", couponId, "ON_SALE", null);

        mockMvc.perform(get("/api/products").param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(4))
                .andExpect(jsonPath("$.items.length()").value(2));

        mockMvc.perform(get("/api/products").param("categoryId", softwareId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items[0].categoryName").value("软件"));
    }

    @Test
    void normalUserCannotManageProducts() throws Exception {
        Session user = register(USER_PHONE);
        Long categoryId = categoryId("实物");

        mockMvc.perform(get("/api/admin/products")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/admin/products")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productJson("越权商品", "1.00", categoryId, null, null)))
                .andExpect(status().isForbidden());
    }

    @Test
    void invalidCategoryAndPriceAreRejected() throws Exception {
        Session admin = register(SUPER_PHONE);
        Long categoryId = categoryId("软件");

        mockMvc.perform(post("/api/admin/products")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productJson("无效分类", "1.00", 999999L, null, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CATEGORY_NOT_FOUND"));

        mockMvc.perform(post("/api/admin/products")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productJson("负价格", "-1.00", categoryId, null, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void coverImageUrlIsReturned() throws Exception {
        Session admin = register(SUPER_PHONE);
        Long categoryId = categoryId("软件");

        MvcResult uploaded = mockMvc.perform(multipart("/api/admin/files/images")
                        .file(new MockMultipartFile("file", "cover.png", "image/png", TINY_PNG))
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token())))
                .andExpect(status().isOk())
                .andReturn();
        String uploadedBody = body(uploaded);
        long fileId = ((Number) JsonPath.read(uploadedBody, "$.id")).longValue();
        String expectedUrl = JsonPath.read(uploadedBody, "$.url");

        mockMvc.perform(post("/api/admin/products")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productJson("带封面商品", "88.00", categoryId, "ON_SALE", fileId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.coverUrl").value(expectedUrl));
    }

    @Test
    void offShelfIsNotDelete() throws Exception {
        Session admin = register(SUPER_PHONE);
        Long categoryId = categoryId("软件");
        MvcResult created = createProduct(admin.token(), "下架商品", "10.00", categoryId, "ON_SALE", null);
        long productId = ((Number) JsonPath.read(body(created), "$.id")).longValue();

        mockMvc.perform(patch("/api/admin/products/{id}/status", productId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"OFF_SHELF"}
                                """))
                .andExpect(status().isOk());

        // 下架后前台不可见，后台仍可见，且没有删除标记
        mockMvc.perform(get("/api/products"))
                .andExpect(jsonPath("$.total").value(0));
        mockMvc.perform(get("/api/admin/products")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token())))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(productId));
        Long deleted = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM product WHERE id = ? AND deleted_at IS NOT NULL",
                Long.class, productId);
        assertThat(deleted).isZero();

        // 可以重新上架
        mockMvc.perform(patch("/api/admin/products/{id}/status", productId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"ON_SALE"}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/products"))
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void deleteIsSeparateFromOffShelfAndKeepsFileReference() throws Exception {
        Session admin = register(SUPER_PHONE);
        Long categoryId = categoryId("软件");

        MvcResult uploaded = mockMvc.perform(multipart("/api/admin/files/images")
                        .file(new MockMultipartFile("file", "cover.png", "image/png", TINY_PNG))
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token())))
                .andExpect(status().isOk())
                .andReturn();
        long fileId = ((Number) JsonPath.read(body(uploaded), "$.id")).longValue();

        MvcResult created = createProduct(admin.token(), "待删除商品", "10.00", categoryId, "ON_SALE", fileId);
        long productId = ((Number) JsonPath.read(body(created), "$.id")).longValue();

        mockMvc.perform(delete("/api/admin/products/{id}", productId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token())))
                .andExpect(status().isNoContent());

        String productStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM product WHERE id = ?", String.class, productId);
        Long deleted = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM product WHERE id = ? AND deleted_at IS NOT NULL",
                Long.class, productId);
        assertThat(productStatus).isEqualTo("OFF_SHELF");
        assertThat(deleted).isEqualTo(1);

        // 前后台都不再展示
        mockMvc.perform(get("/api/products"))
                .andExpect(jsonPath("$.total").value(0));
        mockMvc.perform(get("/api/admin/products")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token())))
                .andExpect(jsonPath("$.total").value(0));
        mockMvc.perform(get("/api/products/{id}", productId))
                .andExpect(status().isNotFound());

        // 文件仍被软删除商品引用，不能删
        mockMvc.perform(delete("/api/admin/files/{id}", fileId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FILE_IN_USE"));

        // 删除后不能再上架
        mockMvc.perform(patch("/api/admin/products/{id}/status", productId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"ON_SALE"}
                                """))
                .andExpect(status().isNotFound());

        // 回收站里能看到
        mockMvc.perform(get("/api/admin/products/deleted")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(productId));

        // 恢复后回到后台列表，状态保持 OFF_SHELF
        mockMvc.perform(post("/api/admin/products/{id}/restore", productId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OFF_SHELF"))
                .andExpect(jsonPath("$.deletedAt").doesNotExist());

        mockMvc.perform(get("/api/admin/products")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token())))
                .andExpect(jsonPath("$.total").value(1));
        mockMvc.perform(get("/api/admin/products/deleted")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token())))
                .andExpect(jsonPath("$.total").value(0));
        mockMvc.perform(get("/api/products"))
                .andExpect(jsonPath("$.total").value(0));

        // 恢复后可以重新上架
        mockMvc.perform(patch("/api/admin/products/{id}/status", productId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"ON_SALE"}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/products"))
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void normalUserCannotDeleteProduct() throws Exception {
        Session admin = register(SUPER_PHONE);
        Session user = register(USER_PHONE);
        Long categoryId = categoryId("实物");
        MvcResult created = createProduct(admin.token(), "普通用户不能删", "1.00", categoryId, "ON_SALE", null);
        long productId = ((Number) JsonPath.read(body(created), "$.id")).longValue();

        mockMvc.perform(delete("/api/admin/products/{id}", productId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.token())))
                .andExpect(status().isForbidden());
    }

    @Test
    void createProductIsIdempotentWithSameKey() throws Exception {
        Session admin = register(SUPER_PHONE);
        Long categoryId = categoryId("软件");
        String payload = productJson("幂等商品", "1.00", categoryId, "DRAFT", null);

        MvcResult first = mockMvc.perform(post("/api/admin/products")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token()))
                        .header("Idempotency-Key", "idem-create-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andReturn();
        long firstId = ((Number) JsonPath.read(body(first), "$.id")).longValue();

        MvcResult second = mockMvc.perform(post("/api/admin/products")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token()))
                        .header("Idempotency-Key", "idem-create-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andReturn();
        long secondId = ((Number) JsonPath.read(body(second), "$.id")).longValue();

        assertThat(secondId).isEqualTo(firstId);
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM product", Long.class);
        assertThat(count).isEqualTo(1);

        // 不同 key 代表新的创建意图
        MvcResult third = mockMvc.perform(post("/api/admin/products")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token()))
                        .header("Idempotency-Key", "idem-create-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andReturn();
        long thirdId = ((Number) JsonPath.read(body(third), "$.id")).longValue();
        assertThat(thirdId).isNotEqualTo(firstId);
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM product", Long.class);
        assertThat(total).isEqualTo(2);
    }

    @Test
    void idempotencyKeyTooLongIsRejected() throws Exception {
        Session admin = register(SUPER_PHONE);
        Long categoryId = categoryId("软件");

        mockMvc.perform(post("/api/admin/products")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.token()))
                        .header("Idempotency-Key", "x".repeat(65))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productJson("key 太长", "1.00", categoryId, "DRAFT", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
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
                .andReturn();

        String body = body(result);
        String token = JsonPath.read(body, "$.accessToken");
        Number id = JsonPath.read(body, "$.user.id");
        return new Session(token, id.longValue());
    }

    private MvcResult createProduct(String token, String name, String price, Long categoryId,
                                    String status, Long coverFileId) throws Exception {
        return mockMvc.perform(post("/api/admin/products")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productJson(name, price, categoryId, status, coverFileId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andReturn();
    }

    private String productJson(String name, String price, Long categoryId, String status, Long coverFileId) {
        String cover = coverFileId == null ? "null" : coverFileId.toString();
        String statusPart = status == null ? "" : ",\"status\":\"" + status + "\"";
        return """
                {"name":"%s","description":"测试商品描述","price":%s,"categoryId":%d,"coverFileId":%s%s}
                """.formatted(name, price, categoryId, cover, statusPart);
    }

    private Long categoryId(String name) {
        return jdbcTemplate.queryForObject("SELECT id FROM category WHERE name = ?", Long.class, name);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private record Session(String token, Long id) {
    }
}
