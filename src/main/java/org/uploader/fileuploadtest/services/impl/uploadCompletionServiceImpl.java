package org.uploader.fileuploadtest.services.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.tika.Tika;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.uploader.fileuploadtest.configs.FileTypeConfig;
import org.uploader.fileuploadtest.dto.response.upload.UploadCompletedResponse;
import org.uploader.fileuploadtest.entities.FileEntity;
import org.uploader.fileuploadtest.entities.UploadSession;
import org.uploader.fileuploadtest.exception_handling.costumeErrors.directory.DirectoryException;
import org.uploader.fileuploadtest.exception_handling.costumeErrors.directory.DirectorySortingException;
import org.uploader.fileuploadtest.exception_handling.costumeErrors.encryption.AesEncryptionException;
import org.uploader.fileuploadtest.exception_handling.costumeErrors.uploading.*;
import org.uploader.fileuploadtest.mapper.uploadProccess.CompletedResponse;
import org.uploader.fileuploadtest.mapper.uploadProccess.FileEntityMapper;
import org.uploader.fileuploadtest.repos.FileRepo;
import org.uploader.fileuploadtest.repos.UploadSessionRepo;
import org.uploader.fileuploadtest.services.UploadCompletionService;

import javax.crypto.*;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
@Slf4j
public class uploadCompletionServiceImpl implements UploadCompletionService {
    private final UploadSessionRepo uploadSessionRepo;
    private final CompletedResponse completedResponse;
    private final FileEntityMapper fileEntityMapper;
    private final FileRepo fileRepo;

    private final RedisTemplate<String , String> redisTemplate;

    @Value("${app.redis.progress-key-prefix}")
    private String PROGRESS_KEY_PREFIX;

    @Value("${app.upload.base-path}")
    private String baseTempPath;
    @Value("${app.upload.final-path}")
    private String baseFinalPath;
    @Value("${app.encryption.enabled}")
    private Boolean encryptionEnabled;
    @Value("${app.encryption.aes-key}")
    private String aesKey;
    @Value("${app.encryption.level}")
    private int encryptionLevel;

    @Override
    public UploadCompletedResponse uploadCompleted(String uploadId){

        String progressKey = PROGRESS_KEY_PREFIX + uploadId;

        Path tempFilePath = Paths.get(baseTempPath , uploadId);

        UploadSession currentSession = getCurrentSession(uploadId);

        uploadCompletionCheck(progressKey , uploadId);

        Path fileDirectory = pathsValidityCheck();

        Path finalFilePath = fileDirectory.resolve(uploadId + "_" + currentSession.getFileName());

        List<Path> chunks = getChunksList(tempFilePath);

        mergingChunks(finalFilePath , chunks);

        deleteTempFiles(tempFilePath);

        Long fileSize = getFileSize(finalFilePath);

        String MimeExtension = MimeValidation(finalFilePath);

        finalFilePath = refactorFileExtension(MimeExtension , finalFilePath , currentSession.getFileName() , uploadId);

        Boolean noninfected = scanWithClamAv(finalFilePath.toString());

        if(!noninfected){
            deleteInfectedFile(finalFilePath , "This file is a Danger for our system");
        }

        encryptFile(finalFilePath);

        FileEntity fileMetaData = fileEntityMapper.createFile(
                currentSession.getFileName(),
                currentSession.getUploadId(),
                MimeExtension,
                fileSize.toString()
        );

        fileRepo.save(fileMetaData);


        return completedResponse.createResponse(
                currentSession.getFileName(),
                fileSize.toString(),
                MimeExtension,
                currentSession.getStatus()
        );
    }


    //--------------------completionMethods--------------------------------

