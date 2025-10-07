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
            String fileType,
            String fileSize
    ){
        return FileEntity.builder()
                .fileName(fileName)
                .uploadSession(uploadSession)
                .fileSize(fileSize)
                .fileType(fileType)
                .build();
    }
}
