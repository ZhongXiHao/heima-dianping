package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {
    private static final long BEGIN_TIMESTAMP = 1735689600L; // 2025-01-01 00:00:00
    private static final int COUNT_BITS = 32;

    private final StringRedisTemplate stringRedisTemplate;

    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public long nextId(String prefix) {
        // get current timestamp in seconds: now() - setup-time.now()
        LocalDateTime now = LocalDateTime.now();
        long nowEpochSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowEpochSecond - BEGIN_TIMESTAMP;

        // format the current date as yyyy:MM:dd
        String today = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        // create a sequence number
        Long count = stringRedisTemplate.opsForValue().increment("icr:" + prefix + ":" + today);
        return timestamp << COUNT_BITS | count;
    }

//    public static void main(String[] args) {
//        LocalDateTime localDateTime = LocalDateTime.of(2025, 1, 1, 0, 0, 0);
//        long second = localDateTime.toEpochSecond(java.time.ZoneOffset.UTC);
//        System.out.println(second);
//    }
}
