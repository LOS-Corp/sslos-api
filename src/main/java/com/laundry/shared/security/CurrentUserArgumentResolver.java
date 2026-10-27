package com.laundry.shared.security;

import org.springframework.core.MethodParameter;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Resolves a {@link CurrentUser} controller parameter from the security context (JWT subject = user UUID). */
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == CurrentUser.class;
    }

    @Override
    public CurrentUser resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                       NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        return current().orElseThrow(() ->
                new AuthenticationCredentialsNotFoundException("Authentication required"));
    }

    /** The caller of the current request, if authenticated with a user id. */
    public static Optional<CurrentUser> current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return Optional.empty();
        }
        UUID id;
        try {
            id = UUID.fromString(auth.getName());
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        Set<Role> roles = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .map(Role::parse)
                .flatMap(Optional::stream)
                .collect(Collectors.toSet());
        return Optional.of(new CurrentUser(id, roles));
    }
}
