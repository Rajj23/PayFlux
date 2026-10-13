package com.payflux.api_gateway.filters;

import com.payflux.api_gateway.util.JWTUtil;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mockStatic;

class JwtAuthFilterTest {

    private final JwtAuthFilter filter = new JwtAuthFilter();

    @Test
    void allowsOnlySignupAndLoginPostsWithoutToken() {
        for (String path : new String[]{"/auth/signup", "/auth/login"}) {
            GatewayFilterChain chain = mock(GatewayFilterChain.class);
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.post(path).build());
            when(chain.filter(exchange)).thenReturn(Mono.empty());

            filter.filter(exchange, chain).block();

            verify(chain).filter(exchange);
        }
    }

    @Test
    void rejectsProtectedRouteWithoutToken() {
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/wallets/42").build());

        filter.filter(exchange, chain).block();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        verify(chain, never()).filter(exchange);
    }

    @Test
    void rejectsWalletCreditsEvenWhenCallerHasValidToken() {
        Claims claims = claims("42", "ROLE_USER");
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/wallets/credit")
                        .header("Authorization", "Bearer valid-test-token")
                        .build());

        try (var jwtUtil = mockStatic(JWTUtil.class)) {
            jwtUtil.when(() -> JWTUtil.validateToken("valid-test-token")).thenReturn(claims);
            filter.filter(exchange, chain).block();
        }

        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
        verify(chain, never()).filter(org.mockito.ArgumentMatchers.any(ServerWebExchange.class));
    }

    @Test
    void rejectsRequestsForAnotherUsersData() {
        Claims claims = claims("42", "ROLE_USER");
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/notifications/99")
                        .header("Authorization", "Bearer valid-test-token")
                        .build());

        try (var jwtUtil = mockStatic(JWTUtil.class)) {
            jwtUtil.when(() -> JWTUtil.validateToken("valid-test-token")).thenReturn(claims);
            filter.filter(exchange, chain).block();
        }

        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
        verify(chain, never()).filter(org.mockito.ArgumentMatchers.any(ServerWebExchange.class));
    }

    @Test
    void rejectsPrometheusMetricsThroughGateway() {
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/actuator/prometheus").build());

        filter.filter(exchange, chain).block();

        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
        verify(chain, never()).filter(exchange);
    }

    @Test
    void validatesTokenAndOverwritesUntrustedIdentityHeaders() {
        String token = "valid-test-token";
        Claims claims = claims("42", "ROLE_USER");

        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/transactions/user/42")
                        .header("Authorization", "Bearer " + token)
                        .header("X-User-Id", "1")
                        .header("X-User-Role", "ROLE_ADMIN")
                        .header("X-User-Email", "attacker@example.com")
                        .build());
        when(chain.filter(org.mockito.ArgumentMatchers.any(ServerWebExchange.class))).thenReturn(Mono.empty());

        try (var jwtUtil = mockStatic(JWTUtil.class)) {
            jwtUtil.when(() -> JWTUtil.validateToken(token)).thenReturn(claims);
            filter.filter(exchange, chain).block();
        }

        var forwarded = org.mockito.ArgumentCaptor.forClass(
                ServerWebExchange.class);
        verify(chain).filter(forwarded.capture());
        assertEquals("42", forwarded.getValue().getRequest().getHeaders().getFirst("X-User-Id"));
        assertEquals("ROLE_USER", forwarded.getValue().getRequest().getHeaders().getFirst("X-User-Role"));
        assertEquals("alice@example.com", forwarded.getValue().getRequest().getHeaders().getFirst("X-User-Email"));
    }

    @Test
    void permitsOptionsPreflightWithoutToken() {
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.method(HttpMethod.OPTIONS, "/api/v1/wallets/42").build());
        when(chain.filter(exchange)).thenReturn(Mono.empty());

        filter.filter(exchange, chain).block();

        verify(chain).filter(exchange);
    }

    private Claims claims(String userId, String role) {
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn("alice@example.com");
        when(claims.get("userId", String.class)).thenReturn(userId);
        when(claims.get("role", String.class)).thenReturn(role);
        return claims;
    }
}