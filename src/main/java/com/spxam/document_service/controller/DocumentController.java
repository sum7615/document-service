package com.spxam.document_service.controller;

import java.util.Map;
import java.util.UUID;

import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.spxam.document_service.entity.Document;
import com.spxam.document_service.repository.DocumentRepository;
import com.spxam.document_service.service.DocumentService;
import com.spxam.document_service.service.StorageService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;
    private final DocumentRepository repository;
    private final StorageService storageService;

    @PostMapping
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file,
                                    @RequestParam("uploadedBy") String uploadedBy) {
        Document doc = documentService.storeFile(file, uploadedBy);
        return ResponseEntity.ok(Map.of(
                "id", doc.getId(),
                "downloadUrl", "/documents/" + doc.getId() + "/download",
                "viewUrl", "/documents/" + doc.getId() + "/view"
        ));
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable UUID id) {
        Document doc = repository.findById(id).orElseThrow();
        Resource resource = storageService.loadFile(doc.getStoragePath());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(doc.getContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + doc.getOriginalName() + "\"")
                .body(resource);
    }

    @GetMapping("/{id}/view")
    public ResponseEntity<Resource> view(@PathVariable UUID id) {
        Document doc = repository.findById(id).orElseThrow();
        Resource resource = storageService.loadFile(doc.getStoragePath());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(doc.getContentType()))
                .body(resource);
    }
}
