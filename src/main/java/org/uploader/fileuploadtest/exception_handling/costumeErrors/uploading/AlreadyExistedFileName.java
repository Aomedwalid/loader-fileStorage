package org.uploader.fileuploadtest.exception_handling.costumeErrors.uploading;

public class AlreadyExistedFileName extends RuntimeException {
    public AlreadyExistedFileName(String message) {
        super(message);
    }
}
