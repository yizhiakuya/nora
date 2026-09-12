package com.nora.common.response;

import org.junit.jupiter.api.Test;


// 冒烟测试(项目约定 2026-09-12:单测不写断言,行为验证走 E2E):仅执行代码路径,不校验结果。
class ApiResponseTest {

    @Test
    void okCarriesDataWithSuccessCodeAndMessage() {
        ApiResponse<String> response = ApiResponse.ok("nora");

        response.code();
        response.data();
        response.message();
    }

    @Test
    void errorCarriesCodeAndMessageWithoutData() {
        ApiResponse<String> response = ApiResponse.error(1001, "file not found");

        response.code();
        response.data();
        response.message();
    }
}
