package com.example.kvstore;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

public class InMemoryKVStore implements KVStore {

    private final Map<String, String> store;
    private final Set<KVStoreListener> listeners;

    public InMemoryKVStore() {
        this.store = new ConcurrentHashMap<>();
        this.listeners = new CopyOnWriteArraySet<>();
    }

    @Override
    public KVStoreResult put(String key, String value) {
        if (key == null || value == null) {
            return KVStoreResult.failure("Key of value cannot be null", KVStoreOperation.PUT);
        }

        String oldValue = store.put(key, value);
        notifyListeners(new KVStoreEvent(KVStoreOperation.PUT, key, oldValue, value));
        return KVStoreResult.success(KVStoreOperation.PUT);
    }

    @Override
    public KVStoreResult get(String key) {
        if (key == null) {
            return KVStoreResult.failure("Key cannot be null", KVStoreOperation.GET);
        }
        String value = store.get(key);
        if (value == null) {
            return KVStoreResult.failure("Key not found", KVStoreOperation.GET);
        }
        return KVStoreResult.success(value, KVStoreOperation.GET);
    }

    @Override
    public KVStoreResult delete(String key) {
        if (key == null) {
            return KVStoreResult.failure("key cannot be null", KVStoreOperation.DELETE);
        }
        String oldValue = store.remove(key);
        if (oldValue != null) {
            notifyListeners(new KVStoreEvent(KVStoreOperation.DELETE, key, oldValue, null));
            return KVStoreResult.success(KVStoreOperation.DELETE);
        }
        return KVStoreResult.failure("Key not found", KVStoreOperation.DELETE);

    }

    @Override
    public KVStoreResult exists(String key) {
        if (key == null) {
            return KVStoreResult.failure("Key cannot be null", KVStoreOperation.EXISTS);
        }
        boolean exists = store.containsKey(key);
        return KVStoreResult.success(String.valueOf(exists), KVStoreOperation.EXISTS);
    }

    @Override
    public void addListener(KVStoreListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    @Override
    public void removeListener(KVStoreListener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    private void notifyListeners(KVStoreEvent event) {
        for (KVStoreListener listener : listeners) {
            try {
                listener.onEvent(event);

            } catch (Exception e) {
                System.err.println("Error notifying listener " + e.getMessage());
            }
        }
    }

    @Override
    public Map<String, String> getAllEntries() {
        return new HashMap<>(store);
    }

}
