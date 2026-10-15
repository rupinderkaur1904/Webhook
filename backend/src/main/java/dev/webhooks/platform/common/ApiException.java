package dev.webhooks.platform.common;

import java.util.List;
import org.springframework.http.HttpStatus;

/** A failure with a stable machine-readable {@code code}, rendered as RFC 9457 problem details. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final List<FieldViolation> errors;

    public ApiException(HttpStatus status, String code, String detail) {
        this(status, code, detail, List.of());
    }

    public ApiException(HttpStatus status, String code, String detail, List<FieldViolation> errors) {
        super(detail);
        this.status = status;
        this.code = code;
        this.errors = errors;
    }

    public static ApiException notFound(String what) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", what + " not found");
    }

    public static ApiException validation(String field, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_FAILED", "Request validation failed",
                List.of(new FieldViolation(field, message)));
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public List<FieldViolation> errors() {
        return errors;
    }

    public record FieldViolation(String field, String message) {}
}
