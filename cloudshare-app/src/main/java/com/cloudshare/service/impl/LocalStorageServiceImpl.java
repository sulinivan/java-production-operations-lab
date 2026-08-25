package com.cloudshare.service.impl;

import com.cloudshare.service.StorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

@Service
@ConditionalOnProperty(name = "storage.provider", havingValue = "LOCAL", matchIfMissing = true)
@Slf4j
public class LocalStorageServiceImpl implements StorageService {

    @Value("${storage.local.directory:./uploads}")
    private String uploadDir;

    private Path getValidatedPath(String filename) {
        Path baseDir = Paths.get(uploadDir).toAbsolutePath().normalize();
        Path targetPath = baseDir.resolve(filename).toAbsolutePath().normalize();

        // Enforce path traversal countermeasure
        if (!targetPath.startsWith(baseDir)) {
            log.error("Path traversal attack detected! Path resolved: {}", targetPath);
            throw new SecurityException("Directory traversal attempt detected");
        }
        return targetPath;
    }

    @Override
    public void store(String path, InputStream inputStream) throws IOException {
        Path targetPath = getValidatedPath(path);
        
        // Ensure parent directories exist
        Files.createDirectories(targetPath.getParent());
        
        log.debug("Storing file locally at: {}", targetPath);
        Files.copy(inputStream, targetPath, StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public InputStream retrieve(String path) throws IOException {
        Path targetPath = getValidatedPath(path);
        if (!Files.exists(targetPath)) {
            log.warn("Local file not found at: {}", targetPath);
            throw new FileNotFoundException("Local file not found: " + path);
        }
        log.debug("Retrieving local file from: {}", targetPath);
        return Files.newInputStream(targetPath);
    }

    @Override
    public void delete(String path) throws IOException {
        Path targetPath = getValidatedPath(path);
        log.debug("Deleting local file at: {}", targetPath);
        Files.deleteIfExists(targetPath);
    }
}
