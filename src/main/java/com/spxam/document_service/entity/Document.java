package com.spxam.document_service.entity;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.UpdateTimestamp;

import com.spxam.document_service.enums.DocumentStatus;
import com.vladmihalcea.hibernate.type.json.JsonBinaryType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Data;
@Entity
@Table(name = "documents",
        indexes = {
                @Index(name = "idx_documents_tenant_created", columnList = "tenant_id, created_at"),
                @Index(name = "idx_documents_checksum", columnList = "checksum"),
                @Index(name = "idx_documents_status", columnList = "status")
        })
@Data
public class Document {

    @Id
    // use generator if you prefer ULID
    private UUID id;

    @Column(nullable = false)
    private String tenantId;

    @Column(nullable = false)
    private String originalName;

    @Column(nullable = false, unique = true)
    private String storageName; // e.g. shard/uuid_filename

    @Column(nullable = false)
    private String contentType;

    @Column(nullable = false)
    private long size;

    private String extension;

    @Column(nullable = false)
    private String checksum;

    private String checksumAlgorithm;

    private String uploadedBy;

    private String uploadedVia;

    @Column(columnDefinition = "text")
    private String description;

    private boolean isPublic = false;

    @Column(columnDefinition = "jsonb")
    @Type(JsonBinaryType.class)
    private Map<String,Object> accessControl;

    @Column(columnDefinition = "jsonb")
    @Type(JsonBinaryType.class)
    private List<String> tags;

    @Version
    private Integer version;

    @Enumerated(EnumType.STRING)
    private DocumentStatus status = DocumentStatus.AVAILABLE;

    private Instant retentionTill;

    private boolean legalHold = false;

    @Column(nullable = false, unique = true)
    private String storagePath;

    private String storageNode;

    private String previewPath;
    private String ocrTextPath;

    @Column(columnDefinition = "jsonb")
    @Type(JsonBinaryType.class)
    private Map<String,Object> meta;

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

    private Instant deletedAt;
    private String deletedBy;
}
