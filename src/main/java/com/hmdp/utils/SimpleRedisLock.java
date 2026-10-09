package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class SimpleRedisLock implements ILock {

    private static final String KEY_PREFIX = "lock:";
    private static final DefaultRedisScript<Long> RELEASE_LOCK_SCRIPT;


    static {
        RELEASE_LOCK_SCRIPT = new DefaultRedisScript<>();
        RELEASE_LOCK_SCRIPT.setLocation(new ClassPathResource("release-lock.lua"));
        RELEASE_LOCK_SCRIPT.setResultType(Long.class);
    }

    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public boolean tryToLock(String lockKey, String lockValue, long timeoutSec) {
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + lockKey, lockValue, timeoutSec, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(success);
    }

//    @Override
//    public void releaseLock(String lockKey, String lockValue) {
//        if (lockValue.equals(stringRedisTemplate.opsForValue().get(KEY_PREFIX + lockKey))) {
//            stringRedisTemplate.delete(KEY_PREFIX + lockKey);
//        }
//    }

    @Override
    public void releaseLock(String lockKey, String lockValue) {
        stringRedisTemplate.execute(RELEASE_LOCK_SCRIPT, Collections.singletonList(KEY_PREFIX + lockKey), lockValue);
    }
}
