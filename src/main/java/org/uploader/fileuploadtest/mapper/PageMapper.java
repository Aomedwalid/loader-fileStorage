package org.uploader.fileuploadtest.mapper;

import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;
import org.uploader.fileuploadtest.dto.response.files.FileResponse;
import org.uploader.fileuploadtest.dto.response.files.PageResponse;

import java.util.List;

@Component
public class PageMapper {
    public PageResponse createPageResponse(
            List<FileResponse> page,
            Long currentPage,
            Long totalPage,
            Long totalItems,
            Long pageSize
    ){
        return PageResponse
                .builder()
                .content(page)
                .currentPage(currentPage)
                .totalPages(totalPage)
                .totalItems(totalItems)
                .pageSize(pageSize)
                .build();
    }
}
