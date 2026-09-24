package com.gamehub.api.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Reads the bearer token and populates the security context.
 *
 * <p>Extends OncePerRequestFilter rather than implementing Filter. A plain
 * filter runs again on every internal forward, so an error dispatch would
 * re-parse the token and, more importantly, re-run any side effects.
 *
 * <p>The filter never rejects a request. An absent or invalid token simply
 * leaves the context unauthenticated, and the authorisation rules in
 * {@link SecurityConfig} decide whether that is acceptable for the path. That
 * separation is what lets one filter serve both public and protected
 * endpoints without knowing which is which.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtService jwtService;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain)
            throws ServletException, IOException {

        extractToken(request)
                .flatMap(jwtService::verifyAccessToken)
                .ifPresent(user -> authenticate(user, request));

        chain.doFilter(request, response);
    }

    private void authenticate(JwtService.AuthenticatedUser user, HttpServletRequest request) {
        var authorities = user.roles().stream()
                .map(SimpleGrantedAuthority::new)
                .toList();

        // The principal is the typed record, not a bare string. Controllers
        // resolve it through CurrentUserArgumentResolver and therefore never
        // parse a user id out of a token themselves.
        var authentication = new UsernamePasswordAuthenticationToken(user, null, authorities);
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private java.util.Optional<String> extractToken(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        if (header == null || !header.startsWith(PREFIX)) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(header.substring(PREFIX.length()).trim());
    }

    /**
     * Skips the filter for paths that can never be authenticated anyway.
     *
     * <p>Saves a signature verification per health check, which matters when
     * a load balancer polls readiness every second across many instances.
     */
    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator/health")
                || path.startsWith("/v3/api-docs")
                || path.startsWith("/swagger-ui");
    }
}
