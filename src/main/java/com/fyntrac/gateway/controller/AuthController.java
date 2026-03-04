package com.fyntrac.gateway.controller;

import com.fyntrac.gateway.dto.SelectTenantRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.WebSession;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final WebClient webClient;
    private final ReactiveOAuth2AuthorizedClientService authorizedClientService;

    @Value("${fyntrac.cors.allowed-origins:http://localhost:3030}")
    private String frontendUrl;

    @Value("${fyntrac.dataloader.base-uri}")
    private String dataloaderBaseUri;

    public AuthController(WebClient.Builder webClientBuilder,
                          ReactiveOAuth2AuthorizedClientService authorizedClientService) {
        this.webClient = webClientBuilder.build();
        this.authorizedClientService = authorizedClientService;
    }

    /**
     * GET /auth/login
     * Redirects to Zitadel's hosted login page via Spring Security OAuth2.
     */
    @GetMapping("/login")
    public Mono<Void> login(org.springframework.web.server.ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.TEMPORARY_REDIRECT);
        exchange.getResponse().getHeaders().setLocation(URI.create("/oauth2/authorization/zitadel"));
        return exchange.getResponse().setComplete();
    }

    /**
     * GET /auth/session
     * Returns current session info. On first call after login, calls Zitadel's
     * userinfo endpoint to get email/name (not in ID token), then fetches
     * tenants from the dataloader service.
     */
    @GetMapping("/session")
    public Mono<ResponseEntity<Map<String, Object>>> getSession(
            @AuthenticationPrincipal OidcUser oidcUser,
            WebSession session) {

        if (oidcUser == null) {
            return Mono.just(ResponseEntity.ok(Map.of("authenticated", false)));
        }

        log.debug("OIDC Claims from Zitadel: {}", oidcUser.getClaims());

        // Try to get email from ID token first
        String email = oidcUser.getEmail();
        if (email == null) {
            Object emailClaim = oidcUser.getClaims().get("email");
            if (emailClaim != null) {
                email = emailClaim.toString();
            }
        }

        // If email is still null, call Zitadel's userinfo endpoint with access token
        if (email == null) {
            // Check session cache first
            String cachedEmail = (String) session.getAttributes().get("userinfo_email");
            if (cachedEmail != null) {
                log.debug("Using cached email from userinfo: {}", cachedEmail);
                return continueGetSession(oidcUser, session, cachedEmail);
            }

            // Look up the authorized client to get access token
            log.debug("Email not in ID token, looking up authorized client for subject: {}", oidcUser.getSubject());

            final OidcUser finalOidcUser = oidcUser;
            return authorizedClientService.loadAuthorizedClient("zitadel", oidcUser.getSubject())
                    .flatMap(authorizedClient -> {
                        String accessToken = authorizedClient.getAccessToken().getTokenValue();
                        log.debug("Got access token, calling userinfo endpoint");

                        return fetchUserInfoFromZitadel(accessToken)
                                .flatMap(userInfo -> {
                                    log.debug("Zitadel userinfo response: {}", userInfo);
                                    String fetchedEmail = (String) userInfo.get("email");
                                    String fetchedName = (String) userInfo.get("name");

                                    if (fetchedEmail != null) {
                                        session.getAttributes().put("userinfo_email", fetchedEmail);
                                    }
                                    if (fetchedName != null) {
                                        session.getAttributes().put("userinfo_name", fetchedName);
                                    }

                                    String identifier = fetchedEmail != null ? fetchedEmail
                                            : (finalOidcUser.getPreferredUsername() != null
                                            ? finalOidcUser.getPreferredUsername()
                                            : finalOidcUser.getSubject());

                                    return continueGetSession(finalOidcUser, session, identifier);
                                });
                    })
                    .onErrorResume(e -> {
                        log.error("Failed to fetch userinfo from Zitadel: {}", e.getMessage());
                        String fallbackId = finalOidcUser.getPreferredUsername() != null
                                ? finalOidcUser.getPreferredUsername()
                                : finalOidcUser.getSubject();
                        return continueGetSession(finalOidcUser, session, fallbackId);
                    })
                    .switchIfEmpty(Mono.defer(() -> {
                        log.warn("No authorized client found, falling back to subject");
                        return continueGetSession(finalOidcUser, session, finalOidcUser.getSubject());
                    }));
        }

        return continueGetSession(oidcUser, session, email);
    }

    /**
     * Calls Zitadel's userinfo endpoint directly with the access token.
     * This is needed because Zitadel does NOT include email/name in the ID token.
     */
    @SuppressWarnings("unchecked")
    private Mono<Map<String, Object>> fetchUserInfoFromZitadel(String accessToken) {
        String userInfoUri = "https://fyntrac-auth-q8clqw.us1.zitadel.cloud/oidc/v1/userinfo";
        log.debug("Calling Zitadel userinfo: {}", userInfoUri);
        return webClient.get()
                .uri(userInfoUri)
                .headers(h -> h.setBearerAuth(accessToken))
                .retrieve()
                .bodyToMono(Map.class)
                .map(m -> (Map<String, Object>) m);
    }

    /**
     * Continues getSession after resolving the user identifier (email).
     */
    private Mono<ResponseEntity<Map<String, Object>>> continueGetSession(
            OidcUser oidcUser, WebSession session, String userIdentifier) {

        log.debug("Session check for user: {}", userIdentifier);

        // 1. Check Cache first
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cachedTenants = (List<Map<String, Object>>) session.getAttributes().get("tenants");
        if (cachedTenants != null) {
            return Mono.just(ResponseEntity.ok(buildSessionResponse(oidcUser, session, cachedTenants, userIdentifier)));
        }

        // 2. Load the ID Token to relay it to the microservice
        String token = oidcUser.getIdToken().getTokenValue();

        Map<String, String> loginBody = new HashMap<>();
        loginBody.put("email", userIdentifier);

        return webClient.post()
                .uri(dataloaderBaseUri + "/fyntrac/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .headers(h -> h.setBearerAuth(token)) // <--- CRUCIAL: Add the token here
                .header("X-Tenant", "master")
                .header("Accept", "application/json")
                .bodyValue(loginBody)
                .retrieve()
                .bodyToMono(Map.class)
                .map(resp -> {
                    Object tenants = resp.get("tenants");
                    Object user = resp.get("user");
                    session.getAttributes().put("tenants", tenants);
                    session.getAttributes().put("user", user);

                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> tenantList = (List<Map<String, Object>>) tenants;
                    return ResponseEntity.ok(buildSessionResponse(oidcUser, session, tenantList, userIdentifier));
                })
                .onErrorResume(e -> {
                    log.error("Failed to fetch tenants for user: {}", userIdentifier, e);
                    Map<String, Object> result = buildSessionResponse(oidcUser, session, List.of(), userIdentifier);
                    result.put("tenantError", "Failed to load tenants: " + e.getMessage());
                    return Mono.just(ResponseEntity.ok(result));
                });
    }
    /**
     * POST /auth/select-tenant
     */
    @PostMapping("/select-tenant")
    public Mono<ResponseEntity<Map<String, Object>>> selectTenant(
            @RequestBody SelectTenantRequest request,
            @AuthenticationPrincipal OidcUser oidcUser,
            WebSession session) {

        if (oidcUser == null) {
            return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Not authenticated")));
        }

        session.getAttributes().put("selected_tenant", request.getTenantCode());

        Map<String, Object> result = new HashMap<>();
        result.put("status", "ok");
        result.put("tenantCode", request.getTenantCode());
        return Mono.just(ResponseEntity.ok(result));
    }

    /**
     * GET /auth/userinfo
     */
    @GetMapping("/userinfo")
    public Mono<ResponseEntity<Map<String, Object>>> getUserInfo(
            @AuthenticationPrincipal OidcUser oidcUser) {

        if (oidcUser == null) {
            return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Not authenticated")));
        }

        return Mono.just(ResponseEntity.ok(oidcUser.getClaims()));
    }

    // --- Helper ---

    private Map<String, Object> buildSessionResponse(
            OidcUser oidcUser, WebSession session, List<Map<String, Object>> tenants,
            String userIdentifier) {
        String name = oidcUser.getFullName();
        if (name == null) {
            name = (String) session.getAttributes().get("userinfo_name");
        }
        if (name == null) {
            name = userIdentifier;
        }

        Map<String, Object> result = new HashMap<>();
        result.put("authenticated", true);
        result.put("email", userIdentifier);
        result.put("name", name);
        result.put("preferred_username", oidcUser.getPreferredUsername());
        result.put("sub", oidcUser.getSubject());
        result.put("tenant", session.getAttributes().get("selected_tenant"));
        result.put("tenants", tenants);
        result.put("user", session.getAttributes().get("user"));
        return result;
    }
}
