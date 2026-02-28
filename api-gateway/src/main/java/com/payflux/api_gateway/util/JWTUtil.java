package com.payflux.api_gateway.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import java.security.Key;

@Component
public class JWTUtil {
    private static final String SECRET = "secret123secret123secret123secret123secret123secret123secret123secret123secret123";

    private static Key getSigningKey(){
        return Keys.hmacShaKeyFor(SECRET.getBytes());
    }

    public static Claims validateToken(String token){
        return Jwts.parserBuilder()
                .setSigningKey(getSigningKey())
                .build()
                .parseClaimsJws(token)
                .getBody();
    }
}
