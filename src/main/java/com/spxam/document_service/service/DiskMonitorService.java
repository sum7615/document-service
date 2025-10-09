package com.spxam.document_service.service;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

@Service
public class DiskMonitorService {

    @Value("${document.storage.root}")
    private String rootPath;

    @Value("${document.storage.temp-ttl-hours}")
    private long tempTtlHours;

    @Value("${document.disk.low-threshold-mb}")
    private long diskThresholdMb;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    @PostConstruct
    public void init() {
        scheduler.scheduleAtFixedRate(this::cleanupTempFiles, 10, 10, TimeUnit.MINUTES);
    }

    public void cleanupTempFiles() {
        Path tmpDir = Paths.get(rootPath, "tmp");
        if (!Files.exists(tmpDir)) return;

        try (Stream<Path> files = Files.walk(tmpDir)) {
            long now = System.currentTimeMillis();
            files.filter(Files::isRegularFile).forEach(path -> {
                try {
                    FileTime lastModifiedTime = Files.getLastModifiedTime(path);
                    if (now - lastModifiedTime.toMillis() > TimeUnit.HOURS.toMillis(tempTtlHours)) {
                        Files.deleteIfExists(path);
                        System.out.println("Deleted temp file: " + path);
                    }
                } catch (IOException e) { e.printStackTrace(); }
            });
        } catch (IOException e) { e.printStackTrace(); }
    }

    public boolean isDiskLow() {
        try {
            FileStore store = Files.getFileStore(Paths.get(rootPath));
            long freeMB = store.getUsableSpace() / (1024 * 1024);
            return freeMB < diskThresholdMb;
        } catch (IOException e) { e.printStackTrace(); return true; }
    }
}
