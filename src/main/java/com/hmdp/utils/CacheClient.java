package com.hmdp.utils;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.lang.reflect.Type;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Component
@Slf4j
public class CacheClient {
    private final StringRedisTemplate stringRedisTemplate;
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, long ttl, TimeUnit timeUnit) {
        long randomTtl = ttl + RandomUtil.randomLong(1, 5);// add random time to prevent cache avalanche
        String jsonStr = JSONUtil.toJsonStr(value);
        stringRedisTemplate.opsForValue().set(key, jsonStr, randomTtl, timeUnit);
    }

    public void setWithLogicalExpire(String key, Object value, long ttl, TimeUnit timeUnit) {
        RedisData<Object> redisData = new RedisData<>();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(ttl)));
        String jsonStr = JSONUtil.toJsonStr(redisData);
        stringRedisTemplate.opsForValue().set(key, jsonStr);
    }

    /**
     * Query the cache with pass-through strategy. Full parameters version. If the cache is empty, it will query the database and update the cache.
     *
     * @param prefix
     * @param id
     * @param type
     * @param dbFallback
     * @param ttl
     * @param timeUnit
     * @param nullTtl
     * @param nullTimeUnit
     * @param <R>
     * @param <ID>
     * @return
     */
    public <R, ID> R queryWithPassThrough(String prefix, ID id, Class<R> type, Function<ID, R> dbFallback, long ttl, TimeUnit timeUnit, long nullTtl, TimeUnit nullTimeUnit) {
        String key = prefix + id.toString();
        String jsonStr = stringRedisTemplate.opsForValue().get(key);

        if (StrUtil.isNotBlank(jsonStr)) {
            return JSONUtil.toBean(jsonStr, type);
        }

        if (jsonStr != null) {
            return null;
        }

        R appliedObject = dbFallback.apply(id);
        if (appliedObject == null) {
            stringRedisTemplate.opsForValue().set(key, "", nullTtl, nullTimeUnit);
            return null;
        }

        this.set(key, appliedObject, ttl, timeUnit);

        return appliedObject;
    }

    /**
     * Query the cache with pass-through strategy. If the cache is empty, it will query the database and update the cache. Default null value expiration time is 2 minutes.
     *
     * @param prefix
     * @param id
     * @param type
     * @param dbFallback
     * @param ttl
     * @param unit
     * @param <R>
     * @param <ID>
     * @return
     */
    public <R, ID> R queryWithPassThrough(String prefix, ID id, Class<R> type,
                                          Function<ID, R> dbFallback, long ttl, TimeUnit unit) {
        return queryWithPassThrough(prefix, id, type, dbFallback, ttl, unit,
                RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
    }

    /**
     * Query the cache with logical expiration strategy. If the cache is expired, it will return the stale data and rebuild the cache asynchronously.
     *
     * @param prefix
     * @param id
     * @param type
     * @param dbFallback
     * @param ttl
     * @param timeUnit
     * @param <R>
     * @param <ID>
     * @return
     */
    public <R, ID> R queryWithLogicalExpire(String prefix, ID id, Class<R> type, Function<ID, R> dbFallback, long ttl, TimeUnit timeUnit) {
        return queryWithLogicalExpire(prefix, id, (Type) type, dbFallback, ttl, timeUnit);
    }

    /**
     * Same as above, but accepts a generic type such as {@code new TypeReference<List<Shop>>() {}.getType()}.
     */
    public <R, ID> R queryWithLogicalExpire(String prefix, ID id, Type type, Function<ID, R> dbFallback, long ttl, TimeUnit timeUnit) {
        if (id == null) {
            return null;
        }

        String key = prefix + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        // the cache must be preloaded first, otherwise it will return null
        if (StrUtil.isBlank(shopJson)) {
            return null;
        }
        RedisData<R> redisData = parseJsonToRedisData(shopJson, type);
        R data = redisData.getData();
        // a missing expireTime means malformed data, treat it as expired so it gets rebuilt
        if (redisData.getExpireTime() != null && redisData.getExpireTime().isAfter(LocalDateTime.now())) {
            return data;
        }

        String lockKey = prefix + id;
        String lockValue = UUID.randomUUID().toString();
        boolean isLock = tryToLock(lockKey, lockValue);

        if (!isLock) {
            return data;
        }

        boolean handedOver = false;
        // double check if the cache has been updated by another thread
        try {
            String latest = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isBlank(latest)) {
                return null;
            }

            RedisData<R> latestRedisData = parseJsonToRedisData(latest, type);
            if (latestRedisData.getExpireTime() != null && latestRedisData.getExpireTime().isAfter(LocalDateTime.now())) {
                return latestRedisData.getData();
            }

            CACHE_REBUILD_EXECUTOR.execute(() -> {
                try {
                    saveShopToRedisCache(id, dbFallback, ttl, timeUnit);
                } catch (Exception e) {
                    log.error("rebuild cache failed for shop id: {}", id, e);
                    throw new RuntimeException(e.getMessage());
                } finally {
                    releaseLock(lockKey, lockValue);
                }
            });
            handedOver = true;
            return data;
        } finally {
            if (!handedOver) {
                releaseLock(lockKey, lockValue);
            }
        }
    }

    public <R, ID> R queryWithMutex(String prefix, ID id, Class<R> type, Function<ID, R> dbFallback, long ttl, TimeUnit timeUnit, long nullTtl, TimeUnit nullTimeUnit) {
        if (id == null) {
            return null;
        }
        String key = prefix + id;
        String lockKey = prefix + id;
        long deadline = System.currentTimeMillis() + 2000L;

        while (true) {
            String shopJson = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(shopJson)) {
                return JSONUtil.toBean(shopJson, type);
            }

            if (shopJson != null) {
                return null;
            }

            String lockValue = UUID.randomUUID().toString();
            if (tryToLock(lockKey, lockValue)) {
                try {
                    String latest = stringRedisTemplate.opsForValue().get(key);
                    if (StrUtil.isNotBlank(latest)) {
                        return JSONUtil.toBean(latest, type);
                    }
                    if (latest != null) {
                        return null;
                    }

                    R appliedObject = dbFallback.apply(id);
                    if (appliedObject == null) {
                        set(key, "", nullTtl, nullTimeUnit);
                        return null;
                    }
                    set(key, appliedObject, ttl, timeUnit);
                    return appliedObject;
                } catch (Exception e) {
                    log.error("rebuild cache failed for shop id: {}", id, e);
                    throw new RuntimeException(e.getMessage());
                } finally {
                    releaseLock(lockKey, lockValue);
                }
            }

            if (System.currentTimeMillis() > deadline) {
                throw new RuntimeException("Failed to acquire lock for shop id: " + id);
            }

            try {
                Thread.sleep(50 + ThreadLocalRandom.current().nextInt(50));
            } catch (Exception e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e.getMessage());
            }

        }
    }

    public <R, ID> R queryWithMutex(String prefix, ID id, Class<R> type, Function<ID, R> dbFallback, long ttl, TimeUnit timeUnit) {
        return queryWithMutex(prefix, id, type, dbFallback, ttl, timeUnit,
                RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
    }
    public <ID> void delete (String prefix, ID id){
        if(id == null){
            return;
        }
        String key = prefix + id;
        stringRedisTemplate.delete(key);
    }

    // preload the shop data into redis cache with an expiration time
    public <R, ID> void saveShopToRedisCache(ID id, Function<ID, R> dbFallback, long ttl, TimeUnit timeUnit) {
        String key = RedisConstants.CACHE_SHOP_KEY + id;

        // get the shop from the database
        R appliedObject = dbFallback.apply(id);

//        Thread.sleep(200); // simulate a delay in database query

        // box the shop into RedisData with an expiration time
        this.setWithLogicalExpire(key, appliedObject, ttl, timeUnit);
    }


    private boolean tryToLock(String key, String lockValue) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, lockValue, RedisConstants.LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return flag != null && flag;
    }

    private void releaseLock(String key, String lockValue) {
        if (lockValue.equals(stringRedisTemplate.opsForValue().get(key))) {
            stringRedisTemplate.delete(key);
        }
    }

    private <R> RedisData<R> parseJsonToRedisData(String json, Type type) {
        JSONObject root = JSONUtil.parseObj(json);
        RedisData<R> result = new RedisData<>();
        result.setExpireTime(root.get("expireTime", LocalDateTime.class));

        Object data = root.get("data");
        if (data != null) {
            // data may be a JSONObject or a JSONArray, so convert it back to a string and parse by the target type
            result.setData(JSONUtil.toBean(JSONUtil.toJsonStr(data), type, false));
        }
        return result;
    }

}
