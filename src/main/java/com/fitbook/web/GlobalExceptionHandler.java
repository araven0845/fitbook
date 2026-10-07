package com.fitbook.web;

import com.fitbook.exception.ApiException;
import com.fitbook.exception.UnauthorizedException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.stream.Collectors;

/**
 * One place that turns every failure into a standard HTTP status. Browsers (Accept: text/html) get
 * the error page, API clients get a JSON ApiError. Unexpected exceptions are logged server-side and
 * answered with a generic 500 - the client never sees a stack trace.
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public Object handleApi(ApiException ex, HttpServletRequest request) {
        if (ex instanceof UnauthorizedException && wantsHtml(request)) {
            return new ModelAndView("redirect:/login");
        }
        return respond(request, ex.getStatus(), ex.getMessage());
    }

    @ExceptionHandler(BindException.class)
    public Object handleValidation(BindException ex, HttpServletRequest request) {
        String details = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .distinct()
                .collect(Collectors.joining("; "));
        String message = details.isBlank() ? "Invalid input" : "Invalid input: " + details;
        return respond(request, HttpStatus.BAD_REQUEST, message);
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    public Object handleBadParameter(Exception ex, HttpServletRequest request) {
        return respond(request, HttpStatus.BAD_REQUEST, "Invalid or missing request parameter");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public Object handleNoResource(NoResourceFoundException ex, HttpServletRequest request) {
        return respond(request, HttpStatus.NOT_FOUND, "The requested page does not exist");
    }

    @ExceptionHandler(Exception.class)
    public Object handleUnexpected(Exception ex, HttpServletRequest request) {
        if (ex instanceof ErrorResponse standard) {
            HttpStatus status = HttpStatus.resolve(standard.getStatusCode().value());
            return respond(request, status != null ? status : HttpStatus.BAD_REQUEST, "The request could not be processed");
        }
        log.error("Unhandled exception for {} {}", request.getMethod(), request.getRequestURI(), ex);
        return respond(request, HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong on our side. Please try again.");
    }

    private Object respond(HttpServletRequest request, HttpStatusCode status, String message) {
        String reason = status instanceof HttpStatus httpStatus ? httpStatus.getReasonPhrase() : "Error";
        if (wantsHtml(request)) {
            ModelAndView mav = new ModelAndView("error");
            mav.setStatus(status);
            mav.addObject("status", status.value());
            mav.addObject("reason", reason);
            mav.addObject("message", message);
            return mav;
        }
        return ResponseEntity.status(status)
                .body(new ApiError(Instant.now(), status.value(), reason, message, request.getRequestURI()));
    }

    private boolean wantsHtml(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        return accept != null && accept.contains("text/html");
    }
}
