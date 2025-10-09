package org.uploader.fileuploadtest.rest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.uploader.fileuploadtest.dto.response.files.PageResponse;
import org.uploader.fileuploadtest.dto.response.main.MainResponse;
import org.uploader.fileuploadtest.mapper.MainResponseMapper;
import org.uploader.fileuploadtest.services.impl.FileServiceImpl;

@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
@Slf4j
public class UsersFiles {
    private final FileServiceImpl fileService;
    private final MainResponseMapper mainResponseMapper;

    @GetMapping("/")
    public ResponseEntity<MainResponse> getAllFilesMetadata(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ){
        PageResponse pageResponse = fileService.getAllFiles(page, size);

        log.info("page response {}" , pageResponse);
        MainResponse response = mainResponseMapper.success(
                HttpStatus.OK.value(),
                "pageFetched successfully",
                pageResponse
        );

        return ResponseEntity.ok(response);
    }

    @GetMapping("/download/{fileName}")
    public ResponseEntity<StreamingResponseBody> downloadFile(
            @PathVariable("fileName") String fileName
    ){
        return fileService.downloadFile(fileName);
    }

}