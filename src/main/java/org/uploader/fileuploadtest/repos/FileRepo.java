package org.uploader.fileuploadtest.repos;

import org.springframework.data.jpa.repository.JpaRepository;
import org.uploader.fileuploadtest.entities.FileEntity;

public interface FileRepo extends JpaRepository<FileEntity, Long> {
}
