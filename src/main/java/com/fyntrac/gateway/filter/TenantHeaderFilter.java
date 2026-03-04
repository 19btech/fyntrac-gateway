package com.fyntrac.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebSession;
import reactor.core.publisher.Mono;

/**
 * Global gateway filter that reads the selected tenant from the
 * WebSession and adds the X-Tenant header to downstream requests.
 */
@Component
public class TenantHeaderFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(TenantHeaderFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return exchange.getSession()
                .flatMap(session -> {
                    String tenant = (String) session.getAttributes().get("selected_tenant");
                    if (tenant != null) {
                        log.debug("Adding X-Tenant header: {}", tenant);
                        ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                                .header("X-Tenant", tenant)
                                .build();
                        return chain.filter(exchange.mutate().request(mutatedRequest).build());
                    }
                    return chain.filter(exchange);
                });
    }

    @Override
    public int getOrder() {
        // Run after authentication but before routing
        return 10;
    }
}
