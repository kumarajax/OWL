package com.owldrive.api;

import java.util.Objects;

public final class ShardContext {
    private static final ThreadLocal<String> CURRENT_SHARD = new ThreadLocal<>();

    private ShardContext() {}

    public static String currentShard() {
        return CURRENT_SHARD.get();
    }

    public static void setCurrentShard(String shardId) {
        if (shardId == null || shardId.isBlank()) {
            CURRENT_SHARD.remove();
        } else {
            CURRENT_SHARD.set(shardId);
        }
    }

    public static void clear() {
        CURRENT_SHARD.remove();
    }

    public static <T> T withShard(String shardId, SupplierWithException<T> supplier) {
        String previous = CURRENT_SHARD.get();
        setCurrentShard(shardId);
        try {
            return supplier.get();
        } finally {
            if (previous == null) {
                clear();
            } else {
                setCurrentShard(previous);
            }
        }
    }

    @FunctionalInterface
    public interface SupplierWithException<T> {
        T get();
    }
}
