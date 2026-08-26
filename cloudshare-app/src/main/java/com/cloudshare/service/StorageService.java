package com.cloudshare.service;

import java.io.IOException;
import java.io.InputStream;

public interface StorageService {
    void store(String path, InputStream inputStream) throws IOException;
    InputStream retrieve(String path) throws IOException;
    void delete(String path) throws IOException;
}
