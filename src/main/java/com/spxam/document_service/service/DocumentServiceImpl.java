package com.spxam.document_service.service;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.spxam.document_service.entity.Document;
import com.spxam.document_service.enums.ScanStatus;
import com.spxam.document_service.repository.DocumentRepository;

import lombok.AllArgsConstructor;

@Service
@AllArgsConstructor
public class DocumentServiceImpl implements DocumentService {
    private final DocumentRepository repository;
    private final StorageService storageService;
    private final VirusScanService virusScanService;

    @Override
    public Document storeFile(MultipartFile file, String uploadedBy) {
        try {
            UUID docId = UUID.randomUUID();
            String path = storageService.storeFile(file, docId);

            Document doc = new Document();
            doc.setId(docId);
            doc.setOriginalName(file.getOriginalFilename());
            doc.setContentType(file.getContentType());
            doc.setSize(file.getSize());
            doc.setExtension(FilenameUtils.getExtension(file.getOriginalFilename()));
            doc.setChecksum(calculateChecksum(file));
            doc.setUploadedBy(uploadedBy);
            doc.setStoragePath(path);
            doc.setScanStatus(ScanStatus.PENDING);

            // Save initial metadata
            repository.save(doc);

            // Virus Scan
            boolean clean = virusScanService.scanFile(file.getInputStream(), doc);
            repository.save(doc);

            if (!clean) throw new RuntimeException("Virus detected: " + doc.getScanResult());

            return doc;
        } catch (Exception e) {
            throw new RuntimeException("File upload failed", e);
        }
    }

    private String calculateChecksum(MultipartFile file) {
        try (InputStream is = file.getInputStream()) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
            byte[] hash = digest.digest();
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException("Checksum failed", e);
        }
    }
}
