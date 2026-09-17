package com.nora.datasource.api;

import java.io.Serializable;

/**
 * 连通测试的结果,由 {@link DatasourceService#test(Long)} 返回。
 *
 * @param ok        连接能否建立并应答
 * @param message   人类可读细节;失败时携带原因
 * @param latencyMs 实测往返延迟(毫秒);{@code ok} 为 false 时 {@code null}
 */
public record ConnectionStatus(
        boolean ok,
        String message,
        Long latencyMs) implements Serializable {

    private static final long serialVersionUID = 1L;
}
