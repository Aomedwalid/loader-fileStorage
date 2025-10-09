package org.uploader.fileuploadtest.dto.response.main;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Component;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MainResponse {
    private Boolean success;

    private int status;

    private String message;

    private Object details;

    private Object errors;
}