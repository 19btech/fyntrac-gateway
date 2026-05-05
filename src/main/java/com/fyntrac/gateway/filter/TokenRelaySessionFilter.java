package com.fyntrac.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Global gateway filter that extracts the OIDC ID Token from the
 * authenticated user's SecurityContext and adds it as the Authorization
 * header to downstream requests.
 *
 * This replaces the default TokenRelay filter because our downstream
 * services (Dataloader, Reporting) validate the ID Token, not the
 * Access Token (which is what TokenRelay sends by default).
 */
@Component
public class TokenRelaySessionFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(TokenRelaySessionFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return ReactiveSecurityContextHolder.getContext()
                .flatMap(securityContext -> {
                    if (securityContext.getAuthentication() instanceof OAuth2AuthenticationToken authToken
                            && authToken.getPrincipal() instanceof OidcUser oidcUser) {

                        String idToken = oidcUser.getIdToken().getTokenValue();
                        if (idToken != null) {
                            log.debug("Relaying ID token to downstream service for path: {}",
                                    exchange.getRequest().getPath());
                            ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                                    .headers(h -> h.setBearerAuth(idToken))
                                    .build();
                            return chain.filter(exchange.mutate().request(mutatedRequest).build());
                        }
                    }
                    return chain.filter(exchange);
                })
                .switchIfEmpty(chain.filter(exchange));
    }

    @Override
    public int getOrder() {
        // Run after TenantHeaderFilter (order 10)
        return 11;
    }
}
