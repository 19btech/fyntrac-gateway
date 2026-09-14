# Fyntrac Gateway - AI Agent Guidelines

## Architecture Overview
This is a Spring Cloud Gateway application serving as the API gateway for the Fyntrac microservices platform. It handles:
- OAuth2 OIDC authentication via Zitadel
- Multi-tenant request routing with session-based tenant selection
- Token relay using OIDC ID tokens (not access tokens) to downstream services
- CORS configuration for frontend integration

Key components:
- `SecurityConfig.java`: WebFlux security with OAuth2 login, CORS, and path-based authorization
- `AuthController.java`: Handles login redirects, session management, tenant fetching from dataloader service
- `TenantHeaderFilter.java`: Global filter adding X-Tenant header from session to downstream requests
- `TokenRelaySessionFilter.java`: Custom token relay filter sending OIDC ID tokens as Bearer auth

## Critical Workflows
- **Build**: `./gradlew build` (compiles and tests)
- **Run locally**: `./gradlew bootRun` (starts on port 8585)
- **Docker build**: `./gradlew dockerBuildImage` (requires GIT_USER/GIT_KEY env vars for GHCR push)
- **Debug auth**: Check logs for OIDC claims, userinfo calls, and tenant loading from dataloader service

## Project-Specific Patterns
- **Reactive programming**: Use Mono/Flux for all async operations, avoid blocking calls
- **Session storage**: Store user data (tenants, selected_tenant) in WebSession attributes
- **Tenant handling**: Always include X-Tenant header for service calls; default to "master" for auth endpoints
- **Token relay**: Downstream services expect OIDC ID tokens, not access tokens (custom filter replaces default TokenRelay)
- **Error handling**: Use onErrorResume for reactive error handling, log errors with user context
- **CORS**: Handled via CorsWebFilter, not security filter chain (disabled in security config)
- **Configuration**: Use application.properties with ${ENV_VAR} placeholders for external config

## Integration Points
- **Zitadel OIDC**: Issuer URI, client ID/secret, project ID for audience scope
- **Dataloader service**: Fetches user tenants via POST /fyntrac/auth/login with Bearer token and X-Tenant: master
- **Downstream routes**: /api/dataloader/** → dataloader, /api/reporting/** → reporting, /api/dsl/** → dsl, /api/insight/** → insight
- **Frontend**: Configured CORS origins, post-login redirect with ?authenticated=true

## Key Files
- `src/main/java/com/fyntrac/gateway/FyntracGatewayApplication.java`: Main app class
- `src/main/java/com/fyntrac/gateway/config/SecurityConfig.java`: Security and CORS config
- `src/main/java/com/fyntrac/gateway/controller/AuthController.java`: Auth endpoints and session logic
- `src/main/java/com/fyntrac/gateway/filter/`: Custom global filters for tenant/token relay
- `src/main/resources/application.properties`: Routing, OAuth2, and service URIs
- `build.gradle`: Dependencies (Spring Cloud Gateway, WebFlux, OAuth2 client)</content>
<parameter name="filePath">/home/uabbas/Workspace/fyntrac-gateway/AGENTS.md
