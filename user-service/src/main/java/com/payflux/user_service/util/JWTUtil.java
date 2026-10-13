package com.payflux.user_service.util;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import java.security.Key;
import java.util.Date;
import java.util.Map;

@Component
public class JWTUtil {

    @org.springframework.beans.factory.annotation.Value("${jwt.secret}")
    private String secret;

    private Key getSigninKey(){
        String effectiveSecret = (secret != null && !secret.isBlank()) ? secret : System.getenv("JWT_SECRET");
        if (effectiveSecret == null || effectiveSecret.isBlank()) {
            throw new IllegalStateException("JWT_SECRET must be configured");
        }
        return Keys.hmacShaKeyFor(effectiveSecret.getBytes());
    }

    public String extractEmail(String token){
        return Jwts.parserBuilder()
                .setSigningKey(getSigninKey())
                .build()
                .parseClaimsJws(token)
                .getBody()
                .getSubject();
    }

    public boolean validateToken(String token, String username){
        try{
            String extractedUsername = extractEmail(token);
            return extractedUsername != null && extractedUsername.equals(username);
        }
        catch (Exception e){
            return false;
        }
    }


    public String extractUsername(String token){
        return Jwts.parserBuilder()
                .setSigningKey(getSigninKey())
                .build()
                .parseClaimsJws(token)
                .getBody()
                .getSubject();
    }

    public String generateToken(Map<String, Object> claims, String email){
        return Jwts.builder()
                .setClaims(claims)
                .setSubject(email)
                .setIssuedAt(new Date(System.currentTimeMillis()))
                .setExpiration(new Date(System.currentTimeMillis() + 86400000))
                .signWith(getSigninKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    public String extractRole(String token){
        return Jwts.parserBuilder()
                .setSigningKey(getSigninKey())
                .build()
                .parseClaimsJws(token)
                .getBody()
                .get("role").toString();
    }
}
