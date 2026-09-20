package com.nora.file;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * file-service 入口(端口 8081):文件上传、Tika 文本提取、预览,
 * 以及经 rag-service 的发后即忘索引。
 * 扫描整个 {@code com.nora} 树,让 nora-common 的
 * {@code GlobalExceptionHandler} 与本服务的 bean 一起注册。
 *
 * <p>{@code @EnableScheduling}(2026-09-20):RagIndexClient 的待处理
 * 生命周期通知重试需要定时任务支持。
 */
@SpringBootApplication(scanBasePackages = "com.nora")
@EnableAsync
@EnableScheduling
public class FileApplication {

    public static void main(String[] args) {
        SpringApplication.run(FileApplication.class, args);
    }
}
