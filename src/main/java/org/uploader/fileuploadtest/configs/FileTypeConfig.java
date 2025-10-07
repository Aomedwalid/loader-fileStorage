package org.uploader.fileuploadtest.configs;


import java.util.Set;

public class FileTypeConfig {
    public static final Set<String> ALLOWED_MIME_TYPES = Set.of(
            // Images
            "image/jpeg",
            "image/png",
            "image/gif",
            // Videos
            "video/mp4",
            "video/webm",
            "video/quicktime", // .mov
            // Documents
            "application/pdf",
            // Data / text
            "application/json",
            "text/plain"
    );
}
