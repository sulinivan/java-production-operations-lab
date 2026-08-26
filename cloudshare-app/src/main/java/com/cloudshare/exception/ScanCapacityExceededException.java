package com.cloudshare.exception;

public class ScanCapacityExceededException extends RuntimeException {
    public ScanCapacityExceededException(String message) {
        super(message);
    }
}
