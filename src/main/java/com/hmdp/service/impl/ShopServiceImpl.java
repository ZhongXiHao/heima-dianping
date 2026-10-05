package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;


@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryById(Long id) throws InterruptedException {
        // cache penetration prevention
//        Shop shop = queryWithPassThrough(id);
        // cache breakdown prevention
        Shop shop = this.queryWithMutex(id);
        if (shop == null) {
            return Result.fail("Shop not found");
        }

        return Result.ok(shop);
    }

    private Shop queryWithMutex(Long id) throws InterruptedException {
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

        // if the shop is not in the cache, try to acquire a lock
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        Shop shopById = null;
        try {
            boolean triedToLock = tryToLock(lockKey);

            // if the lock is not acquired, wait for a short time and try again
            if (!triedToLock) {
                Thread.sleep(50);
                return queryWithMutex(id);
            }

            // if the lock is acquired, double-check the cache to see if the shop is now in the cache (another thread may have populated it while we were waiting for the lock)
            shopJson = stringRedisTemplate.opsForValue().get(key);
            if (shopJson != null && !shopJson.isEmpty()) {
                releaseLock(lockKey);
                return JSONUtil.toBean(shopJson, Shop.class);
            }

            // query the shop from the database and store it in the cache
            shopById = getById(id);
            if (shopById == null) {
                // store a null value in the cache to prevent cache penetration
                stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }

            // if the shop is found, store it in the cache and return it
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shopById), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // release the lock
            releaseLock(lockKey);

        }

        return shopById;
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

    private boolean tryToLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", RedisConstants.LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return flag != null && flag;
    }

    private void releaseLock(String key) {
        stringRedisTemplate.delete(key);
    }
}
