package com.spxam.document_service.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.spxam.document_service.dto.ScanVerdict;
import com.spxam.document_service.enums.DocumentStatus;
import com.spxam.document_service.repository.DocumentRepository;

import jakarta.annotation.PostConstruct;

@Service
public class ScanWorkerService {

    @Value("${document.scan.workers}")
    private int workerCount;

    @Value("${document.scan.archive-max-depth}")
    private int archiveMaxDepth;

    @Value("${document.scan.archive-max-uncompressed-mb}")
    private long archiveMaxMB;

    @Value("${document.retry.max-attempts}")
    private int maxRetries;

    @Value("${document.retry.backoff-ms}")
    private long retryBackoffMs;

    private final StorageService storageService;
    private final DocumentRepository documentRepository;
    private final ClamAvClient clamAvClient;
    private final ScanQueue scanQueue;
    private final RetryQueue retryQueue;
    private final DiskMonitorService diskMonitorService;
    private final Map<String, ScanVerdict> scanCache = new ConcurrentHashMap<>();
    private ExecutorService workerPool;

    public ScanWorkerService(StorageService storageService,
                             DocumentRepository documentRepository,
                             ClamAvClient clamAvClient,
                             ScanQueue scanQueue,
                             RetryQueue retryQueue,
                             DiskMonitorService diskMonitorService) {
        this.storageService = storageService;
        this.documentRepository = documentRepository;
        this.clamAvClient = clamAvClient;
        this.scanQueue = scanQueue;
        this.retryQueue = retryQueue;
        this.diskMonitorService = diskMonitorService;
    }

    @PostConstruct
    public void startWorkers() {
        workerPool = Executors.newFixedThreadPool(workerCount);
        for (int i = 0; i < workerCount; i++) workerPool.submit(this::processLoop);
        startRetryWorker();
    }

    private void processLoop() {
        while (true) {
            try {
                ScanJob job = scanQueue.take();
                if (diskMonitorService.isDiskLow()) {
                    scanQueue.publish(job.getDocId(), job.getSha256(), job.getType(), job.getPath());
                    Thread.sleep(1000);
                    continue;
                }
                try { process(job); } 
                catch (Exception e) {
                    System.err.println("Scan failed for doc " + job.getDocId() + ": " + e.getMessage());
                    retryQueue.publish(new RetryJob(job.getDocId(), job.getSha256(), job.getType(), job.getPath(), 1));
                }
            } catch (Exception e) { e.printStackTrace(); }
        }
    }

    private void startRetryWorker() {
        Executors.newSingleThreadExecutor().submit(() -> {
            while (true) {
                try {
                    RetryJob job = retryQueue.take();
                    if (job.getAttempt() > maxRetries) {
                        updateDocumentStatus(job.getDocId(), DocumentStatus.FAILED); continue;
                    }
                    try {
                        process(new ScanJob(job.getDocId(), job.getSha256(), job.getType(), job.getPath()));
                    } catch (Exception e) {
                        Thread.sleep(retryBackoffMs);
                        job.setAttempt(job.getAttempt() + 1);
                        retryQueue.publish(job);
                    }
                } catch (Exception e) { e.printStackTrace(); }
            }
        });
    }

    private void process(ScanJob job) throws IOException {
        Path filePath = Paths.get(job.getPath());
        ClamResult result = clamAvClient.scan(filePath);
        if (result.isMalicious()) { moveToQuarantine(job, filePath, result.getSignature()); return; }

        if (job.getType().equals("application/pdf")) filePath = sanitizePDF(filePath);
        if (job.getType().contains("officedocument") && hasMacro(filePath)) { moveToQuarantine(job, filePath, "Macro detected"); return; }
        if (job.getType().equals("application/zip")) scanArchive(filePath, 0);

        Path cleanPath = storageService.getCleanPath(job.getSha256());
        storageService.moveFile(filePath, cleanPath);
        scanCache.put(job.getSha256(), ScanVerdict.clean());
        updateDocumentStatus(job.getDocId(), DocumentStatus.AVAILABLE,cleanPath);
    }

    private void updateDocumentStatus(UUID docId, DocumentStatus status,Path path) {
        documentRepository.findById(docId).ifPresent(doc -> {
            doc.setStatus(status); doc.setStoragePath(path.toString());
            documentRepository.save(doc);
        });

    }
    private void moveToQuarantine(ScanJob job, Path filePath, String reason) throws IOException {
        Path quarantine = storageService.getQuarantinePath(job.getSha256());
        storageService.moveFile(filePath, quarantine);
        scanCache.put(job.getSha256(), ScanVerdict.malicious(reason));
        updateDocumentStatus(job.getDocId(), DocumentStatus.MALICIOUS);
    }

    private void updateDocumentStatus(UUID docId, DocumentStatus status) {
        documentRepository.findById(docId).ifPresent(doc -> {
            doc.setStatus(status); documentRepository.save(doc);
        });
    }
    
    Path sanitizePDF(Path pdfFile) throws IOException {
        try (PDDocument doc =Loader.loadPDF(pdfFile.toFile())) { // load is static
            doc.getDocumentCatalog().setOpenAction(null);
            doc.getDocumentCatalog().setAcroForm(null);

            Path sanitized = Files.createTempFile("sanitized", ".pdf");
            doc.save(sanitized.toFile());
            return sanitized;
        }
    }

    private boolean hasMacro(Path officeFile) { return false; }

    private void scanArchive(Path zipFile, int depth) throws IOException {
        if (depth > archiveMaxDepth) return;
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry; long totalUncompressed = 0;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                totalUncompressed += entry.getSize();
                if (totalUncompressed > archiveMaxMB * 1024 * 1024) throw new IOException("Zip too large");
                Path tempEntry = Files.createTempFile("zip-entry", ".tmp");
                Files.copy(zis, tempEntry, StandardCopyOption.REPLACE_EXISTING);
                String entryName = entry.getName().toLowerCase();
                if (entryName.endsWith(".zip") || entryName.endsWith(".tar") || entryName.endsWith(".tar.gz")) {
                    scanArchive(tempEntry, depth + 1);
                } else if (clamAvClient.scan(tempEntry).isMalicious()) {
                    throw new IOException("Malicious file in archive: " + entryName);
                }
                Files.deleteIfExists(tempEntry);
            }
        }
    }
}
