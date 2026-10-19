package com.project.person.admin.cache;

import java.util.Set;

/**
 * 通用缓存接口：只负责存取，不含任何业务逻辑。
 * 实现类通过 CacheFacade 对外暴露：新增 RedisCacheImpl 实现本接口后，
 * 在 CacheFacade 中把 @Qualifier 指向新实现即可切换，业务代码零改动。
 */
public interface CacheService {

    /** 写入缓存（覆盖） */
    void put(String key, Object value);

    /** 读取缓存，不存在返回 null */
    Object get(String key);

    /** 是否包含指定 key */
    boolean containsKey(String key);

    /** 删除指定 key */
    void remove(String key);

    /** 当前所有 key（便于遍历，如收盘落库时遍历行情缓存） */
    Set<String> keys();
}
