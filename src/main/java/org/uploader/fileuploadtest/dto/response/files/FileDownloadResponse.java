package org.uploader.fileuploadtest.dto.response.files;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class FileDownloadResponse {
    private String fileName;

    private String fileSize;

    private String fileType;

    private byte[] file;
}
