package dev.devfield.repoq.project.api;

import org.springframework.http.HttpStatus;

public class ApiProblem extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public ApiProblem(HttpStatus status, String code) {
        super(code);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
}
