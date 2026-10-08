package com.example.demo.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Requires the {@code X-API-Key} header on endpoints that change state or expose operations:
 * loading market data and the admin endpoints. Read-only queries stay open.
 *
 * <p>Fails closed: when no key is configured, the protected endpoints are refused rather than
 * left open.
 */
@Component
public class ApiKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-API-Key";

    private final byte[] expectedDigest;

    public ApiKeyFilter(@Value("${app.security.api-key:}") String apiKey) {
        this.expectedDigest = apiKey.isBlank() ? null : sha256(apiKey);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !(path.startsWith("/api/load/") || path.startsWith("/api/admin/"));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        if (expectedDigest == null) {
            reject(response, HttpStatus.SERVICE_UNAVAILABLE, "API key is not configured on the server");
            return;
        }
        String presented = request.getHeader(HEADER);
        // Comparing fixed-length digests in constant time leaks neither the key nor its length.
        if (presented == null || !MessageDigest.isEqual(expectedDigest, sha256(presented))) {
            reject(response, HttpStatus.UNAUTHORIZED, "Missing or invalid " + HEADER + " header");
            return;
        }
        chain.doFilter(request, response);
    }

    private static void reject(HttpServletResponse response, HttpStatus status, String message)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.TEXT_PLAIN_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(message);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
