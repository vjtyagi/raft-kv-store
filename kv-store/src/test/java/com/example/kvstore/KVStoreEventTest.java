package com.example.kvstore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class KVStoreEventTest {
    @Test
    public void testPutEvent() {
        KVStoreEvent event = new KVStoreEvent(KVStoreOperation.PUT, "testKey", null, "newValue");

        assertEquals(KVStoreOperation.PUT, event.getOperation());
        assertEquals("testKey", event.getKey());
        assertNull(event.getOldValue());
        assertEquals("newValue", event.getNewValue());
        assertTrue(event.getTimestamp() > 0);
    }
}
