package com.spxam.document_service.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.tika.Tika;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.codec.Hex;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.spxam.document_service.config.DocumentUploadProperties;
import com.spxam.document_service.dto.DocEventPayload;
import com.spxam.document_service.dto.ScanVerdict;
import com.spxam.document_service.entity.Document;
import com.spxam.document_service.enums.DocumentStatus;
import com.spxam.document_service.exception.DocumentNotFoundException;
import com.spxam.document_service.kafka.KafkaProducer;
import com.spxam.document_service.repository.DocumentRepository;
import com.spxam.document_service.util.CommonUtil;

@Service
public class DocumentService {

    private final StorageService storageService;
    private final DocumentRepository documentRepository;
    private final ScanQueue scanQueue;
    private final ClamAvClient clamAvClient;
    private final DocumentUploadProperties properties;
    private final Tika tika = new Tika();
    private final KafkaProducer kafkaProducer;
    
    private static final Logger logger = LoggerFactory.getLogger(DocumentService.class);
    // Improved cache with TTL
    private final Cache<String, ScanVerdict> scanCache = Caffeine.newBuilder()
        .expireAfterWrite(24, TimeUnit.HOURS)
        .maximumSize(10_000)
        .build();

    @Value("${app.kafka.topic.doc-events}")
    String docTopic; 
    public DocumentService(StorageService storageService,
                           DocumentRepository documentRepository,
                           ScanQueue scanQueue,
                           ClamAvClient clamAvClient,
                           DocumentUploadProperties properties,KafkaProducer kafkaProducer) {
        this.storageService = storageService;
        this.documentRepository = documentRepository;
        this.scanQueue = scanQueue;
        this.clamAvClient = clamAvClient;
        this.properties = properties;
        this.kafkaProducer=kafkaProducer;
    }

