package com.example.kvstore;

public class KVStoreResult {
    private final boolean success;
    private final String value;
    private final String error;
    private final KVStoreOperation operation;

    private KVStoreResult(boolean success, String value, String error, KVStoreOperation operation) {
        this.success = success;
        this.value = value;
        this.error = error;
        this.operation = operation;
    }

    /**
     * Creates a successful result for operations that don't return a value
     * (PUT/DELETE)
     */
    public static KVStoreResult success(KVStoreOperation operation) {
        return new KVStoreResult(true, null, null, operation);
    }

    /**
     * Create a successfull result with a return value ( GET operations )
     */
    public static KVStoreResult success(String value, KVStoreOperation operation) {
        return new KVStoreResult(true, value, null, operation);
    }

    /**
     * Creates a failed result with an error message
     */
    public static KVStoreResult failure(String error, KVStoreOperation operation) {
        return new KVStoreResult(false, null, error, operation);
    }

    public static KVStoreResult successExists(boolean exists) {
        return new KVStoreResult(true, String.valueOf(exists), null, KVStoreOperation.EXISTS);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getValue() {
        return value;
    }

    public String getError() {
        return error;
    }

    public KVStoreOperation getOperation() {
        return operation;
    }

    @Override
    public String toString() {
        return "KVStoreResult [success=" + success + ", value=" + value + ", error=" + error + ", operation="
                + operation + "]";
    }

}
