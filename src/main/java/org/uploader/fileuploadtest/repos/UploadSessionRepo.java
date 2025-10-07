package org.uploader.fileuploadtest.repos;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.uploader.fileuploadtest.entities.UploadSession;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface UploadSessionRepo extends JpaRepository<UploadSession , Long> {
    Boolean existsUploadSessionByFileName(String fileName);

    Optional<UploadSession> findByUploadId(String uploadId);

    List<UploadSession> findByCreatedAtBefore(Instant cutout);

    @Modifying
    @Query("UPDATE UploadSession u SET u.status = :status WHERE u.uploadId IN :ids")
    void markListAsStatus(@Param("status")UploadSession.Status status , @Param("ids") List<String> ids);

    void deleteByStatus(UploadSession.Status status);
}
