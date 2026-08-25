package com.cloudshare.service;

import lombok.extern.slf4j.Slf4j;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
class DeleteOnCloseInputStream extends InputStream {
    private final InputStream delegate;
    private final Path fileToDelete;

    public DeleteOnCloseInputStream(InputStream delegate, Path fileToDelete) {
        this.delegate = delegate;
        this.fileToDelete = fileToDelete;
    }

    @Override
    public int read() throws IOException {
        return delegate.read();
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        return delegate.read(b, off, len);
    }

    @Override
    public void close() throws IOException {
        try {
            delegate.close();
        } finally {
            Files.deleteIfExists(fileToDelete);
            log.debug("Temporary decrypted download file deleted: {}", fileToDelete);
        }
    }
}
