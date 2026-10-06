package com.hmdp.service.impl;

import cn.hutool.core.lang.TypeReference;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import io.netty.util.internal.StringUtil;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;


@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    @Override
    public Result queryById(Long id) throws InterruptedException {
        // cache penetration prevention
//        Shop shop = queryWithPassThrough(id);
        // cache breakdown prevention
        Shop shop = queryWithLogicalExpire(id);
        if (shop == null) {
            return Result.fail("Shop not found");
        }

        return Result.ok(shop);
    }


    private Shop queryWithLogicalExpire(Long id) throws InterruptedException {
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        // if the shop data is not in redis, return null. Because this method is used for cache breakdown prevention, we assume that the data should be in redis.
        if (StrUtil.isBlank(shopJson)) {
            return null;
        }

        // deserialize the shop data from redis by TypeReference to RedisData<Shop>
        RedisData<Shop> redisData = parseJsonToRedisData(shopJson);
        Shop shop = redisData.getData();

        // if the data is not expired, return it
        if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
            return shop;
        }

        // if the data is expired, try to acquire the lock
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        String lockValue = UUID.randomUUID().toString();

        if (!tryToLock(lockKey, lockValue)) {
            return shop;
        }

        System.out.println("Rebuilding cache for shop id: " + id);
        boolean handedOver = false; // flag to indicate if the cache rebuild task has been handed over to another thread
        try {
            String latest = stringRedisTemplate.opsForValue().get(key);

            // if the shop data is not in redis, return null. Because this method is used for cache breakdown prevention, we assume that the data should be in redis.
            if (StrUtil.isBlank(latest)) {
                return null;
            }

            // deserialize the shop data from redis by TypeReference to RedisData<Shop>
            RedisData<Shop> latestData = parseJsonToRedisData(latest);
            // if the data is not expired, return it
            if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
                return latestData.getData();
            }

            CACHE_REBUILD_EXECUTOR.execute(() -> {
                try {
                    // rebuild the cache
                    saveShopToRedisCache(id, RedisConstants.CACHE_SHOP_TTL);
                    System.out.println("Saving cache for shop id: " + id);
                } catch (Exception e) {
                    log.error("rebuild cache failed for shop id: " + id, e);
                } finally {
                    releaseLock(lockKey, lockValue);
                }
            });
            handedOver = true;
            return shop;
        } finally {
            if (!handedOver) {
                releaseLock(lockKey, lockValue); // release the lock if the cache rebuild task has not been handed over to another thread
            }
        }
    }

    // preload the shop data into redis cache with an expiration time
    public void saveShopToRedisCache(Long id, Long expireSeconds) throws InterruptedException {
        String key = RedisConstants.CACHE_SHOP_KEY + id;

        // get the shop from the database
        Shop shopById = getById(id);

//        Thread.sleep(200); // simulate a delay in database query

        // box the shop into RedisData with an expiration time
        RedisData<Shop> redisData = new RedisData<>();
        redisData.setData(shopById);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));

        // save it to redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    private Shop queryWithMutex(Long id) throws InterruptedException {
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        long deadline = System.currentTimeMillis() + 2000L;

        while (true) {
            String shopJson = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(shopJson)) {
                return JSONUtil.toBean(shopJson, Shop.class);
            }

            if (shopJson != null) {
                return null;
            }

            String lockValue = UUID.randomUUID().toString();
            if (tryToLock(lockKey, lockValue)) {
                try {
                    shopJson = stringRedisTemplate.opsForValue().get(key);
                    if (StrUtil.isNotBlank(shopJson)) {
                        return JSONUtil.toBean(shopJson, Shop.class);
                    }
                    if (shopJson != null) {
                        return null;
                    }

                    Shop shopById = getById(id);
                    if (shopById == null) {
                        stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                        return null;
                    }

                    stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shopById), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
                    return shopById;
                } catch (Exception e) {
                    throw new RuntimeException(e.getMessage());
                } finally {
                    releaseLock(lockKey, lockValue);
                }
            }
            // if the lock is not acquired, wait for a short time and retry
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


    private Shop queryWithPassThrough(Long id) {
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        // query the shop from the redis cache first
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        // if the shop is in the cache, return it
        if (shopJson != null && !shopJson.isEmpty()) {
            return JSONUtil.toBean(shopJson, Shop.class);
        }

        if (shopJson != null) {
            return null;
        }

        // if the shop is not in the cache, query it from the database
        Shop shopById = getById(id);

        // if the shop is not in the database, return an error
        if (shopById == null) {
            // store a null value in the cache to prevent cache penetration
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }

        // if the shop is found, store it in the cache and return it
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shopById), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        return shopById;
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("Shop id is null");
        }

        // update database
        updateById(shop);
        // delete cache
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + id);

        return Result.ok();
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

    private RedisData<Shop> parseJsonToRedisData(String shopJson) {
        return JSONUtil.toBean(shopJson, new TypeReference<RedisData<Shop>>() {
        }, false);
    }
}
