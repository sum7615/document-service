package com.spxam.document_service.exception;

public class DocumentNotFoundException extends RuntimeException{

    private static final long serialVersionUID = -7650051584941338137L;

	public DocumentNotFoundException(String message) {
        super(message);
    }

}
