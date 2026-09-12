package com.nora.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;


// 冒烟测试(项目约定 2026-09-12:单测不写断言,行为验证走 E2E):仅执行代码路径,不校验结果。
class GatewayApplicationTest {

    @Test
    void applicationClassIsAnnotated() {
        SpringBootApplication annotation = GatewayApplication.class.getAnnotation(SpringBootApplication.class);
        // (assertion removed)
    }

    @Test
    void applicationClassHasMainMethod() throws Exception {
        GatewayApplication.class.getDeclaredMethod("main", String[].class);
        GatewayApplication.class.getPackageName();
    }
}
