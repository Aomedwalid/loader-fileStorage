package org.uploader.fileuploadtest.services.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.uploader.fileuploadtest.dto.response.files.FileResponse;
import org.uploader.fileuploadtest.dto.response.files.PageResponse;
import org.uploader.fileuploadtest.entities.FileEntity;
import org.uploader.fileuploadtest.exception_handling.costumeErrors.directory.DirectoryException;
import org.uploader.fileuploadtest.exception_handling.costumeErrors.uploading.IllegalFileException;
import org.uploader.fileuploadtest.mapper.PageMapper;
import org.uploader.fileuploadtest.repos.FileRepo;
import org.uploader.fileuploadtest.services.FileService;

import javax.crypto.*;
import javax.crypto.spec.IvParameterSpec;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class FileServiceImpl implements FileService {

    private final FileRepo fileRepo;
    private final PageMapper pageMapper;
    private final uploadCompletionServiceImpl uploadCompletionService;

    @Value("${app.encryption.enabled}")
    private Boolean encryptionEnabled;

    @Value("${app.encryption.aes-key}")
    private String aesKey;

    @Cacheable(value = "files", key = "#page + '-' + #size")
    @Override
    public PageResponse getAllFiles(int page, int size) {
        Pageable pageable = PageRequest.of(page, size);

        Page<FileResponse> excutedPage = fileRepo.findAllMetadata(pageable);

        List<FileResponse> pageContent = excutedPage.getContent();
        long totalItems = excutedPage.getTotalElements();
        int totalPages = excutedPage.getTotalPages();

        return pageMapper.createPageResponse(
                pageContent,
                (long) page,
                (long) totalPages,
                totalItems,
                (long) size
        );
    }


    @Override
    public ResponseEntity<StreamingResponseBody> downloadFile(String fileName) {

        FileEntity file = fileRepo.findByFileName(fileName)
                .orElseThrow(() -> new IllegalFileException("This file does not exist"));

        StreamingResponseBody fileData = getFileData(Paths.get(file.getFilePath()));

        MediaType mediaType = MediaType.APPLICATION_OCTET_STREAM; // fallback
        try {
            mediaType = MediaType.parseMediaType(file.getFileType());
        } catch (Exception ignored) { }

        String extension = file.getFileType();
        String downloadName = fileName;


        if (!fileName.toLowerCase().endsWith(extension)) {
            downloadName += extension;
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + downloadName + "\"")
                .contentType(mediaType)
                .body(fileData);
    }


    private StreamingResponseBody getFileData(Path encryptedPath) {

        if (!encryptionEnabled) {

            return outputStream -> Files.copy(encryptedPath, outputStream);
        }

        SecretKey secretKey = uploadCompletionService.getOrCreateSecretKey(Paths.get(aesKey));


        return outputStream -> {
            try (InputStream in = Files.newInputStream(encryptedPath)) {


                byte[] iv = new byte[16];
                if (in.read(iv) != 16) {
                    throw new DirectoryException("Invalid encrypted file: missing IV");
                }
                IvParameterSpec ivSpec = new IvParameterSpec(iv);

                Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
                cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec);


                byte[] buffer = new byte[8192];
                int bytesRead;

                while ((bytesRead = in.read(buffer)) != -1) {
                    byte[] decrypted = cipher.update(buffer, 0, bytesRead);
                    if (decrypted != null) {
                        outputStream.write(decrypted);
                    }
                }


                byte[] finalBlock = cipher.doFinal();
                if (finalBlock != null) {
                    outputStream.write(finalBlock);
                }

            } catch (NoSuchPaddingException |
                     NoSuchAlgorithmException |
                     InvalidAlgorithmParameterException |
                     InvalidKeyException e) {
                throw new DirectoryException("Algorithm error code: e1");
            } catch (IllegalBlockSizeException | BadPaddingException e) {
                throw new DirectoryException("Could not finish decryption process code: e2");
            }
        };
    }

}
