package com.github.catvod.utils;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 兼容 Android SDK 16 的并发 LRU 缓存。
 * 避免使用 API 24 才引入的 {@link java.util.concurrent.ConcurrentLinkedDeque}
 * 与 {@link java.util.concurrent.ConcurrentMap#computeIfAbsent}。
 */
public class ConcurrentLruCache<K, V> {

    /** 统计数据 */
    public static class Stats {
        public final long hitCount, missCount, loadSuccessCount, loadExceptionCount, evictionCount;
        Stats(long h, long m, long ls, long le, long ev) {
            hitCount = h; missCount = m; loadSuccessCount = ls; loadExceptionCount = le; evictionCount = ev;
        }
        public long requestCount() { return hitCount + missCount; }
        public double hitRate() { long r = requestCount(); return r == 0 ? 1.0 : (double) hitCount / r; }
        @Override public String toString() {
            return String.format("hits=%d, misses=%d, hitRate=%.4f, evictions=%d",
                    hitCount, missCount, hitRate(), evictionCount);
        }
    }

    private final ConcurrentHashMap<K, V> map;
    private final LinkedList<K> lru;      // 头=最近使用
    private final int maximumSize;
    private final ReentrantLock evictLock = new ReentrantLock();
    private final ConcurrentMap<K, ReentrantLock> keyLocks = new ConcurrentHashMap<>();

    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong loadSuccess = new AtomicLong();
    private final AtomicLong loadException = new AtomicLong();
    private final AtomicLong evictions = new AtomicLong();

    public ConcurrentLruCache(int initialCapacity, int concurrencyLevel, int maximumSize) {
        this.maximumSize = maximumSize;
        this.map = new ConcurrentHashMap<>(initialCapacity, 0.75f, concurrencyLevel);
        this.lru = new LinkedList<>();
    }

    public V get(K key) {
        V v = map.get(key);
        if (v != null) {
            hits.incrementAndGet();
            touch(key);
        } else {
            misses.incrementAndGet();
        }
        return v;
    }

    public V get(K key, Callable<? extends V> loader) throws ExecutionException {
        V v = map.get(key);
        if (v != null) {
            hits.incrementAndGet();
            touch(key);
            return v;
        }
        misses.incrementAndGet();
        // SDK 16 没有 ConcurrentMap.computeIfAbsent，用手动 get + putIfAbsent 实现
        ReentrantLock lock = keyLocks.get(key);
        if (lock == null) {
            ReentrantLock newLock = new ReentrantLock();
            ReentrantLock existing = keyLocks.putIfAbsent(key, newLock);
            lock = existing != null ? existing : newLock;
        }
        lock.lock();
        try {
            v = map.get(key);           // 双重检查：可能别的线程刚加载完
            if (v != null) {
                hits.incrementAndGet();
                return v;
            }
            try {
                v = loader.call();
                loadSuccess.incrementAndGet();
            } catch (Exception e) {
                loadException.incrementAndGet();
                throw new ExecutionException(e);
            }
            putInternal(key, v);
            return v;
        } finally {
            lock.unlock();
            keyLocks.remove(key);
        }
    }

    public void put(K key, V value) {
        putInternal(key, value);
    }

    public void invalidate(K key) {
        V old = map.remove(key);
        if (old != null) {
            synchronized (lru) {
                lru.remove(key);
            }
        }
    }

    public void invalidateAll() {
        map.clear();
        synchronized (lru) {
            lru.clear();
        }
    }

    public long size() { return map.size(); }

    public Stats stats() {
        return new Stats(hits.get(), misses.get(), loadSuccess.get(), loadException.get(), evictions.get());
    }

    // ---------- 内部实现 ----------

    private void putInternal(K key, V value) {
        V old = map.put(key, value);
        synchronized (lru) {
            if (old == null) {
                lru.addFirst(key);
            } else {
                lru.remove(key);
                lru.addFirst(key);
            }
        }
        if (old == null) {
            evictIfNeeded();
        }
    }

    /** 访问时把 key 挪到 LRU 头部 */
    private void touch(K key) {
        // remove 是 O(n)，但只发生在命中路径且队列最大为 maximumSize，可接受
        synchronized (lru) {
            lru.remove(key);
            lru.addFirst(key);
        }
    }

    /** 超过上限时从 LRU 尾部淘汰 */
    private void evictIfNeeded() {
        if (map.size() <= maximumSize) return;
        evictLock.lock();
        try {
            while (map.size() > maximumSize) {
                K oldest;
                synchronized (lru) {
                    oldest = lru.pollLast();
                }
                if (oldest == null) break;
                if (map.remove(oldest) != null) evictions.incrementAndGet();
            }
        } finally {
            evictLock.unlock();
        }
    }
}
