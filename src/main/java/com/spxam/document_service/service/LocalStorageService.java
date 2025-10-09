package com.spxam.document_service.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class LocalStorageService implements StorageService {

    @Value("${storage.local.path}")
    private String storagePath;

    @Override
    public String storeFile(MultipartFile file, UUID docId) throws IOException {
        String fileName = docId + "_" + file.getOriginalFilename();
        Path target = Paths.get(storagePath).resolve(fileName);
        Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
        return target.toString();
    }

    @Override
    public Resource loadFile(String filePath) {
        return new FileSystemResource(filePath);
    }

    @Override
    public boolean deleteFile(String filePath) {
        try {
            return Files.deleteIfExists(Paths.get(filePath));
        } catch (IOException e) {
            return false;
        }
    }

	@Override
	public String storeFile(com.spxam.document_service.service.MultipartFile file,
			com.spxam.document_service.service.UUID docId) throws com.spxam.document_service.service.IOException {
		// TODO Auto-generated method stub
		return null;
	}
}
