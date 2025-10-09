package com.spxam.document_service.grpc;

import java.util.UUID;

import org.springframework.grpc.server.service.GrpcService;

import com.spxam.document_service.entity.Document;
import com.spxam.document_service.repository.DocumentRepository;

import document.DocumentServiceGrpc;
import document.DocumentServiceOuterClass.GetDocumentMetadataRequest;
import document.DocumentServiceOuterClass.GetDocumentMetadataResponse;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;

@GrpcService
@RequiredArgsConstructor
public class DocumentServiceGrpcImpl extends DocumentServiceGrpc.DocumentServiceImplBase {

    private final DocumentRepository repository;

    @Override
    public void getDocumentMetadata(GetDocumentMetadataRequest request,
                                    StreamObserver<GetDocumentMetadataResponse> responseObserver) {
        UUID id = UUID.fromString(request.getDocumentId());
        Document doc = repository.findById(id).orElseThrow();

        GetDocumentMetadataResponse response = GetDocumentMetadataResponse.newBuilder()
                .setDocumentId(doc.getId().toString())
                .setOriginalName(doc.getOriginalName())
                .setContentType(doc.getContentType())
                .setSize(doc.getSize())
                .setUploadedBy(doc.getUploadedBy())
                .setCreatedAt(doc.getCreatedAt().toString())
                .setScanStatus(doc.getScanStatus().name())
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
}
