package com.spxam.document_service.service;

import java.io.IOException;
import java.util.UUID;

import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

public interface StorageService {
    String storeFile(MultipartFile file, UUID docId) throws IOException;
    Resource loadFile(String filePath);
    boolean deleteFile(String filePath);
}
