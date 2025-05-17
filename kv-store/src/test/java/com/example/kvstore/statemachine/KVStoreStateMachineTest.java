package com.example.kvstore.statemachine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.example.kvstore.KVStore;
import com.example.kvstore.KVStoreOperation;
import com.example.kvstore.KVStoreResult;
import com.example.kvstore.command.KVCommand;
import com.example.kvstore.command.PutCommand;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

public class KVStoreStateMachineTest {
    private KVStore kvStore;
    private KVStoreStateMachine stateMachine;

    @BeforeEach
    void setUp() {
        kvStore = mock(KVStore.class);
        stateMachine = new KVStoreStateMachine(kvStore);
    }

    @Test
    void testApplyCommand() {
        KVCommand command = new PutCommand("key1", "value1");
        stateMachine.apply(command);
        verify(kvStore).put("key1", "value1");
    }

    @Test
    void testSuccessfulCommand() {
        PutCommand command = new PutCommand("key1", "value1");
        when(kvStore.put("key1", "value1")).thenReturn(KVStoreResult.success(KVStoreOperation.PUT));
        boolean result = stateMachine.apply(command);
        assertTrue(result);
        verify(kvStore).put("key1", "value1");
    }

}
