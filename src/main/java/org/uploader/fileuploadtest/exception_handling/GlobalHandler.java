package org.uploader.fileuploadtest.exception_handling;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.uploader.fileuploadtest.dto.response.ErrorResponse;
import org.uploader.fileuploadtest.dto.response.main.MainResponse;
import org.uploader.fileuploadtest.exception_handling.costumeErrors.directory.DirectoryException;
import org.uploader.fileuploadtest.exception_handling.costumeErrors.directory.DirectorySortingException;
import org.uploader.fileuploadtest.exception_handling.costumeErrors.download.InvalidDownloadRequest;
import org.uploader.fileuploadtest.exception_handling.costumeErrors.encryption.AesEncryptionException;
import org.uploader.fileuploadtest.exception_handling.costumeErrors.uploading.*;
import org.uploader.fileuploadtest.mapper.ErrorMapper;
import org.uploader.fileuploadtest.mapper.MainResponseMapper;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalHandler {

    private final MainResponseMapper mainResponseMapper;
    private final ErrorMapper errorMapper;



    //security detection

    @ExceptionHandler(AesEncryptionException.class)
    public ResponseEntity<MainResponse> DirectoryError(AesEncryptionException ex ,
                                                       HttpServletRequest request ){

        Map<String , String> errors = new HashMap<>();

        errors.put("purpose : " , ex.getMessage());

        ErrorResponse errorResponse = errorMapper.createNormalError(
                request.getRequestURI(),
                errors
        );

        MainResponse response = mainResponseMapper.failed(
                HttpStatus.BAD_REQUEST.value(),
                "encryption error",
                errorResponse
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);

    }


    @ExceptionHandler(IllegalFileException.class)
    public ResponseEntity<MainResponse> DirectoryError(IllegalFileException ex ,
                                                       HttpServletRequest request ){

        Map<String , String> errors = new HashMap<>();

        errors.put("purpose : " , ex.getMessage());

        ErrorResponse errorResponse = errorMapper.createNormalError(
                request.getRequestURI(),
                errors
        );

        MainResponse response = mainResponseMapper.failed(
                HttpStatus.BAD_REQUEST.value(),
                "merging error",
                errorResponse
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);

    }

    //merging
    @ExceptionHandler(AssemblingException.class)
    public ResponseEntity<MainResponse> DirectoryError(AssemblingException ex ,
                                                       HttpServletRequest request ){

        Map<String , String> errors = new HashMap<>();

        errors.put("purpose : " , ex.getMessage());

        ErrorResponse errorResponse = errorMapper.createNormalError(
                request.getRequestURI(),
                errors
        );

        MainResponse response = mainResponseMapper.failed(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "merging error",
                errorResponse
        );

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);

    }


    @ExceptionHandler(DirectorySortingException.class)
    public ResponseEntity<MainResponse> DirectoryError(DirectorySortingException ex ,
                                                       HttpServletRequest request ){

        Map<String , String> errors = new HashMap<>();

        errors.put("purpose : " , ex.getMessage());

        ErrorResponse errorResponse = errorMapper.createNormalError(
                request.getRequestURI(),
                errors
        );

        MainResponse response = mainResponseMapper.failed(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "Directory error",
                errorResponse
        );

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);

    }

    //files and directories


    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<MainResponse> noFileFound(HttpServletRequest request){

        ErrorResponse errorResponse = errorMapper.createNormalError(
                request.getRequestURI(),
                Collections.emptyMap()
        );

        MainResponse response = mainResponseMapper.failed(
                HttpStatus.FORBIDDEN.value(),
                "wrong input no file is found",
                errorResponse
        );

        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);

    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<MainResponse> directoryFileName(HttpServletRequest request ){

        ErrorResponse errorResponse = errorMapper.createNormalError(
                request.getRequestURI(),
                Collections.emptyMap()
        );

        MainResponse response = mainResponseMapper.failed(
                HttpStatus.BAD_REQUEST.value(),
                "chunk key should be file",
                errorResponse
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);

    }


    @ExceptionHandler(DirectoryException.class)
    public ResponseEntity<MainResponse> DirectoryError(DirectoryException ex ,
                                                             HttpServletRequest request ){

        Map<String , String> errors = new HashMap<>();

        errors.put("purpose : " , ex.getMessage());

        ErrorResponse errorResponse = errorMapper.createNormalError(
                request.getRequestURI(),
                errors
        );

        MainResponse response = mainResponseMapper.failed(
                HttpStatus.BAD_REQUEST.value(),
                "Directory error",
                errorResponse
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);

    }

    // upload session related errors

    @ExceptionHandler(AlreadyExistedFileName.class)
    public ResponseEntity<MainResponse> AlreadyExistedFileName(AlreadyExistedFileName ex ,
                                                       HttpServletRequest request ){

        Map<String , String> errors = new HashMap<>();

        errors.put("purpose : " , ex.getMessage());

        ErrorResponse errorResponse = errorMapper.createNormalError(
                request.getRequestURI(),
                errors
        );

        MainResponse response = mainResponseMapper.failed(
                HttpStatus.BAD_REQUEST.value(),
                "file name already existed",
                errorResponse
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);

    }

    @ExceptionHandler(InvalidFileName.class)
    public ResponseEntity<MainResponse> invalidFileName(InvalidFileName ex ,
                                                       HttpServletRequest request ){

        Map<String , String> errors = new HashMap<>();

        errors.put("purpose : " , ex.getMessage());

        ErrorResponse errorResponse = errorMapper.createNormalError(
                request.getRequestURI(),
                errors
        );

        MainResponse response = mainResponseMapper.failed(
                HttpStatus.BAD_REQUEST.value(),
                "invalid file name",
                errorResponse
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);

    }

    @ExceptionHandler(IncompletedUploadSession.class)
    public ResponseEntity<MainResponse> DirectoryError(IncompletedUploadSession ex ,
                                                       HttpServletRequest request ){

        Map<String , String> errors = new HashMap<>();

        errors.put("incompleted upload session" , ex.getMessage());

        ErrorResponse errorResponse = errorMapper.createNormalError(
                request.getRequestURI(),
                errors
        );

        MainResponse response = mainResponseMapper.failed(
                HttpStatus.BAD_REQUEST.value(),
                "incompleted chunks list",
                errorResponse
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);

    }



    @ExceptionHandler(InvalidChunk.class)
    public ResponseEntity<MainResponse> invalidUploadSession(InvalidChunk ex ,
                                                             HttpServletRequest request ){

        Map<String , String> errors = new HashMap<>();

        errors.put("purpose : " , ex.getMessage());

        ErrorResponse errorResponse = errorMapper.createNormalError(
                request.getRequestURI(),
                errors
        );

        MainResponse response = mainResponseMapper.failed(
                HttpStatus.BAD_REQUEST.value(),
                "Invalid chunk : ",
                errorResponse
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);

    }

    @ExceptionHandler(InactivatedUploadSession.class)
    public ResponseEntity<MainResponse> invalidUploadSession(InactivatedUploadSession ex ,
                                                             HttpServletRequest request ){

        ErrorResponse errorResponse = errorMapper.createNormalError(
                request.getRequestURI(),
                Collections.emptyMap()
        );

        MainResponse response = mainResponseMapper.failed(
                HttpStatus.BAD_REQUEST.value(),
                "Inactive upload Session : " + ex.getMessage(),
                errorResponse
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);

    }

    @ExceptionHandler(InvalidUploadSession.class)
    public ResponseEntity<MainResponse> invalidUploadSession(InvalidUploadSession ex ,
                                                             HttpServletRequest request ){

        ErrorResponse errorResponse = errorMapper.createNormalError(
                request.getRequestURI(),
                Collections.emptyMap()
        );

        MainResponse response = mainResponseMapper.failed(
                HttpStatus.BAD_REQUEST.value(),
                "invalid upload session : " + ex.getMessage(),
                errorResponse
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);

    }

    //validations errors

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<MainResponse> validationException(MethodArgumentNotValidException ex
            , HttpServletRequest request){

        Map<String , String> errors = new HashMap<>();

        ex.getBindingResult().getFieldErrors()
                .forEach(error ->
                        errors.put(error.getField() , error.getDefaultMessage())
                );

        ErrorResponse errorResponse = errorMapper.createNormalError(
                request.getRequestURI(),
                errors
        );

        MainResponse response = mainResponseMapper.failed(
                HttpStatus.BAD_REQUEST.value(),
                "upload validation error ",
                errorResponse
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    //not found address

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<MainResponse> wrongMethodRequest(HttpServletRequest request){

        ErrorResponse errorResponse = errorMapper.createNormalError(
                request.getRequestURI(),
                Collections.emptyMap()
        );

        MainResponse response = mainResponseMapper.failed(
                HttpStatus.NOT_FOUND.value(),
                "This address used a wrong method GET , POST , PATCH , DELETE , PUT",
                errorResponse
        );

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
    }

    @ExceptionHandler({NoHandlerFoundException.class , NoResourceFoundException.class})
    public ResponseEntity<MainResponse> notFoundPage(HttpServletRequest request){

        ErrorResponse errorResponse = errorMapper.createNormalError(
                request.getRequestURI(),
                Collections.emptyMap()
        );

        MainResponse response = mainResponseMapper.failed(
                HttpStatus.NOT_FOUND.value(),
                "This address is not existed",
                errorResponse
        );

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
    }

    // reusable errors

    @ExceptionHandler(HttpClientErrorException.BadRequest.class)
    public ResponseEntity<MainResponse> badInputsRequest( HttpServletRequest request){

        ErrorResponse errorResponse = errorMapper.createNormalError(
                request.getRequestURI(),
                Collections.emptyMap()
        );

        MainResponse response = mainResponseMapper.failed(
                HttpStatus.BAD_REQUEST.value(),
                "something went wrong!",
                errorResponse
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    //sql errors

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<MainResponse> uniqueKeyViolation( DataIntegrityViolationException e , HttpServletRequest request){

        ErrorResponse errorResponse = errorMapper.createNormalError(
                request.getRequestURI(),
                Collections.emptyMap()
        );

        MainResponse response = mainResponseMapper.failed(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "something went wrong! " + e.getMessage(),
                errorResponse
        );

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }

    //download errors

    @ExceptionHandler(InvalidDownloadRequest.class)
    public ResponseEntity<MainResponse> invalidDownloadRequest(InvalidDownloadRequest ex ,
                                                              HttpServletRequest request ){

        Map<String , String> errors = new HashMap<>();

        errors.put("purpose : " , ex.getMessage());

        ErrorResponse errorResponse = errorMapper.createNormalError(
                request.getRequestURI(),
                errors
        );

        MainResponse response = mainResponseMapper.failed(
                HttpStatus.BAD_REQUEST.value(),
                "invalid download request",
                errorResponse
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);

    }

    //Internal server errors

    @ExceptionHandler(Exception.class)
    public ResponseEntity<MainResponse> normalExceptionHandler(Exception ex
            ,HttpServletRequest request){

        ErrorResponse errorResponse = errorMapper.createNormalError(
                request.getRequestURI(),
                Collections.emptyMap()
        );

        MainResponse response = mainResponseMapper.failed(
                HttpStatus.BAD_REQUEST.value(),
                "something went wrong with the server!! : " + ex.getMessage(),
                errorResponse
        );

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }
}
