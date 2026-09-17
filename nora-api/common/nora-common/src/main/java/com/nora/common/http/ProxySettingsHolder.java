package com.nora.common.http;

import java.net.InetSocketAddress;

/**
 * 出站代理设置的运行时可变持有器。
 *
 * <p>从静态配置(application.yml / env)起步,可经设置 UI 在运行时覆盖
 * (由属主服务持久化到 DB)。所有 HTTP 客户端构造点都在调用时读
 * {@link #current()},所以更新立即生效——无需重启。
 *
 * <p>这里的静态可变状态是刻意的:{@code ProxyProperties} 被多个服务/控制器
 * 注入,持有器让变更点最小化且保持线程安全。
 */
public final class ProxySettingsHolder {

    private static volatile ProxyProperties current = ProxyProperties.disabled();

    private ProxySettingsHolder() {
    }

    /** 下一次出站调用使用的值。绝不为 null。 */
    public static ProxyProperties current() {
        return current;
    }

    /** 替换生效设置(如启动时或 UI 保存时)。 */
    public static void set(ProxyProperties props) {
        current = props != null ? props : ProxyProperties.disabled();
    }

    /** 客户端构造点的便捷方法。 */
    public static InetSocketAddress addressFor(String targetUrl) {
        return ProxySupport.addressFor(current(), targetUrl);
    }
}