    private void uploadCompletionCheck(String progressKey , String uploadId){

        Path tempDir = Paths.get(baseTempPath , uploadId);

        Map<Object , Object> progress = redisTemplate.opsForHash().entries(progressKey);

        Map<String , String > keys = progress.entrySet()
                .stream()
                .collect(
                        Collectors.toMap(
                                e -> e.getKey().toString(),
                                e-> e.getValue().toString()
                        )
                );

        if( !keys.get("totalChunks").equals(keys.get("receivedChunks"))){
            throw new IncompletedUploadSession("code 6");
        }

        if(!Files.exists(tempDir)){
            throw new InactivatedUploadSession("We could not find your Directory ");
        }
    }

    private Path pathsValidityCheck() {
        Path finalPath = Paths.get(baseFinalPath);

        createFinalPathDirectory(finalPath);

        return finalPath;

    }

    private void mergingChunks(Path finalPath , List<Path> chunkFiles) {

        try(FileChannel outChannel = FileChannel.open(
                finalPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING
        )){
            for (Path chunkPath : chunkFiles){
                try(FileChannel inChannel = FileChannel.open(chunkPath , StandardOpenOption.READ)){
                    long size = inChannel.size();
                    long position = 0L;

                    while (position < size){
                        position+= inChannel.transferTo(position , size-position , outChannel);
                    }

                }
            }


        } catch (IOException e) {
            throw new AssemblingException("fail to open the channel");
        }


    }

    private void deleteTempFiles(Path tempFilePath) {
        try {

            if(Files.exists(tempFilePath)){
                File tempFolder = new File(tempFilePath.toString());

                FileUtils.deleteDirectory(tempFolder);
            }

        } catch (IOException e) {
            throw new DirectoryException("cannot delete the temp folder code : 152");
        }
    }

    private Long getFileSize(Path filePath)  {
        try{
            return Files.size(filePath);
        }
        catch (IOException e){
            throw new DirectoryException("cannot return fileSize");
        }
    }

    private String MimeValidation(Path finalFilePath) {
        try {
            Tika tika = new Tika();
            String mimeType = tika.detect(finalFilePath);

            if (! FileTypeConfig.ALLOWED_MIME_TYPES.contains(mimeType)){

                deleteInfectedFile(finalFilePath , "this file type : " + mimeType +" is not supported or acceptable");//clean the path
            }


            return extensionFromMime(mimeType);

        }
        catch (IOException e){
            throw new DirectoryException("io exception code : 162");
        }

    }

    private Path refactorFileExtension(String mimeExtension, Path finalFilePath , String fileName , String uploadId) {
        try {
            Path extensionEdited = finalFilePath.resolveSibling(uploadId + "_" + fileName + mimeExtension);

            Files.move(finalFilePath, extensionEdited, StandardCopyOption.REPLACE_EXISTING);

            return extensionEdited;
        }
        catch (IOException e){
            throw new DirectoryException("couldn't set the real file extension");
        }

    }

    private Boolean scanWithClamAv(String filePath) {

        try {
            ProcessBuilder pb = new ProcessBuilder("C:\\Program Files\\ClamAV\\clamscan.exe" , filePath);

            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            int exitCode = process.waitFor();

            log.info("ClamAV output: {}" , output);
            log.info("exist code is : {}" , exitCode);

            if (exitCode == 0) {
                return true;  // File is clean
            } else if (exitCode == 1) {
                return false; // Virus found
            } else {
                // Exit code 2 or other - error occurred
                throw new DirectoryException("ClamAV scan error: " + output);
            }
        }
        catch (IOException | InterruptedException exceptions){
            throw new DirectoryException("something went wrong with antivirus");
        }

    }

