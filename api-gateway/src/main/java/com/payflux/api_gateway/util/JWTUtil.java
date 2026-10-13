package com.payflux.api_gateway.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import java.security.Key;

@Component
public class JWTUtil {
    private static Key getSigningKey(){
        String secret = System.getProperty("payflux.test.jwt.secret");
        if (secret == null || secret.isBlank()) {
            secret = System.getenv("JWT_SECRET");
        }
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("JWT_SECRET must be configured");
        }
        return Keys.hmacShaKeyFor(secret.getBytes());
    }

    public static Claims validateToken(String token){
        return Jwts.parserBuilder()
                .setSigningKey(getSigningKey())
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    public static String createTestToken(String email, String userId, String role, String secret) {
        return Jwts.builder()
                .setSubject(email)
                .claim("userId", userId)
                .claim("role", role)
                .signWith(Keys.hmacShaKeyFor(secret.getBytes()))
                .compact();
    }
}
