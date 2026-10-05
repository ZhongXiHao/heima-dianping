package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;


@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryTypeList() {
        String key = RedisConstants.CACHE_SHOP_TYPE_LIST_KEY;
        String shopTypeListJson = stringRedisTemplate.opsForValue().get(key);

        if (shopTypeListJson != null && !shopTypeListJson.isEmpty()) {
            List<ShopType> shopTypeList = JSONUtil.toList(shopTypeListJson, ShopType.class);
            return Result.ok(shopTypeList);
        }

        List<ShopType> typeList = query().orderByAsc("sort").list();
        if (typeList != null && !typeList.isEmpty()) {
            String typeListJson = JSONUtil.toJsonStr(typeList);
            stringRedisTemplate.opsForValue().set(key, typeListJson, RedisConstants.CACHE_SHOP_TYPE_LIST_TTL, TimeUnit.HOURS);
            return Result.ok(typeList);
        }

        stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);


        return Result.fail("Shop type list is empty");
    }
}
