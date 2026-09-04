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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
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
        return new FileItem(id, "doc-" + id + ".txt", "text/plain", 12L, indexed, Instant.parse("2026-09-04T10:00:00Z"));
    }

    @Test
    void uploadReturnsStoredFileItem() {
        MockMultipartFile upload = new MockMultipartFile(
                "file", "note.txt", "text/plain", "hello".getBytes());
        FileItem stored = item(1L, false);
        when(fileStorageService.store(any())).thenReturn(stored);

        FileItem result = controller.upload(upload).data();

        assertEquals(stored, result);
        assertEquals("doc-1.txt", result.name());
        verify(fileStorageService).store(upload);
    }

    @Test
    void listWithoutIdsReturnsAll() {
        List<FileItem> all = List.of(item(1L, true), item(2L, false));
        when(fileStorageService.list(anyList())).thenReturn(all);

        List<FileItem> result = controller.list(null).data();

        assertEquals(2, result.size());
        verify(fileStorageService).list(argThat(List::isEmpty));
    }

    @Test
    void listWithIdsParsesCommaSeparatedValues() {
        when(fileStorageService.list(anyList())).thenReturn(List.of(item(2L, false)));

        List<FileItem> result = controller.list("1, 2").data();

        assertEquals(1, result.size());
        verify(fileStorageService).list(argThat(ids -> ids.size() == 2 && ids.contains(1L) && ids.contains(2L)));
    }

    @Test
    void listWithMalformedIdThrows() {
        NumberFormatException ex = assertThrows(NumberFormatException.class,
                () -> controller.list("abc"));

        assertTrue(ex.getMessage() != null);
    }

    @Test
    void deleteCallsStorageAndReturnsNullData() {
        var response = controller.delete("1,2");

        assertNull(response.data());
        assertEquals(0, response.code());
        verify(fileStorageService).delete(argThat(ids -> ids.size() == 2 && ids.contains(1L) && ids.contains(2L)));
    }

    @Test
    void previewReturnsTextPreview() {
        FilePreview preview = new FilePreview(5L, "text", "extracted content", "notes.txt", "1.2 KB");
        when(fileStorageService.preview(5L)).thenReturn(preview);

        FilePreview result = controller.preview(5L).data();

        assertEquals(5L, result.fileId());
        assertEquals("text", result.type());
        assertEquals("extracted content", result.textContent());
    }

    @Test
    void previewOfUnknownFileThrows404BusinessException() {
        when(fileStorageService.preview(99L)).thenThrow(
                new BusinessException(404, "File not found: 99"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.preview(99L));

        assertEquals(404, ex.getCode());
    }

    @Test
    void indexTriggersRagAsyncAndReturnsUnindexedItem() {
        FileItem current = item(7L, false);
        when(fileStorageService.getById(7L)).thenReturn(current);

        FileItem result = controller.index(7L).data();

        assertEquals(7L, result.id());
        assertEquals(false, result.indexed());
        verify(ragIndexClient).triggerIndexAsync(current);
    }

    @Test
    void indexDoesNotMarkIndexed() {
        when(fileStorageService.getById(7L)).thenReturn(item(7L, false));

        controller.index(7L);

        verify(fileStorageService, never()).markIndexed(any());
    }

    @Test
    void indexedCallbackMarksIndexedAndReturnsUpdatedItem() {
        FileItem updated = item(7L, true);
        when(fileStorageService.markIndexed(7L)).thenReturn(updated);

        FileItem result = controller.indexed(7L).data();

        assertEquals(true, result.indexed());
        verify(fileStorageService).markIndexed(7L);
    }
}
