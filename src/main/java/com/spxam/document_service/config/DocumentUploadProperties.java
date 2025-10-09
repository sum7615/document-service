package com.spxam.document_service.config;

import java.util.Arrays;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "document.upload")
public class DocumentUploadProperties {
    private long maxFileSize = 50 * 1024 * 1024; // 50MB
    private List<String> allowedContentTypes = Arrays.asList(
        "application/pdf", "image/jpeg", "image/png", 
        "text/plain", "application/msword",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    );
    private boolean virusScanEnabled = true;
    private boolean cacheScanResults = true;
    private int scanCacheHours = 24;
}