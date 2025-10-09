package com.spxam.document_service.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.tika.Tika;
import org.springframework.security.crypto.codec.Hex;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.spxam.document_service.config.DocumentUploadProperties;
import com.spxam.document_service.dto.ScanVerdict;
import com.spxam.document_service.entity.Document;
import com.spxam.document_service.enums.DocumentStatus;
import com.spxam.document_service.repository.DocumentRepository;

@Service
public class DocumentService {

    private final StorageService storageService;
    private final DocumentRepository documentRepository;
    private final ScanQueue scanQueue;
    private final ClamAvClient clamAvClient;
    private final DocumentUploadProperties properties;
    private final Tika tika = new Tika();
    
    // Improved cache with TTL
    private final Cache<String, ScanVerdict> scanCache = Caffeine.newBuilder()
        .expireAfterWrite(24, TimeUnit.HOURS)
        .maximumSize(10_000)
        .build();

    public DocumentService(StorageService storageService,
                           DocumentRepository documentRepository,
                           ScanQueue scanQueue,
                           ClamAvClient clamAvClient,
                           DocumentUploadProperties properties) {
        this.storageService = storageService;
        this.documentRepository = documentRepository;
        this.scanQueue = scanQueue;
        this.clamAvClient = clamAvClient;
        this.properties = properties;
    }

    public Document handleUpload(MultipartFile file, String tenantId, String uploadedBy) throws Exception {
        Path tempFile = null;
        try {
            // Validate input
            validateFile(file);
            
            UUID docId = UUID.randomUUID();
            tempFile = storageService.getTempPath(docId);
            Files.createDirectories(tempFile.getParent());

            // Calculate checksum
            MessageDigest digest = DigestUtils.getSha256Digest();
            try (InputStream is = file.getInputStream();
                 DigestInputStream dis = new DigestInputStream(is, digest);
                 OutputStream os = Files.newOutputStream(tempFile)) {
                dis.transferTo(os);
            }

            String sha256 = new String(Hex.encode(digest.digest()));
            
            // Check cache for known malicious files
            if (properties.isCacheScanResults()) {
                ScanVerdict cached = scanCache.getIfPresent(sha256);
                if (cached != null && cached.isMalicious()) {
                    Path quarantinePath = storageService.getQuarantinePath(sha256);
                    storageService.moveFile(tempFile, quarantinePath);
                    throw new IllegalStateException("File is malicious (cached verdict): " + cached.getReason());
                }
            }

            // Detect file type
            String detectedType = tika.detect(tempFile.toFile());
            String originalType = file.getContentType();
            String finalType = detectedType != null ? detectedType : originalType;
            
            if (!properties.getAllowedContentTypes().contains(finalType)) {
                Files.deleteIfExists(tempFile);
                throw new IllegalArgumentException("File type not allowed: " + finalType);
            }

            // Quick virus scan
            if (properties.isVirusScanEnabled()) {
                ClamResult quickScan = clamAvClient.scan(tempFile);
                if (quickScan.isMalicious()) {
                    Path quarantinePath = storageService.getQuarantinePath(sha256);
                    storageService.moveFile(tempFile, quarantinePath);
                    ScanVerdict verdict = ScanVerdict.malicious(quickScan.getSignature());
                    scanCache.put(sha256, verdict);
                    throw new IllegalStateException("File is malicious: " + quickScan.getSignature());
                }
            }

            // Create document entity
            Document doc = createDocumentEntity(file, docId, tenantId, uploadedBy, sha256, finalType, tempFile);
            
            // Save to database and queue for deep scan
            documentRepository.save(doc);
            scanQueue.publish(docId, sha256, finalType, tempFile.toAbsolutePath().toString());
            
            return doc;
            
        } catch (Exception e) {
            // Clean up temp file if something went wrong
            if (tempFile != null && Files.exists(tempFile)) {
                Files.deleteIfExists(tempFile);
            }
            throw e;
        }
    }

