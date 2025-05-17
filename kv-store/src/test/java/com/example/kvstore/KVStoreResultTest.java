package com.example.kvstore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class KVStoreResultTest {

    @Test
    public void testSuccessfulPutOperation() {
        KVStoreResult result = KVStoreResult.success(KVStoreOperation.PUT);
        assertTrue(result.isSuccess());
        assertEquals(result.getOperation(), KVStoreOperation.PUT);
        assertNull(result.getError());
        assertNull(result.getValue());
    }

    @Test
    public void testSuccessfulGetOperation() {
        String expectedValue = "testValue";
        KVStoreResult result = KVStoreResult.success(expectedValue, KVStoreOperation.GET);
        assertTrue(result.isSuccess());
        assertEquals(KVStoreOperation.GET, result.getOperation());
        assertEquals(expectedValue, result.getValue());
        assertNull(result.getError());
    }

    @Test
    public void testFailedOperation() {
        String errorMessage = "Key not found";
        KVStoreResult result = KVStoreResult.failure(errorMessage, KVStoreOperation.GET);
        assertFalse(result.isSuccess());
        assertEquals(KVStoreOperation.GET, result.getOperation());
        assertNull(result.getValue());
        assertEquals(errorMessage, result.getError());

    }

    @Test
    public void testSuccessfulExistsOperation() {
        KVStoreResult result = KVStoreResult.success("true", KVStoreOperation.EXISTS);
        assertTrue(result.isSuccess());
        assertEquals(KVStoreOperation.EXISTS, result.getOperation());
        assertEquals("true", result.getValue());
        assertNull(result.getError());
        // Key doesn't exist
        KVStoreResult notExistResult = KVStoreResult.success("false", KVStoreOperation.EXISTS);
        assertTrue(notExistResult.isSuccess());
        assertEquals(KVStoreOperation.EXISTS, notExistResult.getOperation());
        assertEquals("false", notExistResult.getValue());
        assertNull(notExistResult.getError());
    }

}
