package com.spxam.document_service.service;

import org.springframework.web.multipart.MultipartFile;

import com.spxam.document_service.entity.Document;

public interface DocumentService {

	Document storeFile(MultipartFile file, String uploadedBy);
}
