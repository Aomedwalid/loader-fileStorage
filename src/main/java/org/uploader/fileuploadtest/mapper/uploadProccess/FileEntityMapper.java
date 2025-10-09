package org.uploader.fileuploadtest.mapper.uploadProccess;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.uploader.fileuploadtest.entities.FileEntity;

@Component
@RequiredArgsConstructor
public class FileEntityMapper {
    public FileEntity createFile(
            String fileName,
            String uploadSession,
            String filePath,
            String fileType,
            String fileSize
    ){
        return FileEntity.builder()
                .fileName(fileName)
                .uploadSession(uploadSession)
                .filePath(filePath)
                .fileSize(fileSize)
                .fileType(fileType)
                .build();
    }
}
