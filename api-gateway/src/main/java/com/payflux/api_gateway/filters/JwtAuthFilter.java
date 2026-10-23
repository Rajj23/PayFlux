package com.payflux.api_gateway.filters;

import com.payflux.api_gateway.util.JWTUtil;
import io.jsonwebtoken.Claims;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class JwtAuthFilter implements GlobalFilter, Ordered {

    private boolean isPublicPath(String path) {
        return "/auth/login".equals(path) || "/auth/signup".equals(path);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (exchange.getRequest().getMethod() == HttpMethod.OPTIONS) {
            return chain.filter(exchange);
        }

        String path = exchange.getRequest().getPath().value();
        String normalizedPath = path.replaceAll("/+$","");

        if (normalizedPath.equals("/actuator/health")
                || normalizedPath.startsWith("/actuator/health/")) {
            return chain.filter(exchange);
        }

        if (isPublicPath(normalizedPath)
                && exchange.getRequest().getMethod() == HttpMethod.POST) {
            return chain.filter(exchange);
        }

        // Prometheus is scraped only inside the cluster by the configured
        // ServiceMonitor; do not expose metrics through the public ALB.
        if (normalizedPath.equals("/actuator/prometheus")) {
            exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
            return exchange.getResponse().setComplete();
        }

        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);

        if(authHeader==null || !authHeader.startsWith("Bearer ")){
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }

        try{
            String token = authHeader.substring(7);
            Claims claims = JWTUtil.validateToken(token);
            String userId = claims.get("userId", String.class);
            String role = claims.get("role", String.class);
            if (claims.getSubject() == null || userId == null || role == null) {
                throw new IllegalArgumentException("JWT is missing required identity claims");
            }
            if (!isAuthorized(normalizedPath, exchange.getRequest().getMethod(), userId, role)) {
                exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                return exchange.getResponse().setComplete();
            }

            ServerWebExchange authenticatedExchange = exchange.mutate()
                    .request(exchange.getRequest().mutate()
                            .headers(headers -> {
                                headers.remove("X-User-Email");
                                headers.remove("X-User-Id");
                                headers.remove("X-User-Role");
                                headers.set("X-User-Email", claims.getSubject());
                                headers.set("X-User-Id", userId);
                                headers.set("X-User-Role", role);
                            })
                            .build())
                    .build();

            return chain.filter(authenticatedExchange);
        }catch (Exception e){
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }

    }

    private boolean isAuthorized(String path, HttpMethod method, String userId, String role) {
        boolean admin = "ROLE_ADMIN".equals(role);

        // Wallet writes and raw wallet creation are internal operations. In
        // particular, never expose a client-controlled balance credit endpoint.
        if (path.equals("/api/v1/wallets") || path.startsWith("/api/v1/wallets/")) {
            if (method != HttpMethod.GET) {
                return false;
            }
            return admin || path.equals("/api/v1/wallets/" + userId);
        }

        if (path.equals("/api/transactions/all") || path.equals("/api/rewards")) {
            return admin;
        }

        if (path.equals("/api/transactions/create")) {
            return method == HttpMethod.POST;
        }

        if (path.startsWith("/api/transactions/user/")) {
            return admin || path.equals("/api/transactions/user/" + userId);
        }

        if (path.startsWith("/api/notifications/")) {
            return method == HttpMethod.GET
                    && (admin || path.equals("/api/notifications/" + userId));
        }
        if (path.equals("/api/notifications")) {
            return false;
        }

        if (path.startsWith("/api/rewards/user/")) {
            return method == HttpMethod.GET
                    && (admin || path.equals("/api/rewards/user/" + userId));
        }

        if (path.equals("/api/users")) {
            return admin;
        }
        if (path.equals("/api/users/all")) {
            return admin;
        }
        if (path.startsWith("/api/users/")) {
            String targetUserId = path.substring("/api/users/".length());
            if ("search".equals(targetUserId)) {
                return method == HttpMethod.GET;
            }
            if (HttpMethod.PUT.equals(method) && targetUserId.equals(userId)) {
                return true;
            }
            return admin || targetUserId.equals(userId);
        }

        // Unknown paths must not become implicitly authorized.
        return false;
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
