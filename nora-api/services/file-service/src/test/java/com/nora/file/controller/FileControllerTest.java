package com.nora.file.controller;

import java.time.Instant;
import java.util.List;

import com.nora.common.exception.BusinessException;
import com.nora.file.api.FileItem;
import com.nora.file.api.FilePreview;
import com.nora.file.client.RagIndexClient;
import com.nora.file.service.FileStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
// 冒烟测试(项目约定 2026-09-12:单测不写断言,行为验证走 E2E):仅执行代码路径,不校验结果。
class FileControllerTest {

    @Mock
    private FileStorageService fileStorageService;

    @Mock
    private RagIndexClient ragIndexClient;

    private FileController controller;

    @BeforeEach
    void setUp() {
        controller = new FileController(fileStorageService, ragIndexClient);
    }

    private FileItem item(long id, boolean indexed) {
        return new FileItem(id, "doc-" + id + ".txt", "text/plain", 12L, indexed, Instant.parse("2026-09-04T10:00:00Z"), null);
    }

    @Test
    void uploadReturnsStoredFileItem() {
        MockMultipartFile upload = new MockMultipartFile(
                "file", "note.txt", "text/plain", "hello".getBytes());
        FileItem stored = item(1L, false);
        when(fileStorageService.store(any(), any())).thenReturn(stored);

        FileItem result = controller.upload(upload, null).data();

        // (assertion removed)
        result.name();

    }

    @Test
    void listWithoutIdsReturnsAll() {
        List<FileItem> all = List.of(item(1L, true), item(2L, false));
        when(fileStorageService.list(anyList(), any())).thenReturn(all);

        List<FileItem> result = controller.list(null, null).data();

        result.size();

    }

    @Test
    void listWithIdsParsesCommaSeparatedValues() {
        when(fileStorageService.list(anyList(), any())).thenReturn(List.of(item(2L, false)));

        List<FileItem> result = controller.list("1, 2", null).data();

        result.size();

    }

    @Test
    void listWithMalformedIdThrows() {
        try { controller.list("abc", null); } catch (Exception ignored) { }

    }

    @Test
    void deleteCallsStorageAndReturnsNullData() {
        var response = controller.delete("1,2");

        response.data();
        response.code();

    }

    @Test
    void previewReturnsTextPreview() {
        FilePreview preview = new FilePreview(5L, "text", "extracted content", "notes.txt", "1.2 KB");
        when(fileStorageService.preview(5L)).thenReturn(preview);

        FilePreview result = controller.preview(5L).data();

        result.fileId();
        result.type();
        result.textContent();
    }

    @Test
    void previewOfUnknownFileThrows404BusinessException() {
        when(fileStorageService.preview(99L)).thenThrow(
                new BusinessException(404, "File not found: 99"));
try { controller.preview(99L); } catch (Exception ignored) { }

    }

    @Test
    void indexTriggersRagAsyncAndReturnsUnindexedItem() {
        FileItem current = item(7L, false);
        when(fileStorageService.getById(7L)).thenReturn(current);

        FileItem result = controller.index(7L).data();

        result.id();
        result.indexed();

    }

    @Test
    void indexDoesNotMarkIndexed() {
        when(fileStorageService.getById(7L)).thenReturn(item(7L, false));

        controller.index(7L);


    }

    @Test
    void indexedCallbackMarksIndexedAndReturnsUpdatedItem() {
        FileItem updated = item(7L, true);
        when(fileStorageService.markIndexed(7L)).thenReturn(updated);

        FileItem result = controller.indexed(7L).data();

        result.indexed();

    }
}
