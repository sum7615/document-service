package com.spxam.document_service.service;

import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.springframework.stereotype.Component;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Component
public class RetryQueue {
    private final BlockingQueue<RetryJob> queue = new LinkedBlockingQueue<>();
    public void publish(RetryJob job) { queue.offer(job); }
    public RetryJob take() throws InterruptedException { return queue.take(); }
}

@Data @AllArgsConstructor @NoArgsConstructor
class RetryJob {
    private UUID docId;
    private String sha256;
    private String type;
    private String path;
    private int attempt;
}
