package com.spxam.document_service.service;

import java.nio.file.Path;

import org.springframework.stereotype.Component;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Component
public class ClamAvClient {
    public ClamResult scan(Path file) { return ClamResult.clean(); }
}

@Data @AllArgsConstructor @NoArgsConstructor
class ClamResult {
    private boolean malicious;
    private String signature;
    public static ClamResult clean() { return new ClamResult(false, null); }
    public static ClamResult malicious(String sig) { return new ClamResult(true, sig); }
}
