package org.uploader.fileuploadtest.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

@Getter
@Setter
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true , nullable = false)
    private String fileName;

    @Column(nullable = false)
    private String uploadSession;

    @Column(nullable = false)
    private String fileType;

    @Column(nullable = false)
    private String fileSize;

    @CreationTimestamp
    @Column(nullable = false , name = "created_at" , updatable = false)
    private String createdAt;
}
