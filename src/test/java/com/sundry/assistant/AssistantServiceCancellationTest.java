package com.sundry.assistant;

import com.sundry.auth.security.AuthenticatedUser;
import com.sundry.user.Role;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class AssistantServiceCancellationTest {

    @Test
    void cancelledTaskDoesNotCallModel() {
        AssistantClient client = mock(AssistantClient.class);
        AssistantService service = new AssistantService(
                mock(RouteCatalog.class),
                mock(AssistantAuditWriter.class),
                client,
                mock(AssistantToolRegistry.class),
                mock(AssistantSessionStore.class),
                mock(ObjectMapper.class));

        AuthenticatedUser user = new AuthenticatedUser(1L, Role.USER);
        AssistantChatRequest request = new AssistantChatRequest(null, "你好");

        assertThatThrownBy(() -> service.chat(user, request, () -> true))
                .isInstanceOf(AssistantBusyException.class);
        verifyNoInteractions(client);
    }
}
