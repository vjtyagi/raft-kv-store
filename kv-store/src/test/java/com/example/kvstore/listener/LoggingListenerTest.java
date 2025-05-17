package com.example.kvstore.listener;

import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;

import com.example.kvstore.KVStoreEvent;
import com.example.kvstore.KVStoreOperation;

@ExtendWith(MockitoExtension.class)
public class LoggingListenerTest {
    @Mock
    private Logger logger;

    @Test
    public void testPutEventLogging() {
        LoggingListener listener = new LoggingListener(logger);
        KVStoreEvent event = new KVStoreEvent(KVStoreOperation.PUT, "testKey", null, "newValue");
        listener.onEvent(event);
        /**
         * verify(logger).info("format", arg1, arg2...)
         * mockito doesn' require matchers if you pass concrete values
         * It internally checks
         * 1. is method name(info) correct?
         * 2. Is first argument exactly the string you gave?
         * 3. are subsequent varargs equal to what was pased, in value and order?
         */
        verify(logger).info("KVStore operation: {}, on key: {}, old value: {}, new value: {}",
                KVStoreOperation.PUT,
                "testKey",
                null,
                "newValue");
    }

    @Test
    public void testUpdateEventLogging() {
        LoggingListener listener = new LoggingListener(logger);
        KVStoreEvent event = new KVStoreEvent(KVStoreOperation.PUT, "testKey", "oldValue", "newValue");
        listener.onEvent(event);
        verify(logger).info(
                "KVStore operation: {}, on key: {}, old value: {}, new value: {}",
                KVStoreOperation.PUT,
                "testKey",
                "oldValue",
                "newValue");
    }
}
