package dev.swordkingdom.horde.client;

import dev.swordkingdom.horde.HordeMod;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;

/**
 * 「按 (实体类型, 装备组合) 缓存一份变体网格」的 LRU 缓存。
 *
 * <h2>为什么要 LRU + 上限</h2>
 * 装备组合是**有限**的（玩家手里的物品种类有限），但理论上没有上界：
 * 如果放任缓存在一个「每人一套不同装备」的服务器上无限增长，
 * 每个变体网格都要占一份显存和一整个 VAO。所以这里给每个实体类型一个上限
 * （默认 64，可配），超出时淘汰**最久未使用**的那一份并记日志
 * —— 记日志很重要：如果玩家发现「换装之后要等一下才显示」，日志里应该能看出是淘汰导致的。
 *
 * <h2>为什么不直接复用原版 Guava Cache</h2>
 * 这里要的是「可观测 + 可离线验证」：命中数/未命中数/淘汰数都要能读出来做断言，
 * 而且淘汰时还要顺手释放 GL 资源（由 {@link #evictor} 回调）。自己写 60 行更清楚。
 *
 * <h2>泛型 + 无 GL 依赖</h2>
 * 这个类**完全不碰 OpenGL**（值类型是 {@code T}），所以离线 harness 里可以用
 * 一个替身对象验证「命中/淘汰/上限」的全部行为 —— 真机上 {@code T} 才是带 VAO 的网格。
 *
 * 线程约定：只在客户端主线程（渲染线程）访问。
 */
public final class EquipMeshCache<T> {

    /** 按键值对 */
    public interface Factory<T> {
        /** 造一份新的（可能返回 null = 这个组合烘焙不出来，会被记成「失败」不再重试） */
        T create(ResourceLocation unitType, String equipKey);

        /** 淘汰/作废时释放（释放 GL 资源）。可以为 null。 */
        default void release(T value) {
        }
    }

    private static final class Node<T> {
        final ResourceLocation type;
        final String key;
        T value;
        /** 造失败过：不再重试（否则每次渲染都会重烘一遍，帧率直接崩） */
        boolean failed;
        Node<T> prev;
        Node<T> next;

        Node(ResourceLocation type, String key) {
            this.type = type;
            this.key = key;
        }
    }

    private final int maxPerType;
    private final Factory<T> factory;
    private final Map<String, Node<T>> table = new HashMap<>();
    /** 每类型的条目数（避免每次淘汰判断都遍历整条链） */
    private final Map<ResourceLocation, Integer> perTypeCount = new HashMap<>();
    /** LRU 链：head = 最近使用，tail = 最久未使用 */
    private Node<T> head;
    private Node<T> tail;

    private long hits;
    private long misses;
    private long evictions;
    private long failures;

    public EquipMeshCache(int maxPerType, Factory<T> factory) {
        this.maxPerType = Math.max(1, maxPerType);
        this.factory = factory;
    }

    public int maxPerType() {
        return maxPerType;
    }

    public int size() {
        return table.size();
    }

    /** 某个类型的变体数（O(1)） */
    public int sizeOf(ResourceLocation unitType) {
        Integer n = perTypeCount.get(unitType);
        return n == null ? 0 : n;
    }

    public long hits() {
        return hits;
    }

    public long misses() {
        return misses;
    }

    public long evictions() {
        return evictions;
    }

    public long failures() {
        return failures;
    }

    public boolean isFailed(ResourceLocation unitType, String equipKey) {
        Node<T> node = table.get(cacheKey(unitType, equipKey));
        return node != null && node.failed;
    }

