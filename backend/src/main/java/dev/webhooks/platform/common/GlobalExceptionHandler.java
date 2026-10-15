package dev.webhooks.platform.common;

import java.net.URI;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Renders every error as {@code application/problem+json} with a stable {@code code} property.
 * Internal exception messages are never exposed.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> handleApi(ApiException ex) {
        ProblemDetail body = problem(ex.status(), ex.code(), ex.getMessage());
        if (!ex.errors().isEmpty()) {
            body.setProperty("errors", ex.errors());
        }
        ex.properties().forEach(body::setProperty);
        return ResponseEntity.status(ex.status()).body(body);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ProblemDetail> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        ProblemDetail body = problem(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER",
                "Parameter '" + ex.getName() + "' has an invalid value");
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.internalServerError()
                .body(problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred"));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<ApiException.FieldViolation> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> new ApiException.FieldViolation(e.getField(), e.getDefaultMessage()))
                .toList();
        ProblemDetail body = problem(HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_FAILED", "Request validation failed");
        body.setProperty("errors", errors);
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(body);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return ResponseEntity.badRequest()
                .body(problem(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Request body is not valid JSON for this operation"));
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        if (ex instanceof MissingRequestHeaderException missing) {
            body = problem(HttpStatus.BAD_REQUEST, "MISSING_HEADER", "Required header '" + missing.getHeaderName() + "' is missing");
            statusCode = HttpStatus.BAD_REQUEST;
        } else if (body instanceof ProblemDetail detail && (detail.getProperties() == null
                || !detail.getProperties().containsKey("code"))) {
            HttpStatus resolved = HttpStatus.resolve(statusCode.value());
            detail.setProperty("code", resolved != null ? resolved.name() : "ERROR");
        }
        return super.handleExceptionInternal(ex, body, headers, statusCode, request);
    }

    public static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setType(URI.create("urn:webhooks:error:" + code.toLowerCase()));
        body.setTitle(status.getReasonPhrase());
        body.setProperty("code", code);
        return body;
    }
}
