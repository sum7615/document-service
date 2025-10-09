package com.spxam.document_service.exception;

import com.spxam.document_service.dto.ExceptionResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

@ControllerAdvice
public class Handler {
    @ExceptionHandler(DocumentNotFoundException.class)
    public ResponseEntity<ExceptionResponse> handle(DocumentNotFoundException ex) {
        ExceptionResponse response = new ExceptionResponse(HttpStatus.NO_CONTENT.name(), ex.getMessage());
        return new ResponseEntity<>(response, HttpStatus.NO_CONTENT);
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ExceptionResponse>handle(RuntimeException ex) {
        ExceptionResponse response = new ExceptionResponse(HttpStatus.INTERNAL_SERVER_ERROR.name(), ex.getMessage());
        return new ResponseEntity<>(response, HttpStatus.INTERNAL_SERVER_ERROR);
    }

}
