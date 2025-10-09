package com.spxam.document_service.controller;

import java.util.Map;

import lombok.AllArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.spxam.document_service.entity.Document;
import com.spxam.document_service.service.DocumentService;

@RestController
@AllArgsConstructor
public class DocumentController {

    private final DocumentService documentService;

    @GetMapping("/download")
    public ResponseEntity<?> download(@RequestParam("id") String id) {
       return  documentService.handleDownload(id);
    }
    @PostMapping("/upload")
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file,
                                    @RequestParam("tenantId") String tenantId,
                                    @RequestParam("uploadedBy") String uploadedBy) {
        try {
            Document doc = documentService.handleUpload(file, tenantId, uploadedBy);
            return ResponseEntity.accepted().body(Map.of("id", doc.getId(), "status", doc.getStatus()));
        } catch (Exception e) {
            return ResponseEntity.status(400).body(Map.of("error", e.getMessage()));
        }
    }

}
