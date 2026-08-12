package org.uploader.fileuploadtest.services.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.uploader.fileuploadtest.dto.response.upload.UploadCompletedResponse;
import org.uploader.fileuploadtest.entities.FileEntity;
import org.uploader.fileuploadtest.entities.UploadSession;
import org.uploader.fileuploadtest.exception_handling.costumeErrors.directory.DirectoryException;
import org.uploader.fileuploadtest.mapper.uploadProccess.CompletedResponse;
import org.uploader.fileuploadtest.mapper.uploadProccess.FileEntityMapper;
import org.uploader.fileuploadtest.repos.FileRepo;
import org.uploader.fileuploadtest.repos.UploadSessionRepo;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UploadCompletionServiceImplTest {

    @TempDir
    Path tempDir;

    private UploadSessionRepo uploadSessionRepo;
    private FileRepo fileRepo;
    private RedisTemplate<String, String> redisTemplate;
    private FileEntityMapper fileEntityMapper;
    private CompletedResponse completedResponse;
    private uploadCompletionServiceImpl completionService;

    private Path tempBase;
    private Path finalBase;
    private String uploadId;

    @BeforeEach
    void setUp() {
        uploadSessionRepo = mock(UploadSessionRepo.class);
        fileRepo = mock(FileRepo.class);
        redisTemplate = mock(RedisTemplate.class);
        fileEntityMapper = mock(FileEntityMapper.class);
        completedResponse = mock(CompletedResponse.class);

        completionService = new uploadCompletionServiceImpl(
                uploadSessionRepo, completedResponse, fileEntityMapper, fileRepo, redisTemplate);

        tempBase = tempDir.resolve("temp");
        finalBase = tempDir.resolve("fin");
        uploadId = "upload_TEST";

        ReflectionTestUtils.setField(completionService, "PROGRESS_KEY_PREFIX", "progress : ");
        ReflectionTestUtils.setField(completionService, "baseTempPath", tempBase.toString());
        ReflectionTestUtils.setField(completionService, "baseFinalPath", finalBase.toString());
        ReflectionTestUtils.setField(completionService, "encryptionEnabled", false);
        ReflectionTestUtils.setField(completionService, "clamavEnabled", false);
        ReflectionTestUtils.setField(completionService, "clamavPath", tempDir.resolve("no-such-clamscan.exe").toString());

        when(fileEntityMapper.createFile(any(), any(), any(), any(), any()))
                .thenReturn(FileEntity.builder().fileName("file").build());
        when(completedResponse.createResponse(any(), any(), any(), any()))
                .thenReturn(UploadCompletedResponse.builder().build());
    }

    private void stubSessionAndProgress() {
        UploadSession session = UploadSession.builder()
                .uploadId(uploadId)
                .fileName("file")
                .totalChunks(1)
                .contentType("text/plain")
                .status(UploadSession.Status.IN_PROGRESS)
                .build();
        when(uploadSessionRepo.findByUploadId(uploadId)).thenReturn(Optional.of(session));

        HashOperations<String, Object, Object> hashOps = mock(HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOps);

        Map<Object, Object> progress = new HashMap<>();
        progress.put("totalChunks", "1");
        progress.put("receivedChunks", "1");
        progress.put("createdAt", Instant.now().toString());
        when(hashOps.entries(anyString())).thenReturn(progress);
    }

    private void writeChunk(int index) throws Exception {
        Path chunkDir = tempBase.resolve(uploadId);
        Files.createDirectories(chunkDir);
        Files.writeString(chunkDir.resolve(String.valueOf(index)), "hello from chunk", StandardCharsets.UTF_8);
    }

    @Test
    void completionWithClamavDisabledCompletesWithoutScan() throws Exception {
        writeChunk(0);
        stubSessionAndProgress();

        completionService.uploadCompleted(uploadId);

        Path finalFile = finalBase.resolve(uploadId + "_file.txt");
        assertTrue(Files.exists(finalFile), "merged final file should exist");
        assertFalse(Files.exists(finalBase.resolve(uploadId + "_file.txt.enc")), "no encrypted file when AES disabled");

        verify(fileRepo).save(any(FileEntity.class));
        verify(fileEntityMapper).createFile(eq("file"), eq(uploadId), anyString(), eq(".txt"), eq("16"));
    }

    @Test
    void completionWithClamavEnabledAndScanFailureFailsSafely() throws Exception {
        writeChunk(0);
        stubSessionAndProgress();
        ReflectionTestUtils.setField(completionService, "clamavEnabled", true);
        ReflectionTestUtils.setField(completionService, "clamavPath", tempDir.resolve("missing-clamscan.exe").toString());

        assertThrows(DirectoryException.class, () -> completionService.uploadCompleted(uploadId));

        verify(fileRepo, never()).save(any());
    }

    @Test
    void completionWithEncryptionEnabledStoresEncryptedFile() throws Exception {
        writeChunk(0);
        stubSessionAndProgress();
        ReflectionTestUtils.setField(completionService, "encryptionEnabled", true);
        ReflectionTestUtils.setField(completionService, "aesKey", tempDir.resolve("aes.txt").toString());
        ReflectionTestUtils.setField(completionService, "encryptionLevel", 256);

        completionService.uploadCompleted(uploadId);

        Path encryptedFile = finalBase.resolve(uploadId + "_file.txt.enc");
        assertTrue(Files.exists(encryptedFile), "encrypted file should exist");
        assertFalse(Files.exists(finalBase.resolve(uploadId + "_file.txt")), "plaintext should be removed after encryption");

        verify(fileRepo).save(any(FileEntity.class));
    }
}
