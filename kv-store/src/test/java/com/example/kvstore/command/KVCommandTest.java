package com.example.kvstore.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.example.kvstore.KVStore;
import com.example.kvstore.KVStoreOperation;
import com.example.kvstore.KVStoreResult;

public class KVCommandTest {

    @Test
    void testAllCommandTypes() {
        KVCommand putCommand = new PutCommand("key1", "value1");
        assertEquals("PUT|key1|value1", putCommand.serialize());

        KVCommand getCommand = new GetCommand("key1");
        assertEquals("GET|key1", getCommand.serialize());

        KVCommand deleteCommand = new DeleteCommand("key1");
        assertEquals("DELETE|key1", deleteCommand.serialize());

        KVCommand existCommand = new ExistsCommand("key1");
        assertEquals("EXISTS|key1", existCommand.serialize());
    }

    @Test
    void testDeserialization() {
        KVCommand putCommand = KVCommand.deserialize("PUT|key1|value1");
        assertTrue(putCommand instanceof PutCommand);
        assertEquals("key1", putCommand.getKey());
        assertEquals("value1", ((PutCommand) putCommand).getValue());

        KVCommand getCmd = KVCommand.deserialize("GET|key1");
        assertTrue(getCmd instanceof GetCommand);
        assertEquals("key1", getCmd.getKey());

    }

    @Test
    void testCommandExecution() {
        KVStore mockStore = mock(KVStore.class);
        when(mockStore.put("key1", "value1")).thenReturn(KVStoreResult.success(KVStoreOperation.PUT));

        KVCommand putCommand = new PutCommand("key1", "value1");
        KVStoreResult result = putCommand.apply(mockStore);

        assertTrue(result.isSuccess());
        assertEquals(KVStoreOperation.PUT, result.getOperation());
        verify(mockStore).put("key1", "value1");
    }
}
