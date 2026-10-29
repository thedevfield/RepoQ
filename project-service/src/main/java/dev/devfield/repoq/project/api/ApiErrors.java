package dev.devfield.repoq.project.api;

import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(ApiProblem.class)
    ResponseEntity<Map<String, String>> problem(ApiProblem e) {
        return ResponseEntity.status(e.status()).body(Map.of("code", e.code()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, String>> malformed() {
        return ResponseEntity.badRequest().body(Map.of("code", "invalid_request"));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<Map<String, String>> invalidPath() {
        return ResponseEntity.badRequest().body(Map.of("code", "invalid_request"));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Map<String, String>> database() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("code", "project_unavailable"));
    }
}
