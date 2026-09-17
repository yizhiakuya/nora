package com.nora.file;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * file-service 入口(端口 8081):文件上传、Tika 文本提取、预览,
 * 以及经 rag-service 的发后即忘索引。
 * 扫描整个 {@code com.nora} 树,让 nora-common 的
 * {@code GlobalExceptionHandler} 与本服务的 bean 一起注册。
 */
@SpringBootApplication(scanBasePackages = "com.nora")
@EnableAsync
public class FileApplication {

    public static void main(String[] args) {
        SpringApplication.run(FileApplication.class, args);
    }
}
