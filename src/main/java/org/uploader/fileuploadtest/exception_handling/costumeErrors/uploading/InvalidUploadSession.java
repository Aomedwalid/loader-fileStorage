package org.uploader.fileuploadtest.exception_handling.costumeErrors.uploading;

public class InvalidUploadSession extends RuntimeException{
    public InvalidUploadSession(String message){
        super(message);
    }
}
