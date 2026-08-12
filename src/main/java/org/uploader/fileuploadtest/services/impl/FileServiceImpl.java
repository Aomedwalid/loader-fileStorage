package org.uploader.fileuploadtest.services.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.uploader.fileuploadtest.dto.response.files.FileResponse;
import org.uploader.fileuploadtest.dto.response.files.PageResponse;
import org.uploader.fileuploadtest.entities.FileEntity;
import org.uploader.fileuploadtest.exception_handling.costumeErrors.directory.DirectoryException;
import org.uploader.fileuploadtest.exception_handling.costumeErrors.download.InvalidDownloadRequest;
import org.uploader.fileuploadtest.exception_handling.costumeErrors.uploading.IllegalFileException;
import org.uploader.fileuploadtest.mapper.PageMapper;
import org.uploader.fileuploadtest.repos.FileRepo;
import org.uploader.fileuploadtest.services.FileService;

import javax.crypto.*;
import javax.crypto.spec.IvParameterSpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
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

    @Value("${app.download.chunk-size:1048576}")
    private long defaultChunkSize;

    @Value("${app.download.max-chunk-size:104857600}")
    private long maxChunkSize;

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
        FileEntity file = findFile(fileName);

        return downloadResponse(file, 0, Long.MAX_VALUE, null);
    }

    @Override
    public ResponseEntity<StreamingResponseBody> downloadFileChunk(String fileName, int index, Long requestedChunkSize) {
        FileEntity file = findFile(fileName);

        long chunkSize = resolveChunkSize(requestedChunkSize);
        long logicalSize = parseStoredFileSize(file);
        long totalChunks = Math.max(1, (logicalSize + chunkSize - 1) / chunkSize);

        if (index < 0 || index >= totalChunks){
            throw new InvalidDownloadRequest("chunk index must be between 0 and " + (totalChunks - 1));
        }

        long start = index * chunkSize;
        long end = Math.min(start + chunkSize, logicalSize);

        String contentRange = "bytes " + start + "-" + (end - 1) + "/" + logicalSize;

        return downloadResponse(file, start, end - start, contentRange);
    }

    private ResponseEntity<StreamingResponseBody> downloadResponse(
            FileEntity file, long start, long length, String contentRange) {

        boolean partial = contentRange != null;

        MediaType mediaType = MediaType.APPLICATION_OCTET_STREAM; // fallback
        try {
            mediaType = MediaType.parseMediaType(file.getFileType());
        } catch (Exception ignored) { }

        String downloadName = buildDownloadName(file);
        ContentDisposition contentDisposition = ContentDisposition
                .attachment()
                .filename(downloadName, StandardCharsets.UTF_8)
                .build();

        ResponseEntity.BodyBuilder builder = ResponseEntity
                .status(partial ? HttpStatus.PARTIAL_CONTENT : HttpStatus.OK)
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition.toString())
                .contentType(mediaType);

        if (partial){
            builder.contentLength(length);
            builder.header(HttpHeaders.CONTENT_RANGE, contentRange);
            builder.header(HttpHeaders.ACCEPT_RANGES, "bytes");
        }

        return builder.body(streamRange(file, start, length));
    }

    private String buildDownloadName(FileEntity file) {
        String fileName = file.getFileName();
        String extension = file.getFileType();

        if (!fileName.toLowerCase().endsWith(extension)) {
            return fileName + extension;
        }
        return fileName;
    }

    private StreamingResponseBody streamRange(FileEntity file, long start, long length) {
        Path filePath = Paths.get(file.getFilePath());

        if (!encryptionEnabled) {
            return outputStream -> {
                try (InputStream in = Files.newInputStream(filePath)) {
                    skipFully(in, start);
                    copyUpTo(in, outputStream, length);
                }
            };
        }

        SecretKey secretKey = uploadCompletionService.getOrCreateSecretKey(Paths.get(aesKey));

        return outputStream -> {
            try (InputStream in = Files.newInputStream(filePath)) {

                byte[] iv = new byte[16];
                if (in.read(iv) != 16) {
                    throw new DirectoryException("Invalid encrypted file: missing IV");
                }
                IvParameterSpec ivSpec = new IvParameterSpec(iv);

                Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
                cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec);

                long toSkip = start;
                long toWrite = length;
                byte[] buffer = new byte[8192];
                int bytesRead;

                while (toSkip > 0 && (bytesRead = in.read(buffer)) != -1) {
                    byte[] decrypted = cipher.update(buffer, 0, bytesRead);
                    if (decrypted != null) {
                        if (decrypted.length <= toSkip) {
                            toSkip -= decrypted.length;
                        } else {
                            int skip = (int) toSkip;
                            int writeLength = (int) Math.min(decrypted.length - skip, toWrite);
                            outputStream.write(decrypted, skip, writeLength);
                            toWrite -= writeLength;
                            toSkip = 0;
                        }
                    }
                }

                while (toWrite > 0 && (bytesRead = in.read(buffer)) != -1) {
                    byte[] decrypted = cipher.update(buffer, 0, bytesRead);
                    if (decrypted != null) {
                        int writeLength = (int) Math.min(decrypted.length, toWrite);
                        outputStream.write(decrypted, 0, writeLength);
                        toWrite -= writeLength;
                    }
                }

                if (toWrite > 0) {
                    byte[] finalBlock = cipher.doFinal();
                    if (finalBlock != null) {
                        int writeLength = (int) Math.min(finalBlock.length, toWrite);
                        outputStream.write(finalBlock, 0, writeLength);
                    }
                }

            } catch (NoSuchPaddingException |
                     NoSuchAlgorithmException |
                     InvalidAlgorithmParameterException |
                     InvalidKeyException e) {
                throw new DirectoryException("Algorithm error code: e1");
            } catch (IllegalBlockSizeException | BadPaddingException e) {
                throw new DirectoryException("Could not finish decryption process code: e2");
            } catch (IOException e) {
                throw new DirectoryException("Could not read file code: e3");
            }
        };
    }

    private void skipFully(InputStream in, long n) throws IOException {
        long remaining = n;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped > 0) {
                remaining -= skipped;
            } else if (in.read() == -1) {
                break;
            } else {
                remaining--;
            }
        }
    }

    private void copyUpTo(InputStream in, OutputStream out, long max) throws IOException {
        byte[] buffer = new byte[8192];
        while (max > 0) {
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, max));
            if (read == -1) break;
            out.write(buffer, 0, read);
            max -= read;
        }
    }

    private FileEntity findFile(String fileName) {
        return fileRepo.findByFileName(fileName)
                .orElseThrow(() -> new IllegalFileException("This file does not exist"));
    }

    private long resolveChunkSize(Long requestedChunkSize) {
        long chunkSize = requestedChunkSize != null ? requestedChunkSize : defaultChunkSize;

        if (chunkSize < 1) {
            throw new InvalidDownloadRequest("chunkSize must be at least 1");
        }
        if (chunkSize > maxChunkSize) {
            throw new InvalidDownloadRequest("chunkSize must not exceed " + maxChunkSize);
        }
        return chunkSize;
    }

    private long parseStoredFileSize(FileEntity file) {
        try {
            return Long.parseLong(file.getFileSize());
        } catch (NumberFormatException e) {
            throw new DirectoryException("invalid stored file size for " + file.getFileName());
        }
    }

}
