package org.uploader.fileuploadtest.dto.response.upload;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Component;
import org.uploader.fileuploadtest.entities.UploadSession;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class UploadCompletedResponse {

    private String fileName;

    private String fileSize;

    private String contentType;

    private UploadSession.Status status;

}
