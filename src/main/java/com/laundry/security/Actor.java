package com.laundry.security;

import com.laundry.common.error.BusinessException;
import com.laundry.common.error.ErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Ai đang gọi API: người dùng đăng nhập hoặc kiosk. */
public sealed interface Actor permits Actor.User, Actor.Kiosk {

    record User(UUID id, String email, Set<Role> roles) implements Actor {
        public boolean has(Role role) {
            return roles.contains(role);
        }
    }

    record Kiosk(UUID id, String code) implements Actor {
    }

    static Optional<Actor> current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof Actor actor ? Optional.of(actor) : Optional.empty();
    }

    static User user() {
        return current().filter(User.class::isInstance).map(User.class::cast)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
    }

    static UUID userId() {
        return user().id();
    }

    static Optional<UUID> optionalUserId() {
        return current().filter(User.class::isInstance).map(a -> ((User) a).id());
    }

    static Kiosk kiosk() {
        return current().filter(Kiosk.class::isInstance).map(Kiosk.class::cast)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
    }
}