    private void validateFile(MultipartFile file) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Empty file");
        }
        
        if (file.getSize() > properties.getMaxFileSize()) {
            throw new IllegalArgumentException("File too large. Maximum size: " + properties.getMaxFileSize() + " bytes");
        }
        
        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.trim().isEmpty()) {
            throw new IllegalArgumentException("Invalid filename");
        }
        
        if (originalName.contains("..") || originalName.contains("/") || originalName.contains("\\")) {
            throw new IllegalArgumentException("Invalid filename");
        }
    }

    private Document createDocumentEntity(MultipartFile file, UUID docId, String tenantId, 
                                         String uploadedBy, String sha256, String contentType, 
                                         Path tempFile) {
        Document doc = new Document();
        doc.setId(docId);
        doc.setTenantId(tenantId);
        doc.setOriginalName(file.getOriginalFilename());
        doc.setContentType(contentType);
        doc.setSize(file.getSize());
        doc.setChecksum(sha256);
        doc.setChecksumAlgorithm("SHA-256");
        doc.setStorageName(docId.toString());
        doc.setExtension(getFileExtension(file.getOriginalFilename()));
        doc.setStoragePath(generateStoragePath(docId, file.getOriginalFilename(), tenantId));
        doc.setUploadedBy(uploadedBy);
        doc.setStatus(DocumentStatus.QUEUED);
        
        return doc;
    }

    private String getFileExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "";
        }
        return filename.substring(filename.lastIndexOf(".") + 1).toLowerCase();
    }

    private String generateStoragePath(UUID docId, String originalFilename, String tenantId) {
        String extension = getFileExtension(originalFilename);
        String filename = docId.toString() + (extension.isEmpty() ? "" : "." + extension);
        // Shard by first 2 characters of UUID for better filesystem performance
        return String.format("%s/%s/%s", 
            tenantId, 
            docId.toString().substring(0, 2), 
            filename);
    }

    // PDF Sanitization method (PDFBox 3.x compatible)
    public Path sanitizePDF(Path pdfFile) throws IOException {
        // Use Loader.loadPDF for PDFBox 3.x
        try (PDDocument doc = Loader.loadPDF(pdfFile.toFile())) {
            doc.getDocumentCatalog().setOpenAction(null);
            doc.getDocumentCatalog().setAcroForm(null);

            Path sanitized = Files.createTempFile("sanitized", ".pdf");
            doc.save(sanitized.toFile());
            return sanitized;
        }
    }

    // Async scan processor (to be called by your queue consumer)
    public void processDeepScan(UUID docId, String sha256, String filePath, String contentType) {
        Path file = Paths.get(filePath);
        try {
            // Additional sanitization for PDFs
            if ("application/pdf".equals(contentType)) {
                Path sanitized = sanitizePDF(file);
                Files.delete(file); // delete original
                file = sanitized; // use sanitized version
                // Recalculate checksum if needed
            }
            
            // Deep scan
            ClamResult fullScan = clamAvClient.scan(file);
            
            Document doc = documentRepository.findById(docId)
                .orElseThrow(() -> new IllegalStateException("Document not found: " + docId));
                
            if (fullScan.isMalicious()) {
                doc.setStatus(DocumentStatus.MALICIOUS);
                Path quarantinePath = storageService.getQuarantinePath(sha256);
                storageService.moveFile(file, quarantinePath);
                doc.setStoragePath(quarantinePath.toString());
                
                // Cache malicious verdict
                if (properties.isCacheScanResults()) {
                    scanCache.put(sha256, ScanVerdict.malicious(fullScan.getSignature()));
                }
            } else {
                doc.setStatus(DocumentStatus.AVAILABLE);
                Path permanentPath = storageService.getCleanPath(sha256);
                storageService.moveFile(file, permanentPath);
                doc.setStoragePath(permanentPath.toString());
                
                // Cache clean verdict
                if (properties.isCacheScanResults()) {
                    scanCache.put(sha256, ScanVerdict.clean());
                }
            }
            
            documentRepository.save(doc);
            
        } catch (Exception e) {
            // Update document status to indicate scan failure
            documentRepository.findById(docId).ifPresent(doc -> {
                doc.setStatus(DocumentStatus.FAILED);
                documentRepository.save(doc);
            });
            // Log error
        }
    }
}