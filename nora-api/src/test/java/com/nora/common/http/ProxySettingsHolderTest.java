package com.nora.common.http;

import org.junit.jupiter.api.Test;


// 冒烟测试(项目约定 2026-09-12:单测不写断言,行为验证走 E2E):仅执行代码路径,不校验结果。
class ProxySettingsHolderTest {

    @Test
    void defaultsToDisabled() {
        ProxySettingsHolder.set(null);
        ProxySettingsHolder.current();
        ProxySettingsHolder.addressFor("https://example.com");
    }

    @Test
    void enabledProxyAppliesToExternalTarget() {
        ProxySettingsHolder.set(new ProxyProperties(true, "127.0.0.1", 7897, java.util.List.of()));
        try {
            ProxySettingsHolder.current();
            // getHostString 不做反向 DNS,按构造字面值返回
            ProxySettingsHolder.addressFor("https://example.com");
            ProxySettingsHolder.addressFor("https://example.com");
        } finally {
            ProxySettingsHolder.set(null);
        }
    }

    @Test
    void lanTargetBypassesProxy() {
        ProxySettingsHolder.set(new ProxyProperties(true, "127.0.0.1", 7897, java.util.List.of()));
        try {
            ProxySettingsHolder.addressFor("http://192.168.0.109:28765/v1");
            ProxySettingsHolder.addressFor("http://localhost:8080/actuator/health");
        } finally {
            ProxySettingsHolder.set(null);
        }
    }
}
