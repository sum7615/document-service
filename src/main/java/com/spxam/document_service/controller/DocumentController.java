package com.spxam.document_service.controller;

import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import com.spxam.document_service.entity.Document;
import com.spxam.document_service.service.DocumentService;

import lombok.AllArgsConstructor;

@RestController
@AllArgsConstructor
public class DocumentController {

    private final DocumentService documentService;

    @GetMapping(value="/download", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<?> download(@RequestParam("id") String id) {
       return  documentService.handleDownload(id);
    }
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> upload(@RequestPart("file") MultipartFile file,
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
