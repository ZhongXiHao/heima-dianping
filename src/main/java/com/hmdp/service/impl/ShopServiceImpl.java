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

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;


@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryById(Long id) {
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        // query the shop from the redis cache first
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        // if the shop is in the cache, return it
        if (shopJson != null && !shopJson.isEmpty()) {
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }

        if (shopJson != null) {
            return Result.fail("Shop not found");
        }

        // if the shop is not in the cache, query it from the database
        Shop shopById = getById(id);

        // if the shop is not in the database, return an error
        if (shopById == null) {
            // store a null value in the cache to prevent cache penetration
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return Result.fail("Shop not found");
        }

        // if the shop is found, store it in the cache and return it
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shopById), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        return Result.ok(shopById);
    }
}
