package org.uploader.fileuploadtest.exception_handling.costumeErrors.download;

public class InvalidDownloadRequest extends RuntimeException {
    public InvalidDownloadRequest(String message) {
        super(message);
    }
}
