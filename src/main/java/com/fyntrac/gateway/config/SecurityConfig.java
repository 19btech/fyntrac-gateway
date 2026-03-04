package com.fyntrac.gateway.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcReactiveOAuth2UserService;
import org.springframework.security.oauth2.client.oidc.web.server.logout.OidcClientInitiatedServerLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.userinfo.DefaultReactiveOAuth2UserService;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.RedirectServerAuthenticationSuccessHandler;
import org.springframework.security.web.server.authentication.logout.ServerLogoutSuccessHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import org.springframework.web.server.session.CookieWebSessionIdResolver;
import org.springframework.web.server.session.WebSessionIdResolver;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

        private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

        @Value("${fyntrac.cors.allowed-origins:http://localhost:3030}")
        private String allowedOrigins;

        @Bean
        public SecurityWebFilterChain springSecurityFilterChain(
                        ServerHttpSecurity http,
                        ReactiveClientRegistrationRepository clientRegistrationRepository) {

                http
                                .authorizeExchange(exchanges -> exchanges
                                                // Public endpoints
                                                .pathMatchers("/actuator/health", "/actuator/info").permitAll()
                                                // Auth login redirect — must be public
                                                .pathMatchers("/auth/login").permitAll()
                                                // OAuth2 login/callback endpoints
                                                .pathMatchers("/login/**", "/oauth2/**").permitAll()
                                                // API endpoints — permitAll for local test drivers
                                                // The gateway still relays tokens if present via
                                                // TokenRelaySessionFilter
                                                .pathMatchers("/api/**").permitAll()
                                                // Auth session/select-tenant need authentication context
                                                .pathMatchers("/auth/**").authenticated()
                                                // Everything else requires authentication
                                                .anyExchange().authenticated())
                                .oauth2Login(oauth2 -> {
                                        // After successful login, redirect to the frontend
                                        RedirectServerAuthenticationSuccessHandler successHandler = new RedirectServerAuthenticationSuccessHandler();
                                        successHandler.setLocation(java.net.URI.create(
                                                        allowedOrigins.split(",")[0].trim() + "?authenticated=true"));
                                        oauth2.authenticationSuccessHandler(successHandler);
                                })
                                .oauth2Client(oauth2Client -> {
                                })
                                .logout(logout -> logout
                                                .logoutUrl("/auth/logout")
                                                .logoutSuccessHandler(
                                                                oidcLogoutSuccessHandler(clientRegistrationRepository)))
                                .csrf(csrf -> csrf.disable())
                                // Disable CORS in security filter chain — handled by CorsWebFilter instead
                                .cors(cors -> cors.disable())
                                .exceptionHandling(exceptions -> exceptions
                                                .authenticationEntryPoint((exchange, ex) -> {
                                                        // For API calls, return 401 instead of redirect
                                                        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                                                        return exchange.getResponse().setComplete();
                                                }));

                return http.build();
        }

        private ServerLogoutSuccessHandler oidcLogoutSuccessHandler(
                        ReactiveClientRegistrationRepository clientRegistrationRepository) {
                OidcClientInitiatedServerLogoutSuccessHandler handler = new OidcClientInitiatedServerLogoutSuccessHandler(
                                clientRegistrationRepository);
                handler.setPostLogoutRedirectUri("{baseUrl}");
                return handler;
        }

        @Bean
        @Order(Ordered.HIGHEST_PRECEDENCE)
        public CorsWebFilter corsWebFilter() {
                return new CorsWebFilter(corsConfigurationSource());
        }

        @Bean
        public CorsConfigurationSource corsConfigurationSource() {
                CorsConfiguration config = new CorsConfiguration();
                config.setAllowedOrigins(Arrays.asList(allowedOrigins.split(",")));
                config.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
                config.setAllowedHeaders(List.of("*"));
                config.setAllowCredentials(true); // Required for cookies/session
                config.setMaxAge(3600L);

                UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
                source.registerCorsConfiguration("/**", config);
                return source;
        }

        /**
         * Configure the session cookie: SameSite=Lax ensures the browser
         * sends the cookie on cross-port requests (frontend 3030 → gateway 8585).
         */
        @Bean
        public WebSessionIdResolver webSessionIdResolver() {
                CookieWebSessionIdResolver resolver = new CookieWebSessionIdResolver();
                resolver.setCookieName("SESSION");
                resolver.addCookieInitializer(builder -> builder
                                .path("/")
                                .sameSite("Lax")
                                .httpOnly(true));
                return resolver;
        }
}
