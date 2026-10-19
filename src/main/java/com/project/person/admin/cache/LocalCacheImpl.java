package com.project.person.admin.cache;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地缓存实现：基于 ConcurrentHashMap，简单够用。
 * bean 名称为 localCacheImpl；切换 Redis 时无需改动本类，
 * 在 CacheFacade 中把注入的 @Qualifier 改为新实现即可。
 */
@Component("localCacheImpl")
public class LocalCacheImpl implements CacheService {

    private final ConcurrentHashMap<String, Object> store = new ConcurrentHashMap<>();

    @Override
    public void put(String key, Object value) {
        if (key == null || value == null) {
            return;
        }
        store.put(key, value);
    }

    @Override
    public Object get(String key) {
        return key == null ? null : store.get(key);
    }

    @Override
    public boolean containsKey(String key) {
        return key != null && store.containsKey(key);
    }

    @Override
    public void remove(String key) {
        if (key != null) {
            store.remove(key);
        }
    }

    @Override
    public Set<String> keys() {
        return store.keySet();
    }
}
