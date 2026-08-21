package io.github.mdsedov.xmlexpander.web;

import java.io.IOException;
import java.time.Instant;

import javax.xml.stream.XMLStreamException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
class ApiExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiError> invalidRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(new ApiError(
                Instant.now(), exception.getMessage()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiError> uploadTooLarge(MaxUploadSizeExceededException exception) {
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).body(new ApiError(
                Instant.now(), "Файл превышает допустимый размер загрузки"));
    }

    @ExceptionHandler({IOException.class, XMLStreamException.class})
    ResponseEntity<ApiError> processingError(Exception exception) {
        LOGGER.warn("Cannot process XML request", exception);
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(new ApiError(
                Instant.now(), exception.getMessage() == null
                        ? "Не удалось обработать XML"
                        : exception.getMessage()));
    }

    record ApiError(Instant timestamp, String error) {
    }
}
