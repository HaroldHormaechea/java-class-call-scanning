package com.hhg.benchmark.exception;

public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) { super(message); }
    public NotFoundException(String entity, Long id) {
        super(entity + " not found with id: " + id);
    }
}
