package com.github.catvod.utils;

// ConcurrentLruCache 本身已经是 <K, V> 泛型的，不用动
// 只需把 CacheBuilder 改成泛型
public class CacheBuilder<K, V> {
    private int initialCapacity = 16;
    private int concurrencyLevel = 4;
    private int maximumSize = Integer.MAX_VALUE;

    public static <K, V> CacheBuilder<K, V> newBuilder() { return new CacheBuilder<>(); }

    public CacheBuilder<K, V> initialCapacity(int n) { this.initialCapacity = n; return this; }
    public CacheBuilder<K, V> concurrencyLevel(int n) { this.concurrencyLevel = n; return this; }
    public CacheBuilder<K, V> maximumSize(int n) { this.maximumSize = n; return this; }
    public CacheBuilder<K, V> recordStats() { return this; } // 统计已内置，保留链式调用兼容

    public ConcurrentLruCache<K, V> build() {
        return new ConcurrentLruCache<>(initialCapacity, concurrencyLevel, maximumSize);
    }
}
