package org.uploader.fileuploadtest.services;

import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.uploader.fileuploadtest.dto.response.files.PageResponse;


public interface FileService {
    PageResponse getAllFiles(int page , int size);

    ResponseEntity<StreamingResponseBody> downloadFile(String fileName);
}
