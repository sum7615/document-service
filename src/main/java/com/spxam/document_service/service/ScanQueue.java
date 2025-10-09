package com.spxam.document_service.service;

import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.springframework.stereotype.Component;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Component
public class ScanQueue {
    private final BlockingQueue<ScanJob> queue = new LinkedBlockingQueue<>();
    public void publish(UUID docId, String sha256, String type, String path) { queue.offer(new ScanJob(docId, sha256, type, path)); }
    public ScanJob take() throws InterruptedException { return queue.take(); }
}

@Data @AllArgsConstructor @NoArgsConstructor
class ScanJob {
    private UUID docId;
    private String sha256;
    private String type;
    private String path;
}
