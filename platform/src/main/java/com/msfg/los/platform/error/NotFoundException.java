package com.msfg.los.platform.error;
import org.springframework.http.HttpStatus;
public class NotFoundException extends DomainException {
    public NotFoundException(String entity, Object id) {
        super(HttpStatus.NOT_FOUND, "NOT_FOUND", entity + " not found: " + id);
    }
}
