package com.sundry.assistant;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

class AssistantAuditWriterTest {

    @Test
    void asyncWriteDoesNotBlockCaller() {
        AssistantChatLogMapper mapper = mock(AssistantChatLogMapper.class);
        AssistantAuditWriter writer = new AssistantAuditWriter(mapper, true, 10);
        try {
            AssistantChatLog entry = entry();

            writer.write(entry);

            verify(mapper, timeout(1000)).insert(entry);
        } finally {
            writer.shutdown();
        }
    }

    @Test
    void syncWriteInsertsImmediately() {
        AssistantChatLogMapper mapper = mock(AssistantChatLogMapper.class);
        AssistantAuditWriter writer = new AssistantAuditWriter(mapper, false, 10);
        try {
            AssistantChatLog entry = entry();

            writer.write(entry);

            verify(mapper).insert(entry);
        } finally {
            writer.shutdown();
        }
    }

    private AssistantChatLog entry() {
        AssistantChatLog entry = new AssistantChatLog();
        entry.setUserId(1L);
        entry.setAction("navigate");
        return entry;
    }
}
