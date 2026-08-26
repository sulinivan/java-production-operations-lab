package com.cloudshare.service.impl;

import com.cloudshare.service.StorageService;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;

@Service
@ConditionalOnProperty(name = "storage.provider", havingValue = "MINIO")
@RequiredArgsConstructor
@Slf4j
public class MinIoStorageServiceImpl implements StorageService {

    private final MinioClient minioClient;

    @Value("${storage.minio.bucket-name:cloudshare-bucket}")
    private String bucketName;

    @PostConstruct
    public void init() {
        try {
            log.info("Checking if MinIO bucket '{}' exists", bucketName);
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build());
            if (!exists) {
                log.info("Creating MinIO bucket '{}'", bucketName);
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
            }
        } catch (Exception e) {
            log.error("Failed to initialize MinIO bucket '{}'", bucketName, e);
            throw new RuntimeException("Could not initialize MinIO storage bucket", e);
        }
    }

    @Override
    public void store(String path, InputStream inputStream) throws IOException {
        try {
            log.debug("Uploading object to MinIO bucket '{}': {}", bucketName, path);
            // Size of stream is unknown beforehand; stream with -1 length and partSize of 10MB
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucketName)
                            .object(path)
                            .stream(inputStream, -1L, 10485760L) // 10MB part size
                            .build()
            );
        } catch (Exception e) {
            log.error("MinIO object upload failed: {}", path, e);
            throw new IOException("Failed to write object to MinIO storage", e);
        }
    }

    @Override
    public InputStream retrieve(String path) throws IOException {
        try {
            log.debug("Retrieving object from MinIO bucket '{}': {}", bucketName, path);
            return minioClient.getObject(
                    GetObjectArgs.builder()
                            .bucket(bucketName)
                            .object(path)
                            .build()
            );
        } catch (Exception e) {
            log.error("MinIO object retrieval failed: {}", path, e);
            throw new IOException("Failed to retrieve object from MinIO storage", e);
        }
    }

    @Override
    public void delete(String path) throws IOException {
        try {
            log.debug("Removing object from MinIO bucket '{}': {}", bucketName, path);
            minioClient.removeObject(
                    RemoveObjectArgs.builder()
                            .bucket(bucketName)
                            .object(path)
                            .build()
            );
        } catch (Exception e) {
            log.error("MinIO object deletion failed: {}", path, e);
            throw new IOException("Failed to delete object from MinIO storage", e);
        }
    }
}
