package com.example.kvstore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class InMemoryKVStoreTest {
    @Mock
    private KVStoreListener listener;
    private KVStore kvStore;

    @BeforeEach
    public void setUp() {
        kvStore = new InMemoryKVStore();
    }

    @Test
    public void testPutNotifiesListener() {

        kvStore.addListener(listener);

        KVStoreResult result = kvStore.put("testKey", "testValue");

        assertTrue(result.isSuccess());
        /**
         * argThat() : Takes a lambda/predicate as argument to
         * validate if the argument passed matches our criteria
         * If the lambda/predicate returns true -> assertion passes
         * If lambda/predicate returns false -> assertion fails
         */
        verify(listener).onEvent(argThat(event -> event.getOperation() == KVStoreOperation.PUT &&
                event.getKey().equals("testKey") &&
                event.getNewValue().equals("testValue") &&
                event.getOldValue() == null));

    }

    @Test
    public void testUpdateNotifiesListener() {

        kvStore.addListener(listener);
        kvStore.put("testKey", "testValue");
        KVStoreResult result = kvStore.put("testKey", "newValue");

        assertTrue(result.isSuccess());
        verify(listener).onEvent(argThat(event -> event.getOperation() == KVStoreOperation.PUT &&
                event.getKey().equals("testKey") &&
                event.getNewValue().equals("newValue") &&
                event.getOldValue().equals("testValue")));
    }

    @Test
    public void testGet() {
        assertFalse(kvStore.get("nonexistent").isSuccess());
        assertEquals("Key not found", kvStore.get("nonexistent").getError());
        kvStore.put("key1", "value1");
        assertEquals("value1", kvStore.get("key1").getValue());
        kvStore.put("key1", "newValue1");
        assertEquals("newValue1", kvStore.get("key1").getValue());
    }

    @Test
    public void testDeleteNotifiesListener() {

        kvStore.addListener(listener);
        kvStore.put("testKey", "testValue");

        KVStoreResult result = kvStore.delete("testKey");

        // Verify
        assertTrue(result.isSuccess());
        verify(listener).onEvent(argThat(event -> event.getOperation() == KVStoreOperation.DELETE &&
                event.getKey().equals("testKey") &&
                event.getNewValue() == null &&
                event.getOldValue().equals("testValue")));
    }

    @Test
    public void testRemoveListener() {

        kvStore.addListener(listener);

        kvStore.removeListener(listener);
        kvStore.put("testKey", "testValue");
        verifyNoInteractions(listener);
    }

    @Test
    public void testExists() {

        KVStoreResult result = kvStore.exists("nonexistent");
        assertEquals(KVStoreOperation.EXISTS, result.getOperation());
        assertEquals("false", result.getValue());

        kvStore.put("key1", "value1");
        KVStoreResult existResult = kvStore.exists("key1");
        assertEquals("true", existResult.getValue());
        kvStore.delete("key1");
        KVStoreResult afterDelResult = kvStore.exists("key1");
        assertEquals("false", afterDelResult.getValue());
    }

    @Test
    public void testConcurrentAccess() throws InterruptedException {
        final int numThreads = 10;
        final int numOperations = 100;
        Thread[] threads = new Thread[numThreads];
        for (int i = 0; i < numThreads; i++) {
            final int threadId = i;
            threads[i] = new Thread(() -> {
                for (int j = 0; j < numOperations; j++) {
                    String key = "key" + threadId + "-" + j;
                    String value = "value" + threadId + "-" + j;
                    kvStore.put(key, value);

                    assertEquals(value, kvStore.get(key).getValue());
                    assertEquals("true", kvStore.exists(key).getValue());
                    assertTrue(kvStore.delete(key).isSuccess());
                    assertEquals("false", kvStore.exists(key));
                }
            });
            threads[i].start();
        }
        for (Thread thread : threads) {
            thread.join();
        }
    }

}
