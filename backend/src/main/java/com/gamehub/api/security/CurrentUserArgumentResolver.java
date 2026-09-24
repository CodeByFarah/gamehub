package com.gamehub.api.security;

import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.util.UUID;

/**
 * Resolves {@link CurrentUser} parameters from the security context.
 *
 * <p>Supports both the full principal and a bare UUID, because most handlers
 * only need the id and taking the whole record would obscure that.
 */
@Component
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentUser.class)
                && (UUID.class.equals(parameter.getParameterType())
                    || JwtService.AuthenticatedUser.class.equals(parameter.getParameterType()));
    }

    @Override
    public Object resolveArgument(MethodParameter parameter,
                                  ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest,
                                  WebDataBinderFactory binderFactory) {

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null
                || !(authentication.getPrincipal() instanceof JwtService.AuthenticatedUser user)) {
            // Reached only if an endpoint is annotated but left unprotected in
            // the filter chain, which is a configuration bug. Failing loudly
            // beats silently injecting null and handing the handler an
            // anonymous request it will treat as authenticated.
            throw new IllegalStateException(
                    "@CurrentUser used on an endpoint that is not authenticated: "
                            + parameter.getMethod());
        }

        return UUID.class.equals(parameter.getParameterType()) ? user.userId() : user;
    }
}