    public Document getDocument(String uuid) {
        UUID docId = UUID.fromString(uuid);
        return documentRepository.findById(docId).orElseThrow(() ->new DocumentNotFoundException("Document not found: " + uuid));
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
//        Map<String,Object> metaData = new HashMap();

        Document doc = new Document();
        doc.setId(docId);
        doc.setTenantId(tenantId);
        doc.setOriginalName(file.getOriginalFilename());
        doc.setContentType(contentType);
        doc.setSize(file.getSize());
        doc.setChecksum(sha256);
//        doc.setMeta( );
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
    public void processDeepScan(UUID docId, String sha256, String filePath, String contentType) {
        Path file = Paths.get(filePath);
        Path fileToScan = file;

        try {
            // Additional sanitization for PDFs
            if ("application/pdf".equals(contentType)) {
                try {
                    fileToScan = handlePDFEncryption(file);
                } catch (IOException e) {
                    // Handle encrypted PDF that cannot be decrypted
                    logger.warn("Failed to process encrypted PDF for document "+docId.toString()+" : "+ e.getMessage());
                    handleEncryptedPDF(docId, sha256, file);
                    return;
                }
            }

            // Continue with deep scan using the processed file
            ClamResult fullScan = clamAvClient.scan(fileToScan);
            processScanResult(docId, sha256, file, fileToScan, fullScan);

        } catch (Exception e) {
            logger.error("Scan failed for doc "+docId+": "+e.getMessage());
            handleScanFailure(docId, e);
        } finally {
            // Clean up temporary file if one was created
            if (fileToScan != file && Files.exists(fileToScan)) {
                try {
                    Files.delete(fileToScan);
                } catch (IOException e) {
                    logger.warn("Failed to delete temporary file: {}", fileToScan, e);
                }
            }
        }
    }
    private void handleScanFailure(UUID docId, Exception error) {
        logger.error("Scan failure for document {}: {}", docId, error.getMessage(), error);

        try {
            Document doc = documentRepository.findById(docId).orElse(null);

            if (doc != null) {
                // Update document status
                doc.setStatus(DocumentStatus.SCAN_FAILED);

                // Add error metadata
                Map<String, Object> meta = doc.getMeta();
                if (meta == null) {
                    meta = new HashMap<>();
                }
                meta.put("scanError", true);
                meta.put("errorMessage", error.getMessage());
                meta.put("errorType", error.getClass().getSimpleName());
                meta.put("failedAt", Instant.now().toString());
                doc.setMeta(meta);

                documentRepository.save(doc);
                logger.warn("Document {} marked as SCAN_FAILED due to: {}", docId, error.getMessage());
            } else {
                logger.error("Document {} not found while handling scan failure", docId);
            }
        } catch (Exception e) {
            logger.error("Failed to handle scan failure for document {}: {}", docId, e.getMessage(), e);
        }

        DocEventPayload payload = new DocEventPayload(docId.toString(), DocumentStatus.SCAN_FAILED.toString());
        
        publish(CommonUtil.convertToJson(payload));
    }
    private Path handlePDFEncryption(Path pdfFile) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdfFile.toFile())) {
            if (doc.isEncrypted()) {
                doc.setAllSecurityToBeRemoved(true);

                Path sanitized = Files.createTempFile("decrypted", ".pdf");
                doc.save(sanitized.toFile());
                return sanitized;
            } else {
                // No encryption, just sanitize normally
                return sanitizePDF(pdfFile);
            }
        } catch (IOException e) {
            if (e.getMessage().contains("encryption dictionary")) {
                throw new IOException("PDF is encrypted and cannot be decrypted automatically", e);
            }
            throw e;
        }
    }
    private void processScanResult(UUID docId, String sha256, Path originalFile,
                                   Path fileToScan, ClamResult scanResult) {
        Document doc = documentRepository.findById(docId)
                .orElseThrow(() -> new IllegalStateException("Document not found: " + docId));

        try {
            if (scanResult.isMalicious()) {
                handleMaliciousFile(doc, sha256, originalFile, fileToScan, scanResult);
            } else {
                handleCleanFile(doc, sha256, originalFile, fileToScan, scanResult);
            }

            // Update cache with scan verdict
            cacheScanVerdict(sha256, scanResult);

        } catch (Exception e) {
            logger.error("Error processing scan result for document {}: {}", docId, e.getMessage(), e);
            handleScanProcessingError(doc, originalFile, fileToScan, e);
        }
    }

    private void handleMaliciousFile(Document doc, String sha256, Path originalFile,
                                     Path fileToScan, ClamResult scanResult) throws IOException {
        logger.warn("Malicious file detected for document {}: {}", doc.getId(), scanResult.getSignature());

        // Move to quarantine
        Path quarantinePath = storageService.getQuarantinePath(sha256);
        storageService.moveFile(originalFile, quarantinePath);

        // Update document
        doc.setStatus(DocumentStatus.QUARANTINED);
        doc.setStoragePath(quarantinePath.toString());

        // Add metadata about the threat
        Map<String, Object> meta = doc.getMeta();
        if (meta == null) {
            meta = new HashMap<>();
        }
        meta.put("threatDetected", true);
        meta.put("threatSignature", scanResult.getSignature());
        meta.put("quarantinedAt", Instant.now().toString());
        doc.setMeta(meta);

        documentRepository.save(doc);
        logger.info("Document {} quarantined due to threat: {}", doc.getId(), scanResult.getSignature());

        DocEventPayload payload = new DocEventPayload(doc.getId().toString(), DocumentStatus.QUARANTINED.toString());
        
        publish(CommonUtil.convertToJson(payload));
        // Clean up temporary file if different from original
        cleanupTemporaryFile(originalFile, fileToScan);
    }

    private void handleCleanFile(Document doc, String sha256, Path originalFile,
                                 Path fileToScan, ClamResult scanResult) throws IOException {

        // Determine which file to use for permanent storage
        Path fileForPermanentStorage = fileToScan;
        boolean wasSanitized = !originalFile.equals(fileToScan);

        // Move to permanent storage
        Path permanentPath = storageService.getPermanentPath(doc, sha256);
        storageService.moveFile(fileForPermanentStorage, permanentPath);

        // Update document
        doc.setStatus(DocumentStatus.AVAILABLE);
        doc.setStoragePath(permanentPath.toString());

        // Add metadata about the scan
        Map<String, Object> meta = doc.getMeta();
        if (meta == null) {
            meta = new HashMap<>();
        }
        meta.put("scanCompleted", true);
        meta.put("wasSanitized", wasSanitized);
        meta.put("clearedAt", Instant.now().toString());

        if (wasSanitized) {
            meta.put("sanitizationApplied", true);
            meta.put("originalFileDeleted", true);
        }

        doc.setMeta(meta);

        documentRepository.save(doc);

        DocEventPayload payload = new DocEventPayload(doc.getId().toString(), DocumentStatus.AVAILABLE.toString());
        
        publish(CommonUtil.convertToJson(payload));
        // Clean up original file if we used a sanitized version
        if (wasSanitized && Files.exists(originalFile)) {
            try {
                Files.delete(originalFile);
            } catch (IOException e) {
                logger.warn("Failed to delete original file after sanitization: {}", originalFile, e);
            }
        }
    }

    private void cacheScanVerdict(String sha256, ClamResult scanResult) {
        if (properties.isCacheScanResults()) {
            ScanVerdict verdict;
            if (scanResult.isMalicious()) {
                verdict = ScanVerdict.malicious(scanResult.getSignature());
            } else {
                verdict = ScanVerdict.clean();
            }
            scanCache.put(sha256, verdict);
            logger.debug("Cached scan verdict for checksum {}: {}", sha256, verdict);
        }
    }

    private void cleanupTemporaryFile(Path originalFile, Path fileToScan) {
        // Clean up temporary sanitized file if it exists and is different from original
        if (!originalFile.equals(fileToScan) && Files.exists(fileToScan)) {
            try {
                Files.delete(fileToScan);
                logger.debug("Temporary sanitized file cleaned up: {}", fileToScan);
            } catch (IOException e) {
                logger.warn("Failed to clean up temporary file: {}", fileToScan, e);
            }
        }
    }

    private void handleScanProcessingError(Document doc, Path originalFile, Path fileToScan, Exception error) {
        try {
            // Update document status
            doc.setStatus(DocumentStatus.SCAN_FAILED);

            // Add error metadata
            Map<String, Object> meta = doc.getMeta();
            if (meta == null) {
                meta = new HashMap<>();
            }
            meta.put("scanError", true);
            meta.put("errorMessage", error.getMessage());
            meta.put("errorTime", Instant.now().toString());
            doc.setMeta(meta);

            documentRepository.save(doc);

            // Move to error quarantine
            Path errorPath = storageService.getErrorQuarantinePath(doc.getChecksum());
            try {
                storageService.moveFile(originalFile, errorPath);
                doc.setStoragePath(errorPath.toString());
                documentRepository.save(doc);
            } catch (IOException moveError) {
                logger.error("Failed to move file to error quarantine: {}", originalFile, moveError);
            }

        } finally {
            // Always clean up temporary files
            cleanupTemporaryFile(originalFile, fileToScan);
        }
        
        DocEventPayload payload = new DocEventPayload(doc.getId().toString(), DocumentStatus.SCAN_FAILED.toString());
        
        publish(CommonUtil.convertToJson(payload));
    }

    private void handleEncryptedPDF(UUID docId, String sha256, Path encryptedFile) throws IOException {

    }

    public ResponseEntity<Resource> handleDownload(String id) {
        try {
            Document document = getDocument(id);

            // Check availability
            if (document.getStatus() != DocumentStatus.AVAILABLE) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(null);
            }

            Path filePath = Paths.get(document.getStoragePath());
            if (!Files.exists(filePath)) {
                throw new DocumentNotFoundException("File not found in storage. ");
            }

            Resource resource = new FileSystemResource(filePath);

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + document.getOriginalName() + "\"")
                    .header(HttpHeaders.CONTENT_TYPE, document.getContentType())
                    .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(document.getSize()))
                    .body(resource);

        }catch (DocumentNotFoundException w){
            throw new DocumentNotFoundException(w.getMessage());
        }
        catch (Exception e) {
            throw new RuntimeException("Download failed for document: " + id, e);
        }
    }
    
    public void publish(String payload) {
    	kafkaProducer.sendMessage(docTopic, payload);
    }
    
}