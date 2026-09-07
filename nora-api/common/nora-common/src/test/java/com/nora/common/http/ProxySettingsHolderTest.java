package com.nora.common.http;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProxySettingsHolderTest {

    @Test
    void defaultsToDisabled() {
        ProxySettingsHolder.set(null);
        assertFalse(ProxySettingsHolder.current().usable());
        assertNull(ProxySettingsHolder.addressFor("https://example.com"));
    }

    @Test
    void enabledProxyAppliesToExternalTarget() {
        ProxySettingsHolder.set(new ProxyProperties(true, "127.0.0.1", 7897));
        try {
            assertTrue(ProxySettingsHolder.current().usable());
            // getHostString 不做反向 DNS,按构造字面值返回
            assertEquals("127.0.0.1", ProxySettingsHolder.addressFor("https://example.com").getHostString());
            assertEquals(7897, ProxySettingsHolder.addressFor("https://example.com").getPort());
        } finally {
            ProxySettingsHolder.set(null);
        }
    }

    @Test
    void lanTargetBypassesProxy() {
        ProxySettingsHolder.set(new ProxyProperties(true, "127.0.0.1", 7897));
        try {
            assertNull(ProxySettingsHolder.addressFor("http://192.168.0.109:28765/v1"));
            assertNull(ProxySettingsHolder.addressFor("http://localhost:8080/actuator/health"));
        } finally {
            ProxySettingsHolder.set(null);
        }
    }
}
