package org.uploader.fileuploadtest.services.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.connection.RedisKeyCommands;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.uploader.fileuploadtest.dto.request.UploadSessionRequest;
import org.uploader.fileuploadtest.dto.response.upload.ChunkResponse;
import org.uploader.fileuploadtest.dto.response.upload.UploadSessionResponse;
import org.uploader.fileuploadtest.dto.response.upload.UploadStatusResponse;
import org.uploader.fileuploadtest.entities.UploadSession;
import org.uploader.fileuploadtest.exception_handling.costumeErrors.directory.DirectoryException;
import org.uploader.fileuploadtest.exception_handling.costumeErrors.uploading.AlreadyExistedFileName;
import org.uploader.fileuploadtest.exception_handling.costumeErrors.uploading.InactivatedUploadSession;
import org.uploader.fileuploadtest.exception_handling.costumeErrors.uploading.InvalidChunk;
import org.uploader.fileuploadtest.exception_handling.costumeErrors.uploading.InvalidUploadSession;
import org.uploader.fileuploadtest.mapper.uploadProccess.ChunksMapper;
import org.uploader.fileuploadtest.mapper.uploadProccess.UploadSessionMapper;
import org.uploader.fileuploadtest.mapper.uploadProccess.UploadStatusMapper;
import org.uploader.fileuploadtest.repos.UploadSessionRepo;
import org.uploader.fileuploadtest.services.UploadService;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class UploadServiceImpl implements UploadService {

    private final UploadSessionMapper uploadSessionMapper;
    private final UploadSessionRepo uploadSessionRepo;
    private final ChunksMapper chunksMapper;
    private final UploadStatusMapper uploadStatusMapper;

    private final RedisTemplate<String , String> redisTemplate;

    @Value("${app.upload.base-path}")
    private String basePath;

    @Value("${app.redis.progress-key-prefix}")
    private String PROGRESS_KEY_PREFIX;
    @Value("${app.redis.chunk-key-prefix}")
    private String CHUNK_KEY_PREFIX;

    @Override
    public UploadSessionResponse createUploadSession(UploadSessionRequest createUploadSession){

        if (uploadSessionRepo.existsUploadSessionByFileName(createUploadSession.getFileName())){
            throw new AlreadyExistedFileName("code u1");
        }

        UploadSession uploadSession = uploadSessionMapper.createUploadSession(
                createUploadSession.getFileName(),
                createUploadSession.getTotalChunks(),
                createUploadSession.getContentType()
        );

        UploadSession savedUploadSession = uploadSessionRepo.save(uploadSession);

        initializeRedisTracking(savedUploadSession.getUploadId() , savedUploadSession.getTotalChunks());
        return uploadSessionMapper.createUploadResponse(
                savedUploadSession.getUploadId(),
                savedUploadSession.getTotalChunks(),
                savedUploadSession.getStatus()
        );
    }


    @Override
    public ChunkResponse uploadChunk(MultipartFile chunk , int index , String uploadId) {

        String progressKey = PROGRESS_KEY_PREFIX + uploadId;
        String chunksKey = CHUNK_KEY_PREFIX + uploadId;

        Path tempDir = Paths.get(basePath , uploadId);

        UploadSession currentSession = getCurrentSession(uploadId);

        sessionStatusVerify(currentSession);

        validateChunkIndex(index , currentSession.getTotalChunks() , chunk , chunksKey);

        createUploadSessionDirectory(tempDir);

        uploadChunkToDirectory(chunk , tempDir , index);

        saveProgressInRedis(index , chunksKey , progressKey);



        return chunksMapper.createChunkResponse(
                index,
                currentSession.getFileName()
        );

    }

    @Override
    @Cacheable(value = "uploadStatus", key = "#uploadId")
    public UploadStatusResponse getUploadSessionStatus(String uploadId) {

        String progressKey = PROGRESS_KEY_PREFIX + uploadId;
        String chunksKey = CHUNK_KEY_PREFIX + uploadId;

        UploadSession currentSession = getCurrentSession(uploadId);

        Map< String , String > progress = getUploadDetails(progressKey);

        Set<Long> chunkSet = getUploadChunkSet(chunksKey);

        return uploadStatusMapper.createResponse(
                currentSession.getFileName(),
                currentSession.getStatus(),
                Instant.parse(progress.get("createdAt")),
                Long.parseLong(progress.get("totalChunks")),
                Long.parseLong(progress.get("receivedChunks")),
                chunkSet
        );
    }

    @Transactional
    @Override
    public void cleanOrphanedSessions() {

        Instant cutOut = Instant.now().minus(Duration.ofMinutes(110));

        List<UploadSession> uploadSessions = uploadSessionRepo.findByCreatedAtBefore(cutOut);

        Set<String> redisKeys = fetchRedisKeys();

        log.info("redis keys : " + redisKeys);

        List<String> abundantSessions = getAbundantSessions(uploadSessions , redisKeys);

        log.info("abundant uploadIds : " +  abundantSessions.toString());

        uploadSessionRepo.markListAsStatus(UploadSession.Status.CANCELED , abundantSessions);

        uploadSessionRepo.deleteByStatus(UploadSession.Status.CANCELED);

        deleteAbundantFiles(abundantSessions);

    }


    //-----------------------------initialization methods------------------------

    private void initializeRedisTracking(
            String uploadId , int totalChunks
    ) {
        String progressKey = PROGRESS_KEY_PREFIX + uploadId;
        String chunksKey = CHUNK_KEY_PREFIX + uploadId;

        redisTemplate.delete(progressKey);
        redisTemplate.delete(chunksKey);

        Map<String , Object> progressData = new HashMap<>();
        progressData.put("totalChunks" , String.valueOf(totalChunks));
        progressData.put("receivedChunks" , String.valueOf(0));
        progressData.put("createdAt" , Instant.now().toString());

        redisTemplate.opsForHash().putAll(progressKey , progressData);

        redisTemplate.expire(progressKey , Duration.ofHours(2));
        redisTemplate.expire(chunksKey , Duration.ofHours(2));
    }

    //------------------chunk uploading-----------------------------------

    private void sessionStatusVerify(UploadSession uploadSession){

        if ( uploadSession.getStatus().equals(UploadSession.Status.CANCELED) ||
                uploadSession.getStatus().equals(UploadSession.Status.COMPLETED)){
            throw new InactivatedUploadSession("code : 2");
        }

    }

    private void validateChunkIndex(
            int index , int totalChunks , MultipartFile chunk , String chunkKey
    ) {

        String indexStr = String.valueOf(index);

        Boolean indexExisted = redisTemplate.opsForSet().isMember(chunkKey , indexStr);

        if (index < 0 || index >= totalChunks){
            throw new InvalidChunk("index should be less than 0 and more than " + totalChunks);
        }

        if (chunk.isEmpty()){
            throw new InvalidChunk("emptyChunk");
        }

        if (Boolean.TRUE.equals(indexExisted)){
            throw new InvalidChunk("this index already used");
        }
    }

    private void createUploadSessionDirectory(Path tempDir)  {

        if (!Files.exists(tempDir)){

            try {
                Files.createDirectories(tempDir);
            }
            catch (IOException e){
                throw new DirectoryException("cannot create temp directory for your session");
            }

        }

    }

    private void uploadChunkToDirectory(MultipartFile chunk, Path tempDir, int index) {
        Path chunkPath = tempDir.resolve(String.valueOf(index));

        try {
            Files.copy(chunk.getInputStream() , chunkPath , StandardCopyOption.REPLACE_EXISTING);
        }
        catch (IOException e){
            throw new DirectoryException("cannot upload the chunk , code : 3");
        }
    }

    private void saveProgressInRedis(
            int index, String chunkKey ,  String progressKey) {


        redisTemplate.opsForSet().add(chunkKey , String.valueOf(index));

        redisTemplate.opsForHash().increment(progressKey , "receivedChunks" , 1);

        Duration ttl = Duration.ofHours(2);

        redisTemplate.expire(chunkKey , ttl);
        redisTemplate.expire(progressKey , ttl);


    }

    //---------------------status methods----------------------------------

    private Map<String , String> getUploadDetails(String progressKey) {

        Map<Object , Object> rawMap = redisTemplate.opsForHash().entries(progressKey);

        return rawMap.entrySet()
                .stream()
                .collect(
                        Collectors.toMap(
                                e -> e.getKey().toString(),
                                e-> e.getValue().toString()
                        )
                );
    }

    private Set<Long> getUploadChunkSet(String chunksKey) {

        Set<String> raw = redisTemplate.opsForSet().members(chunksKey);

        assert raw != null;

        return raw.stream()
                .map(Long::parseLong)
                .collect(Collectors.toSet());
    }

    //---------------------schedular methods-------------------------------

        private Set<String> fetchRedisKeys() {

            Set<String> keys = new HashSet<>();

            ScanOptions options = ScanOptions.scanOptions()
                    .match(PROGRESS_KEY_PREFIX + "*")
                    .count(1000)
                    .build();

            assert redisTemplate.getConnectionFactory() != null;

            RedisKeyCommands keyCommands = redisTemplate.getConnectionFactory().getConnection().keyCommands();
            try (Cursor<byte[]> cursor = keyCommands.scan(options)) {
                cursor.forEachRemaining(item -> keys.add(new String(item, StandardCharsets.UTF_8)));
            }

            return keys;

        }

    private List<String> getAbundantSessions(List<UploadSession> uploadSessions, Set<String> redisKeys) {

        return  uploadSessions
                .stream()
                .map(UploadSession::getUploadId)
                .filter(uploadId -> !redisKeys.contains(PROGRESS_KEY_PREFIX + uploadId))
                .toList();
    }

    private void deleteAbundantFiles(List<String> abundantSessions) {
        abundantSessions
                .forEach(f -> {

                    Path fileDir = Paths.get(basePath , f);

                    if(Files.exists(fileDir)){

                        File file = new File(basePath + "/" + f);

                        try {
                            FileUtils.deleteDirectory(file);
                            log.info("file deleted successfully");
                        }
                        catch (IOException e){
                            throw new DirectoryException("Garbage Collector : cannot sweep the folders");
                        }
                    }

                });
    }

    //---------------------reusable methods--------------------------------

    private UploadSession getCurrentSession(String uploadId){

        return uploadSessionRepo.findByUploadId(uploadId)
                .orElseThrow(() -> new InvalidUploadSession("code : 1") );

    }

}
