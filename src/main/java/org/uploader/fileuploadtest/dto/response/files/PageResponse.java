package org.uploader.fileuploadtest.dto.response.files;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

import java.util.List;


@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class PageResponse {
    private List<FileResponse> content;

    private Long currentPage;

    private Long totalPages;

    private Long totalItems;

    private Long pageSize;
}
