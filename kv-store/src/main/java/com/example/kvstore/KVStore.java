package com.example.kvstore;

import java.util.Map;

public interface KVStore {
    /**
     * Stores a value with a specified key
     * If the key already exists, it's value is updated
     * 
     * @param key   the key to store the value with
     * @param value the value to store
     * @return true if the operation was successfull, false otherwise
     */
    KVStoreResult put(String key, String value);

    /**
     * Retrieves the value associated with specified key
     * 
     * @param key The key to retreive the value for
     * @return the value associated with the key, or null if key doesn't exist
     */
    KVStoreResult get(String key);

    /**
     * Removes a key-value pair with specified key
     * 
     * @param key the key to remove
     * @return true if key was removed, false otherwise
     */
    KVStoreResult delete(String key);

    /**
     * Check if specified key exists in the store
     * 
     * @param ke the key to check
     * @return true if key exists, false otherwise
     */
    KVStoreResult exists(String key);

    void addListener(KVStoreListener listener);

    void removeListener(KVStoreListener listener);

    Map<String, String> getAllEntries();

}
