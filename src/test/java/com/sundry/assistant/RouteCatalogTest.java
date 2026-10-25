package com.sundry.assistant;

import com.sundry.user.Role;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RouteCatalogTest {

    private final RouteCatalog catalog = new RouteCatalog();

    @Test
    void matchesCommonNavigationPhrases() {
        assertThat(catalog.match("我的订单在哪", Role.USER).name()).isEqualTo("orders");
        assertThat(catalog.match("打开购物车", Role.USER).name()).isEqualTo("cart");
        assertThat(catalog.match("首页推荐", Role.USER).name()).isEqualTo("feed");
        assertThat(catalog.match("收货地址", Role.USER).name()).isEqualTo("addresses");
    }

    @Test
    void explanationQuestionsDoNotNavigate() {
        assertThat(catalog.match("订单怎么改地址", Role.USER)).isNull();
        assertThat(catalog.match("推荐怎么算的", Role.USER)).isNull();
        // 有明确导航动词时仍可跳转
        assertThat(catalog.match("购物车怎么去", Role.USER).name()).isEqualTo("cart");
    }

    @Test
    void adminRoutesAreHiddenFromUsers() {
        assertThat(catalog.match("秒杀活动后台", Role.USER)).isNull();
        assertThat(catalog.match("秒杀活动后台", Role.ADMIN).name()).isEqualTo("admin-seckill");
        assertThat(catalog.match("全部订单", Role.SUPER_ADMIN).name()).isEqualTo("admin-orders");
    }

    @Test
    void longerSynonymWins() {
        assertThat(catalog.match("后台订单", Role.ADMIN).name()).isEqualTo("admin-orders");
        assertThat(catalog.match("我的订单", Role.ADMIN).name()).isEqualTo("orders");
    }
}
