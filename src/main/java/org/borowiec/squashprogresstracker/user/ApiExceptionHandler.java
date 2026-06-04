package org.borowiec.squashprogresstracker.user;

import org.borowiec.squashprogresstracker.llm.client.LlmException;
import org.borowiec.squashprogresstracker.match.MatchNotFoundException;
import org.borowiec.squashprogresstracker.match.gameplan.GamePlanUnavailableException;
import org.borowiec.squashprogresstracker.user.dto.ApiError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(LlmException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public ApiError handleLlmException(LlmException ex) {
        log.warn("LLM call failed, providerStatus={}", ex.providerStatus(), ex);
        return ApiError.of(503, "AI service is temporarily unavailable");
    }


    @ExceptionHandler(GamePlanUnavailableException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiError handleGamePlanUnavailable(GamePlanUnavailableException ex) {
        return ApiError.of(404, "No match history for that opponent");
    }

    @ExceptionHandler(MatchNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiError handleMatchNotFound(MatchNotFoundException ex) {
        return ApiError.of(404, "Match not found");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError handleValidation(MethodArgumentNotValidException ex) {
        var fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        FieldError::getField,
                        fe -> fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "invalid",
                        (a, b) -> a
                ));
        return ApiError.ofFields(400, "Validation failed", fieldErrors);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiError handleDuplicateEmail(DataIntegrityViolationException ex) {
        return ApiError.of(409, "Email already registered");
    }

    @ExceptionHandler(BadCredentialsException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ApiError handleBadCredentials(BadCredentialsException ex) {
        return ApiError.of(401, "Invalid email or password");
    }

    @ExceptionHandler(DuplicateEmailException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiError handleDuplicateEmailExplicit(DuplicateEmailException ex) {
        return ApiError.of(409, ex.getMessage());
    }
}