    private void encryptFile(Path filePath){
        if (!encryptionEnabled){
            return;
        }
        try {
            Path aesPath = Paths.get(aesKey);

            byte[] iv = new byte[16];
            SecureRandom random = new SecureRandom();
            random.nextBytes(iv);
            IvParameterSpec ivSpec = new IvParameterSpec(iv);

            SecretKey secretKey = getOrCreateSecretKey(aesPath);
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE , secretKey , ivSpec);

            Path encryptedPath = filePath.resolveSibling(filePath.getFileName() + ".enc");

            try(InputStream in = Files.newInputStream(filePath);
            OutputStream out = Files.newOutputStream(encryptedPath)) {

                out.write(iv);

                byte[] buffer = new byte[8192];
                int byteRead;
                while ((byteRead = in.read(buffer)) != -1 ){
                    byte[] encrypted = cipher.update(buffer , 0 , byteRead);
                    if (encrypted != null) {
                        out.write(encrypted);
                    }
                }
                byte[] finalBlock = cipher.doFinal();
                if (finalBlock != null) {
                    out.write(finalBlock);
                }

            } catch (IllegalBlockSizeException | BadPaddingException e) {
                throw new AesEncryptionException("cannot finalize the process of encryption code 113");
            }

            Files.deleteIfExists(filePath);

        }
        catch (NoSuchPaddingException | NoSuchAlgorithmException | IOException e) {
            throw new AesEncryptionException("encryption couldn't done code 111");
        }
        catch (InvalidAlgorithmParameterException | InvalidKeyException e) {
            throw new AesEncryptionException("invalid algorithm path code 112");
        }
    }


    //-------------------reusable methods for completion----------------------------------



    private void createFinalPathDirectory(Path finalPath){
        if (!Files.exists(finalPath)){
            try {
                Files.createDirectories(finalPath);
                log.info("final files folder created");
            }
            catch (IOException e){
                throw new DirectoryException("cannot create temp directory for your session");
            }
        }
    }

    private List<Path> getChunksList(Path fileDirectory) {

        try (Stream<Path> stream = Files.list(fileDirectory)){
            return stream
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(p -> Integer.parseInt(p.getFileName().toString())))
                    .collect(Collectors.toList());
        }
        catch (IOException e){
            throw new DirectorySortingException("wrong sorting");
        }

    }

    private static String extensionFromMime(String mimeType) {
        return switch (mimeType) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/gif" -> ".gif";
            case "video/mp4" -> ".mp4";
            case "video/webm" -> ".webm";
            case "video/quicktime" -> ".mov";
            case "application/pdf" -> ".pdf";
            case "application/json" -> ".json";
            case "text/plain" -> ".txt";
            default -> ".bin"; // fallback
        };
    }

    private SecretKey getOrCreateSecretKey(Path aesPath) {
        try {
            if (Files.exists(aesPath)){

                String aesKey = Files.readString(aesPath);

                byte[] decodedKey = Base64.getDecoder().decode(aesKey);
                return new SecretKeySpec(decodedKey, 0, decodedKey.length, "AES");

            }
            else
            {
                Files.createDirectories(aesPath.getParent());

                SecretKey aesKey = SecretKeyGen();

                String encodedKey = Base64.getEncoder().encodeToString(aesKey.getEncoded());

                Files.writeString(aesPath , encodedKey ,StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

                return aesKey;
            }
        }catch (IOException e){
            throw new DirectoryException("can't create the secret file");
        }

    }

    private SecretKey SecretKeyGen() {
        try {

            KeyGenerator keyGen = KeyGenerator.getInstance("AES");
            keyGen.init(encryptionLevel);
            return keyGen.generateKey();

        }
        catch (Exception e){
            throw new AesEncryptionException("cannot generate the Aes key");
        }
    }

    //-------------------------reusable methods--------------------------

    private UploadSession getCurrentSession(String uploadId){

        return uploadSessionRepo.findByUploadId(uploadId)
                .orElseThrow(() -> new InvalidUploadSession("code : 1") );

    }

    private void deleteInfectedFile(Path file , String message){
        try {
            Files.deleteIfExists(file); //clean the path

            throw new IllegalFileException(message);
        }
        catch (IOException e){
            throw new DirectoryException("io exception code : 162");
        }
    }
}

