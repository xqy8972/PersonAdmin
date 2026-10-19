package com.project.person.admin.cache;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 缓存门面：对外唯一入口，业务代码只依赖本类。
 * 对 CacheService 具体实现的引用收敛在这里——后续更换缓存实现（如 RedisCacheImpl），
 * 只需把下面注入的 @Qualifier 改成新实现即可，业务代码零改动。
 */
@Component
public class CacheFacade {

    /** 当前使用本地 Map 缓存；切换 Redis 时改为 @Qualifier("redisCacheImpl") */
    @Autowired
    @Qualifier("localCacheImpl")
    private CacheService cacheService;

    public void put(String key, Object value) {
        cacheService.put(key, value);
    }

    public Object get(String key) {
        return cacheService.get(key);
    }

    public boolean containsKey(String key) {
        return cacheService.containsKey(key);
    }

    public void remove(String key) {
        cacheService.remove(key);
    }

    public Set<String> keys() {
        return cacheService.keys();
    }
}