    /**
     * 取（必要时烘焙）某个变体。
     *
     * @return 变体网格；烘焙失败返回 null（并且**不会**每次都重试）
     */
    public T get(ResourceLocation unitType, String equipKey) {
        String k = cacheKey(unitType, equipKey);
        Node<T> node = table.get(k);
        if (node != null) {
            if (node.failed) {
                return null;
            }
            hits++;
            moveToHead(node);
            return node.value;
        }
        misses++;
        node = new Node<>(unitType, equipKey);
        T created = null;
        try {
            created = factory.create(unitType, equipKey);
        } catch (Throwable t) {
            HordeMod.LOGGER.warn("[horde] 变体网格烘焙异常（{} / {}）: {}", unitType, equipKey, t.toString());
        }
        if (created == null) {
            node.failed = true;
            failures++;
            // 失败也进表：避免每帧重试同一个烘不出来的组合
            table.put(k, node);
            pushFront(node);
            countUp(unitType);
            evictIfNeeded(unitType);
            return null;
        }
        node.value = created;
        table.put(k, node);
        pushFront(node);
        countUp(unitType);
        evictIfNeeded(unitType);
        return created;
    }

    /** 该类型装备组合变化时作废（例如该类型换了模型/贴图覆盖） */
    public void invalidateType(ResourceLocation unitType) {
        Node<T> node = head;
        while (node != null) {
            Node<T> next = node.next;
            if (node.type.equals(unitType)) {
                remove(node);
                table.remove(cacheKey(node.type, node.key));
                countDown(node.type);
                if (node.value != null && factory != null) {
                    factory.release(node.value);
                }
            }
            node = next;
        }
    }

    /** 全部作废（覆盖表变了 / 换维度） */
    public void clear() {
        Node<T> node = head;
        while (node != null) {
            Node<T> next = node.next;
            if (node.value != null && factory != null) {
                factory.release(node.value);
            }
            node = next;
        }
        table.clear();
        perTypeCount.clear();
        head = null;
        tail = null;
    }

    // ------------------------------------------------------------------

    private void evictIfNeeded(ResourceLocation unitType) {
        while (sizeOf(unitType) > maxPerType) {
            // 从尾巴开始找「该类型最久未使用」的那一份
            Node<T> victim = tail;
            while (victim != null && !victim.type.equals(unitType)) {
                victim = victim.prev;
            }
            if (victim == null) {
                return;
            }
            remove(victim);
            table.remove(cacheKey(victim.type, victim.key));
            countDown(victim.type);
            if (victim.value != null && factory != null) {
                try {
                    factory.release(victim.value);
                } catch (Throwable ignored) {
                    // 释放失败不该让缓存逻辑崩
                }
            }
            evictions++;
            HordeMod.LOGGER.info("[horde] 变体网格缓存已满（{} 的上限 {}），"
                            + "淘汰最久未使用的组合 [{}]（淘汰第 {} 次；"
                            + "想少淘汰就把 config 里的 equipVariantCachePerType 调大）",
                    unitType, maxPerType, victim.key, evictions);
        }
    }

    private void pushFront(Node<T> node) {
        node.prev = null;
        node.next = head;
        if (head != null) {
            head.prev = node;
        }
        head = node;
        if (tail == null) {
            tail = node;
        }
    }

    private void moveToHead(Node<T> node) {
        if (node == head) {
            return;
        }
        // 摘下来
        if (node.prev != null) {
            node.prev.next = node.next;
        }
        if (node.next != null) {
            node.next.prev = node.prev;
        }
        if (tail == node) {
            tail = node.prev;
        }
        pushFront(node);
    }

    private void remove(Node<T> node) {
        if (node.prev != null) {
            node.prev.next = node.next;
        } else {
            head = node.next;
        }
        if (node.next != null) {
            node.next.prev = node.prev;
        } else {
            tail = node.prev;
        }
        node.prev = null;
        node.next = null;
    }

    private static String cacheKey(ResourceLocation type, String equipKey) {
        return type + "#" + equipKey;
    }

    private void countUp(ResourceLocation type) {
        perTypeCount.merge(type, 1, Integer::sum);
    }

    private void countDown(ResourceLocation type) {
        Integer n = perTypeCount.get(type);
        if (n == null || n <= 1) {
            perTypeCount.remove(type);
        } else {
            perTypeCount.put(type, n - 1);
        }
    }
}
