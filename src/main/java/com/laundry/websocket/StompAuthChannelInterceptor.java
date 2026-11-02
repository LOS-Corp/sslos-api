package com.laundry.websocket;

import com.laundry.auth.security.JwtService;
import com.laundry.shared.security.CurrentUser;
import com.laundry.shared.security.Role;
import com.laundry.user.service.BranchScopeService;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Authenticates STOMP frames (NFR-07). CONNECT may be anonymous, but a presented token must be valid.
 * Subscriptions:
 * <ul>
 *   <li>{@code /topic/machines/**} — public machine status, same as {@code GET /api/machines}.</li>
 *   <li>{@code /topic/branches/{id}/**} — Owner/Admin, or Staff assigned to that branch.</li>
 *   <li>{@code /user/queue/**} — any authenticated user (own messages only, routed by Spring).</li>
 * </ul>
 * Everything else, including SEND to {@code /app/**}, is refused.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private static final Pattern BRANCH_TOPIC = Pattern.compile("^/topic/branches/([0-9a-fA-F-]{36})(/.*)?$");
    private static final String BEARER = "Bearer ";

    private final JwtService jwtService;
    private final BranchScopeService branchScope;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }
        StompCommand command = accessor.getCommand();
        if (command == StompCommand.CONNECT) {
            authenticate(accessor);
        } else if (command == StompCommand.SUBSCRIBE) {
            authorizeSubscription(accessor.getDestination(), currentUser(accessor.getUser()));
        } else if (command == StompCommand.SEND) {
            throw new MessageDeliveryException("Clients cannot send application messages");
        }
        return message;
    }

    private void authenticate(StompHeaderAccessor accessor) {
        String header = accessor.getFirstNativeHeader("Authorization");
        if (header == null || header.isBlank()) {
            return; // anonymous viewer of public machine status
        }
        if (!header.startsWith(BEARER)) {
            throw new MessageDeliveryException("Malformed Authorization header");
        }
        try {
            Claims claims = jwtService.parseToken(header.substring(BEARER.length()));
            List<?> roles = claims.get("roles", List.class);
            List<SimpleGrantedAuthority> authorities = (roles == null ? List.<Object>of() : roles).stream()
                    .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                    .toList();
            accessor.setUser(new UsernamePasswordAuthenticationToken(claims.getSubject(), null, authorities));
        } catch (RuntimeException e) {
            throw new MessageDeliveryException("Invalid or expired token");
        }
    }

    void authorizeSubscription(String destination, Optional<CurrentUser> user) {
        if (destination == null) {
            throw new MessageDeliveryException("Missing destination");
        }
        if (destination.equals("/topic/machines") || destination.startsWith("/topic/machines/")) {
            return;
        }
        if (destination.startsWith("/user/queue/") && user.isPresent()) {
            return;
        }
        Matcher branch = BRANCH_TOPIC.matcher(destination);
        if (branch.matches() && user.isPresent()
                && branchScope.canAccess(user.get(), UUID.fromString(branch.group(1)))) {
            return;
        }
        log.debug("Refused STOMP subscription to {}", destination);
        throw new MessageDeliveryException("Subscription not allowed: " + destination);
    }

    private static Optional<CurrentUser> currentUser(Principal principal) {
        if (!(principal instanceof UsernamePasswordAuthenticationToken token)) {
            return Optional.empty();
        }
        try {
            Set<Role> roles = token.getAuthorities().stream()
                    .map(a -> Role.parse(a.getAuthority()))
                    .flatMap(Optional::stream)
                    .collect(Collectors.toSet());
            return Optional.of(new CurrentUser(UUID.fromString(token.getName()), roles));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
