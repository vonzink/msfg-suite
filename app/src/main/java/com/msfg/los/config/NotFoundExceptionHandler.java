package com.msfg.los.config;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;

/**
 * Handles NoHandlerFoundException before the platform catch-all, returning 404.
 * Required because GlobalExceptionHandler's Exception catch-all would otherwise
 * return 500 for unmatched routes.
 */
@RestControllerAdvice
public class NotFoundExceptionHandler {

    @ExceptionHandler(NoHandlerFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public void handleNotFound() {
        // Return 404 with empty body — matched routes that don't exist
    }
}
