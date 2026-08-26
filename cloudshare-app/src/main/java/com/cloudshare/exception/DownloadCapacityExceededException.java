package com.cloudshare.exception;

public class DownloadCapacityExceededException extends RuntimeException {
    public DownloadCapacityExceededException(String message) {
        super(message);
    }
}
