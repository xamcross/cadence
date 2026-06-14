package com.cadence.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** Maps auth exceptions to the {error,message} envelope — never leaking PII/tokens/stack traces. */
@RestControllerAdvice
public class AuthExceptionHandler {

    private static ResponseEntity<Map<String, String>> body(HttpStatus status, String error, String message) {
        return ResponseEntity.status(status).body(Map.of("error", error, "message", message));
    }

    @ExceptionHandler(AuthExceptions.InvalidLinkException.class)
    public ResponseEntity<Map<String, String>> invalidLink(AuthExceptions.InvalidLinkException e) {
        return body(HttpStatus.GONE, "link_invalid",
            "This link is no longer valid. Ask your administrator to resend.");
    }

    @ExceptionHandler(AuthExceptions.WeakPasswordException.class)
    public ResponseEntity<Map<String, String>> weakPassword(AuthExceptions.WeakPasswordException e) {
        return body(HttpStatus.BAD_REQUEST, "weak_password",
            "Password must be at least 8 characters.");
    }

    @ExceptionHandler(AuthExceptions.AlreadyMemberException.class)
    public ResponseEntity<Map<String, String>> alreadyMember(AuthExceptions.AlreadyMemberException e) {
        return body(HttpStatus.CONFLICT, "already_member", "This person is already a member.");
    }

    @ExceptionHandler(AuthExceptions.RateLimitedException.class)
    public ResponseEntity<Map<String, String>> rateLimited(AuthExceptions.RateLimitedException e) {
        return body(HttpStatus.TOO_MANY_REQUESTS, "rate_limited", "Too many attempts. Try again later.");
    }
}
