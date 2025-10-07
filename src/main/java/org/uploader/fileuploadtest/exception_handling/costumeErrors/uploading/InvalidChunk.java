package org.uploader.fileuploadtest.exception_handling.costumeErrors.uploading;

public class InvalidChunk extends RuntimeException {
    public InvalidChunk(String message) {
        super(message);
    }
}
