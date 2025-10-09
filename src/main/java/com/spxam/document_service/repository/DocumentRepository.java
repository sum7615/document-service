package com.spxam.document_service.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.spxam.document_service.entity.Document;

public interface DocumentRepository extends JpaRepository<Document, UUID> {
}
