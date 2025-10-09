package com.spxam.document_service.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

import com.spxam.document_service.entity.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
@Service
public class StorageService {

    @Value("${document.storage.root}")
    private String rootPath;

    @Value("${document.storage.clean}")
    private String cleanDir;

    @Value("${document.storage.quarantine}")
    private String quarantineDir;

    public Path getTempPath(UUID docId) {
        return Paths.get(rootPath, "tmp", docId.toString());
    }
    public Path getPermanentPath(Document doc, String checksum) {
        // Use the existing storage path generation logic
        String fileName = doc.getId().toString() +
                (doc.getExtension() != null && !doc.getExtension().isEmpty() ?
                        "." + doc.getExtension() : "");

        return Paths.get(cleanDir,
                doc.getTenantId(),
                doc.getId().toString().substring(0, 2), // sharding
                fileName);
    }


    public Path getErrorQuarantinePath(String checksum) {
        return Paths.get(quarantineDir,
                "errors",
                checksum.substring(0, 2),
                checksum + ".error");
    }

    public Path getCleanPath(String sha256) {
        String shard = sha256.substring(0, 2) + "/" + sha256.substring(2, 4);
        return Paths.get(rootPath, cleanDir, shard, sha256);
    }

    public Path getQuarantinePath(String sha256) {
        String shard = sha256.substring(0, 2) + "/" + sha256.substring(2, 4);
        return Paths.get(rootPath, quarantineDir, shard, sha256);
    }

    public void moveFile(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

    public void deleteFile(Path path) throws IOException {
        Files.deleteIfExists(path);
    }
}
