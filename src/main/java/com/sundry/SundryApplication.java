package com.sundry;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// @SpringBootApplication 是 @SpringBootConfiguration + @EnableAutoConfiguration + @ComponentScan 的组合：
// 启动时扫描 com.sundry 及子包、读取 application.yml，并按依赖自动装配
@SpringBootApplication
public class SundryApplication {

    public static void main(String[] args) {
        SpringApplication.run(SundryApplication.class, args);
    }
}
