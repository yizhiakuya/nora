package com.nora.datasource.api;

import java.io.Serializable;

/**
 * Outcome of a connectivity test, returned by
 * {@link DatasourceService#test(Long)}.
 *
 * @param ok        whether the connection could be established and answered
 * @param message   human-readable detail; on failure carries the reason
 * @param latencyMs measured round-trip latency in milliseconds, {@code null} when {@code ok} is false
 */
public record ConnectionStatus(
        boolean ok,
        String message,
        Long latencyMs) implements Serializable {

    private static final long serialVersionUID = 1L;
}
