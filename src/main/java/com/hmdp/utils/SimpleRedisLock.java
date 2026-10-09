package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class SimpleRedisLock implements ILock {

    private static final String KEY_PREFIX = "lock:";
    private final StringRedisTemplate stringRedisTemplate;

    public SimpleRedisLock(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean tryToLock(String lockKey, String lockValue, long timeoutSec) {
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + lockKey, lockValue, timeoutSec, TimeUnit.SECONDS);

        return BooleanUtil.isTrue(success);
    }

    @Override
    public void releaseLock(String lockKey, String lockValue) {
        if (lockValue.equals(stringRedisTemplate.opsForValue().get(KEY_PREFIX + lockKey))) {
            stringRedisTemplate.delete(KEY_PREFIX + lockKey);
        }
    }
}
