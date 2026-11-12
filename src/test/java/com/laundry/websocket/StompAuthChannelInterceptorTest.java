package com.laundry.websocket;

import com.laundry.auth.security.JwtService;
import com.laundry.shared.security.CurrentUser;
import com.laundry.shared.security.Role;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.MessageDeliveryException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class StompAuthChannelInterceptorTest {

    private final StompAuthChannelInterceptor interceptor =
            new StompAuthChannelInterceptor(mock(JwtService.class));

    private final CurrentUser staffOfA = CurrentUser.of(UUID.randomUUID(), Role.STAFF);

    @Test
    void machineStatusIsPublic() {
        assertThatCode(() -> interceptor.authorizeSubscription("/topic/machines/WM001", Optional.empty()))
                .doesNotThrowAnyException();
    }

    @Test
    void theOldBranchTopicIsGone() {
        assertThatThrownBy(() -> interceptor.authorizeSubscription(
                "/topic/branches/" + UUID.randomUUID() + "/machines/WM001/runtime", Optional.of(staffOfA)))
                .isInstanceOf(MessageDeliveryException.class);
    }

    @Test
    void unknownDestinationsAreRefused() {
        assertThatThrownBy(() -> interceptor.authorizeSubscription("/topic/everything", Optional.of(staffOfA)))
                .isInstanceOf(MessageDeliveryException.class);
        assertThatThrownBy(() -> interceptor.authorizeSubscription("/user/queue/x", Optional.empty()))
                .isInstanceOf(MessageDeliveryException.class);
    }
}
