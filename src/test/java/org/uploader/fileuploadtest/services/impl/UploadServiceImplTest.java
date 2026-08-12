package org.uploader.fileuploadtest.services.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.uploader.fileuploadtest.dto.request.UploadSessionRequest;
import org.uploader.fileuploadtest.dto.response.upload.UploadSessionResponse;
import org.uploader.fileuploadtest.entities.UploadSession;
import org.uploader.fileuploadtest.exception_handling.costumeErrors.uploading.InvalidFileName;
import org.uploader.fileuploadtest.mapper.uploadProccess.ChunksMapper;
import org.uploader.fileuploadtest.mapper.uploadProccess.UploadSessionMapper;
import org.uploader.fileuploadtest.mapper.uploadProccess.UploadStatusMapper;
import org.uploader.fileuploadtest.repos.UploadSessionRepo;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UploadServiceImplTest {

    private UploadSessionMapper uploadSessionMapper;
    private UploadSessionRepo uploadSessionRepo;
    private ChunksMapper chunksMapper;
    private UploadStatusMapper uploadStatusMapper;
    private RedisTemplate<String, String> redisTemplate;
    private UploadServiceImpl uploadService;

    @BeforeEach
    void setUp() {
        uploadSessionMapper = mock(UploadSessionMapper.class);
        uploadSessionRepo = mock(UploadSessionRepo.class);
        chunksMapper = mock(ChunksMapper.class);
        uploadStatusMapper = mock(UploadStatusMapper.class);
        redisTemplate = mock(RedisTemplate.class);

        uploadService = new UploadServiceImpl(
                uploadSessionMapper, uploadSessionRepo, chunksMapper, uploadStatusMapper, redisTemplate);

        ReflectionTestUtils.setField(uploadService, "basePath", ".");
        ReflectionTestUtils.setField(uploadService, "PROGRESS_KEY_PREFIX", "progress : ");
        ReflectionTestUtils.setField(uploadService, "CHUNK_KEY_PREFIX", "Chunk : ");
    }

    private UploadSessionRequest request(String fileName) {
        return UploadSessionRequest.builder()
                .fileName(fileName)
                .totalChunks(3)
                .contentType("text/plain")
                .build();
    }

    @Test
    void rejectsPathTraversalAndHeaderUnsafeFileNames() {
        assertThrows(InvalidFileName.class, () -> uploadService.createUploadSession(request("../../etc/passwd")));
        assertThrows(InvalidFileName.class, () -> uploadService.createUploadSession(request("..\\..\\evil")));
        assertThrows(InvalidFileName.class, () -> uploadService.createUploadSession(request("..")));
        assertThrows(InvalidFileName.class, () -> uploadService.createUploadSession(request(".")));
        assertThrows(InvalidFileName.class, () -> uploadService.createUploadSession(request("quote\"name")));
        assertThrows(InvalidFileName.class, () -> uploadService.createUploadSession(request("line\nbreak")));
        assertThrows(InvalidFileName.class, () -> uploadService.createUploadSession(request("")));
        assertThrows(InvalidFileName.class, () -> uploadService.createUploadSession(request("   ")));

        verify(uploadSessionRepo, never()).existsUploadSessionByFileName(anyString());
    }

    @Test
    void createsSessionForValidFileName() {
        when(uploadSessionRepo.existsUploadSessionByFileName(anyString())).thenReturn(false);

        UploadSession session = UploadSession.builder()
                .uploadId("upload_X")
                .totalChunks(3)
                .status(UploadSession.Status.IN_PROGRESS)
                .build();
        when(uploadSessionMapper.createUploadSession(anyString(), anyInt(), anyString())).thenReturn(session);
        when(uploadSessionRepo.save(any(UploadSession.class))).thenReturn(session);
        when(uploadSessionMapper.createUploadResponse(anyString(), anyInt(), any()))
                .thenReturn(UploadSessionResponse.builder().uploadId("upload_X").build());

        HashOperations<String, Object, Object> hashOps = mock(HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        when(redisTemplate.expire(anyString(), any())).thenReturn(true);

        UploadSessionResponse response = uploadService.createUploadSession(request("valid_name.txt"));

        assertNotNull(response);
        assertEquals("upload_X", response.getUploadId());
        verify(uploadSessionRepo).existsUploadSessionByFileName("valid_name.txt");
    }
}
