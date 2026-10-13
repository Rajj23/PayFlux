package com.payflux.user_service.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class JWTUtilTest {

    private JWTUtil jwtUtil;

    @BeforeEach
    void setUp() {
        jwtUtil = new JWTUtil();
        ReflectionTestUtils.setField(jwtUtil, "secret", "ci-only-secret-key-that-is-at-least-32-bytes-long");
    }

    @Test
    void testGenerateAndExtractToken() {
        Map<String, Object> claims = new HashMap<>();
        claims.put("role", "ROLE_USER");
        claims.put("userId", "10");

        String token = jwtUtil.generateToken(claims, "test@test.com");
        assertNotNull(token);

        String extractedUsername = jwtUtil.extractUsername(token);
        assertEquals("test@test.com", extractedUsername);

        assertEquals("ROLE_USER", jwtUtil.extractRole(token));
        // userId isn't extracted by any public method in JWTUtil in the current
        // implementation
        // so we'll just test what's available
    }

    @Test
    void testValidateToken() {
        Map<String, Object> claims = new HashMap<>();
        String token = jwtUtil.generateToken(claims, "test@test.com");

        assertTrue(jwtUtil.validateToken(token, "test@test.com"));
        assertFalse(jwtUtil.validateToken(token, "wrong@test.com"));
    }
}
