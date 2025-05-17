package com.example.kvstore;

public interface KVStoreListener {
    /**
     * Called when a change occurs in the KVStore
     * 
     * @param event the event containing details about the change
     */
    void onEvent(KVStoreEvent event);
}
