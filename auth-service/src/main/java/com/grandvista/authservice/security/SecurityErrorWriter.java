package com.grandvista.authservice.security;

import com.grandvista.authservice.exception.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;

/**
 * Writes JSON 401/403 responses for security failures that happen before a controller is reached.
 * <ul>
 *   <li>missing token       -> 401 "Authentication required ..."</li>
 *   <li>expired token       -> 401 "JWT token has expired"</li>
 *   <li>malformed/bad sig   -> 401 "JWT token is invalid"</li>
 *   <li>valid token, wrong role -> 403</li>
 * </ul>
 */
@Component
public class SecurityErrorWriter implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final JsonMapper jsonMapper;

    public SecurityErrorWriter(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException exception) throws IOException {
        String message = describe(exception);
        response.setHeader("WWW-Authenticate", "Bearer");
        write(response, HttpStatus.UNAUTHORIZED, message, request.getRequestURI());
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException exception) throws IOException {
        write(response, HttpStatus.FORBIDDEN, "You do not have permission to access this resource",
                request.getRequestURI());
    }

    static String describe(AuthenticationException exception) {
        if (exception instanceof InvalidBearerTokenException) {
            String detail = String.valueOf(exception.getMessage()).toLowerCase();
            return detail.contains("expired") ? "JWT token has expired" : "JWT token is invalid or malformed";
        }
        return "Authentication required: provide a valid 'Authorization: Bearer <token>' header";
    }

    private void write(HttpServletResponse response, HttpStatus status, String message, String path) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        jsonMapper.writeValue(response.getOutputStream(),
                ApiError.of(status.value(), status.getReasonPhrase(), message, path));
    }
}
