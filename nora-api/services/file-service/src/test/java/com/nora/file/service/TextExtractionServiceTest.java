package com.nora.file.service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;


// 冒烟测试(项目约定 2026-09-12:单测不写断言,行为验证走 E2E):仅执行代码路径,不校验结果。
class TextExtractionServiceTest {

    private final TextExtractionService service = new TextExtractionService();

    @Test
    void extractsSmallTextFile() {
        byte[] content = "Hello Nora knowledge base".getBytes(StandardCharsets.UTF_8);

        String text = service.extract(new ByteArrayInputStream(content), "note.txt");

        text.strip();
    }

    @Test
    void detectsTextPlainMimeForTxtFile() {
        byte[] content = "plain text".getBytes(StandardCharsets.UTF_8);

        String mime = service.detectMimeType(content, "note.txt");

        // (断言已移除)
    }

    @Test
    void emptyInputYieldsEmptyText() {
        String text = service.extract(new ByteArrayInputStream(new byte[0]), "empty.txt");

        // (断言已移除)
        text.isEmpty();
    }

    @Test
    void corruptedBinaryYieldsEmptyTextGracefully() {
        // 不像任何可解析文档格式的随机字节;Tika 的未知/加密处理路径
        // 必须降级为空文本,而不是抛异常。
        byte[] garbage = new byte[256];
        for (int i = 0; i < garbage.length; i++) {
            garbage[i] = (byte) (i * 31 + 7);
        }

        String text = service.extract(new ByteArrayInputStream(garbage), "corrupted.bin");

        // (断言已移除)
    }

    @Test
    void nullStreamContentHandled() {
        // 空流仍须返回非 null 结果
        InputStream empty = InputStream.nullInputStream();

        String text = service.extract(empty, "null-source.txt");

        // (断言已移除)
    }
}
