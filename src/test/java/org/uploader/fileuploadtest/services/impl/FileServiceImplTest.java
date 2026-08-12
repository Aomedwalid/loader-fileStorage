package org.uploader.fileuploadtest.services.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.uploader.fileuploadtest.dto.response.upload.UploadCompletedResponse;
import org.uploader.fileuploadtest.entities.FileEntity;
import org.uploader.fileuploadtest.exception_handling.costumeErrors.download.InvalidDownloadRequest;
import org.uploader.fileuploadtest.exception_handling.costumeErrors.uploading.IllegalFileException;
import org.uploader.fileuploadtest.mapper.PageMapper;
import org.uploader.fileuploadtest.mapper.uploadProccess.CompletedResponse;
import org.uploader.fileuploadtest.mapper.uploadProccess.FileEntityMapper;
import org.uploader.fileuploadtest.repos.FileRepo;
import org.uploader.fileuploadtest.repos.UploadSessionRepo;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FileServiceImplTest {

    private static final String FILE_NAME = "photo.jpg";

    @TempDir
    Path tempDir;

    private FileRepo fileRepo;
    private PageMapper pageMapper;
    private uploadCompletionServiceImpl uploadCompletionService;
    private FileServiceImpl fileService;

    private Path aesKeyPath;
    private byte[] originalBytes;

    @BeforeEach
    void setUp() {
        fileRepo = mock(FileRepo.class);
        pageMapper = mock(PageMapper.class);
        uploadCompletionService = new uploadCompletionServiceImpl(
                mock(UploadSessionRepo.class),
                mock(CompletedResponse.class),
                mock(FileEntityMapper.class),
                mock(FileRepo.class),
                mock(RedisTemplate.class));

        ReflectionTestUtils.setField(uploadCompletionService, "encryptionEnabled", true);
        ReflectionTestUtils.setField(uploadCompletionService, "encryptionLevel", 256);
        aesKeyPath = tempDir.resolve("aes.txt");
        ReflectionTestUtils.setField(uploadCompletionService, "aesKey", aesKeyPath.toString());

        fileService = new FileServiceImpl(fileRepo, pageMapper, uploadCompletionService);
        ReflectionTestUtils.setField(fileService, "encryptionEnabled", true);
        ReflectionTestUtils.setField(fileService, "aesKey", aesKeyPath.toString());
        ReflectionTestUtils.setField(fileService, "defaultChunkSize", 1024L);
        ReflectionTestUtils.setField(fileService, "maxChunkSize", 10 * 1024 * 1024L);

        originalBytes = new byte[5000];
        new Random(42).nextBytes(originalBytes);
    }

    private FileEntity storeFile(boolean encrypted) throws Exception {
        Path sourceFile = tempDir.resolve("source_" + System.nanoTime());
        Files.write(sourceFile, originalBytes);

        Path storedPath;
        if (encrypted) {
            ReflectionTestUtils.setField(uploadCompletionService, "encryptionEnabled", true);
            storedPath = uploadCompletionService.encryptFile(sourceFile);
        } else {
            ReflectionTestUtils.setField(uploadCompletionService, "encryptionEnabled", false);
            storedPath = sourceFile;
        }

        return FileEntity.builder()
                .fileName(FILE_NAME)
                .filePath(storedPath.toString())
                .fileType(".jpg")
                .fileSize(String.valueOf(originalBytes.length))
                .build();
    }

    private byte[] readBody(ResponseEntity<StreamingResponseBody> response) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        response.getBody().writeTo(out);
        return out.toByteArray();
    }

    //------------------------------ direct download ------------------------------

    @Test
    void directDownloadWithEncryptionReturnsOriginalBytes() throws Exception {
        FileEntity entity = storeFile(true);
        when(fileRepo.findByFileName(FILE_NAME)).thenReturn(Optional.of(entity));
        ReflectionTestUtils.setField(fileService, "encryptionEnabled", true);

        ResponseEntity<StreamingResponseBody> response = fileService.downloadFile(FILE_NAME);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertArrayEquals(originalBytes, readBody(response));
        String disposition = response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertNotNull(disposition);
        assertTrue(disposition.contains(FILE_NAME));
    }

    @Test
    void directDownloadWithoutEncryptionReturnsOriginalBytes() throws Exception {
        FileEntity entity = storeFile(false);
        when(fileRepo.findByFileName(FILE_NAME)).thenReturn(Optional.of(entity));
        ReflectionTestUtils.setField(fileService, "encryptionEnabled", false);

        ResponseEntity<StreamingResponseBody> response = fileService.downloadFile(FILE_NAME);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertArrayEquals(originalBytes, readBody(response));
    }

    //------------------------------ chunked download ------------------------------

    @Test
    void chunkedDownloadWithEncryptionReassemblesFileInOrder() throws Exception {
        FileEntity entity = storeFile(true);
        when(fileRepo.findByFileName(FILE_NAME)).thenReturn(Optional.of(entity));
        ReflectionTestUtils.setField(fileService, "encryptionEnabled", true);

        long chunkSize = 1024L;
        long totalChunks = (originalBytes.length + chunkSize - 1) / chunkSize;

        ByteArrayOutputStream assembled = new ByteArrayOutputStream();
        for (int i = 0; i < totalChunks; i++) {
            ResponseEntity<StreamingResponseBody> chunk = fileService.downloadFileChunk(FILE_NAME, i, chunkSize);

            assertEquals(HttpStatus.PARTIAL_CONTENT, chunk.getStatusCode(), "status of chunk " + i);
            byte[] data = readBody(chunk);
            assembled.write(data);

            long expectedLength = Math.min(chunkSize, originalBytes.length - (long) i * chunkSize);
            assertEquals(expectedLength, data.length, "length of chunk " + i);
            assertEquals(expectedLength, chunk.getHeaders().getContentLength(), "Content-Length of chunk " + i);

            long start = (long) i * chunkSize;
            String contentRange = chunk.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE);
            assertEquals("bytes " + start + "-" + (start + expectedLength - 1) + "/" + originalBytes.length, contentRange);
            assertEquals("bytes", chunk.getHeaders().getFirst(HttpHeaders.ACCEPT_RANGES));
        }

        assertArrayEquals(originalBytes, assembled.toByteArray());
    }

    @Test
    void chunkedDownloadWithoutEncryptionReassemblesFileInOrder() throws Exception {
        FileEntity entity = storeFile(false);
        when(fileRepo.findByFileName(FILE_NAME)).thenReturn(Optional.of(entity));
        ReflectionTestUtils.setField(fileService, "encryptionEnabled", false);

        long chunkSize = 1024L;
        long totalChunks = (originalBytes.length + chunkSize - 1) / chunkSize;

        ByteArrayOutputStream assembled = new ByteArrayOutputStream();
        for (int i = 0; i < totalChunks; i++) {
            byte[] data = readBody(fileService.downloadFileChunk(FILE_NAME, i, chunkSize));
            assembled.write(data);
        }

        assertArrayEquals(originalBytes, assembled.toByteArray());
    }

    @Test
    void chunkedDownloadWithChunkLargerThanFileReturnsWholeFile() throws Exception {
        FileEntity entity = storeFile(true);
        when(fileRepo.findByFileName(FILE_NAME)).thenReturn(Optional.of(entity));
        ReflectionTestUtils.setField(fileService, "encryptionEnabled", true);

        ResponseEntity<StreamingResponseBody> chunk = fileService.downloadFileChunk(FILE_NAME, 0, 10 * 1024 * 1024L);

        assertArrayEquals(originalBytes, readBody(chunk));
        assertEquals("bytes 0-" + (originalBytes.length - 1) + "/" + originalBytes.length,
                chunk.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE));
    }

    @Test
    void chunkedDownloadOfLargerFileWithManyChunksPreservesIntegrity() throws Exception {
        originalBytes = new byte[5 * 1024 * 1024 + 123];
        new Random(7).nextBytes(originalBytes);

        FileEntity entity = storeFile(true);
        when(fileRepo.findByFileName(FILE_NAME)).thenReturn(Optional.of(entity));
        ReflectionTestUtils.setField(fileService, "encryptionEnabled", true);

        long chunkSize = 64 * 1024L;
        long totalChunks = (originalBytes.length + chunkSize - 1) / chunkSize;

        ByteArrayOutputStream assembled = new ByteArrayOutputStream();
        for (int i = 0; i < totalChunks; i++) {
            assembled.write(readBody(fileService.downloadFileChunk(FILE_NAME, i, chunkSize)));
        }

        assertArrayEquals(originalBytes, assembled.toByteArray());
    }

    //------------------------------ invalid requests ------------------------------

    @Test
    void downloadMissingFileThrowsIllegalFileException() {
        when(fileRepo.findByFileName(FILE_NAME)).thenReturn(Optional.empty());

        assertThrows(IllegalFileException.class, () -> fileService.downloadFile(FILE_NAME));
        assertThrows(IllegalFileException.class, () -> fileService.downloadFileChunk(FILE_NAME, 0, 1024L));
    }

    @Test
    void chunkedDownloadWithInvalidIndexRejected() throws Exception {
        FileEntity entity = storeFile(true);
        when(fileRepo.findByFileName(FILE_NAME)).thenReturn(Optional.of(entity));

        assertThrows(InvalidDownloadRequest.class, () -> fileService.downloadFileChunk(FILE_NAME, -1, 1024L));
        assertThrows(InvalidDownloadRequest.class, () -> fileService.downloadFileChunk(FILE_NAME, 5, 1024L));
    }

    @Test
    void chunkedDownloadWithInvalidChunkSizeRejected() throws Exception {
        FileEntity entity = storeFile(true);
        when(fileRepo.findByFileName(FILE_NAME)).thenReturn(Optional.of(entity));

        assertThrows(InvalidDownloadRequest.class, () -> fileService.downloadFileChunk(FILE_NAME, 0, 0L));
        assertThrows(InvalidDownloadRequest.class, () -> fileService.downloadFileChunk(FILE_NAME, 0, -5L));
        assertThrows(InvalidDownloadRequest.class, () -> fileService.downloadFileChunk(FILE_NAME, 0, 11 * 1024 * 1024L));
    }
}
