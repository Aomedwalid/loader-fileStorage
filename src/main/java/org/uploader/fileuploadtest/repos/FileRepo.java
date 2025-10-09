package org.uploader.fileuploadtest.repos;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.uploader.fileuploadtest.dto.response.files.FileResponse;
import org.uploader.fileuploadtest.entities.FileEntity;

import java.util.Optional;


public interface FileRepo extends JpaRepository<FileEntity, Long> {

    @Query("SELECT new org.uploader.fileuploadtest.dto.response.files.FileResponse(f.id , f.fileName , f.fileType , f.fileSize , f.createdAt) FROM FileEntity f")
    Page<FileResponse> findAllMetadata(Pageable pageable);

    Optional<FileEntity> findByFileName(String fileName);
}
