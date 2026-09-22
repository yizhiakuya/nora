package com.nora.file.api;

/**
 * 上传文件的提取预览,由 file-service 产出(Apache Tika 文本提取)。
 *
 * <p><b>2026-09-22(知识库优化阶段 A)扩展诊断:</b>此前只有 textContent,
 * 解析失败与「文件没有文字」不可区分。现在携带:
 * <ul>
 *   <li>{@code extractStatus} — ok / empty / truncated / error;</li>
 *   <li>{@code extractWarning} — 非致命告警(truncated 的可读说明);</li>
 *   <li>{@code extractError} — 失败原因(error 的可读说明)。</li>
 * </ul>
 *
 * @param fileId         被预览文件 id
 * @param type           预览类型,如 {@code text}
 * @param textContent    提取的纯文本内容(文件无文本预览时为 {@code null})
 * @param name           原始文件名(让 rag-service 命名知识文档)
 * @param size           人类可读文件大小,如 {@code "1.5 MB"}
 * @param extractStatus  提取状态:ok/empty/truncated/error;旧数据为 null(按 ok 处理)
 * @param extractWarning 非致命告警;无则 null
 * @param extractError   失败原因;无则 null
 */
public record FilePreview(
        Long fileId,
        String type,
        String textContent,
        String name,
        String size,
        String extractStatus,
        String extractWarning,
        String extractError
) {

    /** 兼容构造(旧调用点/测试):无诊断字段。 */
    public FilePreview(Long fileId, String type, String textContent, String name, String size) {
        this(fileId, type, textContent, name, size, null, null, null);
    }
}
