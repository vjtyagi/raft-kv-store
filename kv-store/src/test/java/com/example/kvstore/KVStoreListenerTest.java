package com.example.kvstore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class KVStoreListenerTest {

    @Mock
    private KVStoreListener listener;

    @Test
    public void testListener() {
        KVStoreEvent event = new KVStoreEvent(KVStoreOperation.PUT, "testKey", null, "newValue");
        listener.onEvent(event);

        ArgumentCaptor<KVStoreEvent> eventCaptor = ArgumentCaptor.forClass(KVStoreEvent.class);
        verify(listener).onEvent(eventCaptor.capture());

        KVStoreEvent capturedEvent = eventCaptor.getValue();
        assertEquals(KVStoreOperation.PUT, capturedEvent.getOperation());
        assertEquals("testKey", capturedEvent.getKey());
        assertEquals("newValue", capturedEvent.getNewValue());
        assertNull(capturedEvent.getOldValue());
    }

    @Test
    public void testListenerWithLambda() {
        KVStoreListener listener = (event) -> {
            assertEquals(KVStoreOperation.PUT, event.getOperation());
            assertEquals("testKey", event.getKey());
            assertEquals("newValue", event.getNewValue());
        };
        KVStoreEvent event = new KVStoreEvent(KVStoreOperation.PUT, "testKey", null, "newValue");
        listener.onEvent(event);
    }
}
