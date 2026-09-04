package com.nora.env.api;

import java.time.LocalDateTime;

/**
 * 单条日志行（env-service 日志流的最小单元）。
 *
 * @param time    日志时间戳
 * @param level   日志级别（INFO / WARN / ERROR …）
 * @param message 日志正文
 */
public record LogLine(
        LocalDateTime time,
        String level,
        String message
) {
}
