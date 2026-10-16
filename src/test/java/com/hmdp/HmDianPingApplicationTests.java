package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@SpringBootTest
class HmDianPingApplicationTests {
    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

//    @Test
//    public void saveShopToRedisCache() throws InterruptedException {
//        shopService.saveShopToRedisCache(1L, 2L);
//    }

    private final ExecutorService executorService = Executors.newFixedThreadPool(500);

    @Test
    public void testIdWorker() {
        CountDownLatch countDownLatch = new CountDownLatch(300);

        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                long order = redisIdWorker.nextId("order");
                System.out.println(order);
            }
            countDownLatch.countDown();
        };

        long begin = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            executorService.execute(task);
        }
        try {
            countDownLatch.await();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        long end = System.currentTimeMillis();
        System.out.println(end - begin);
    }

    @Test
    public void testRedisson() throws InterruptedException {
        RLock lock = redissonClient.getLock("my-lock");
        boolean isLock = lock.tryLock(1, 10, TimeUnit.SECONDS);

        if (isLock) {
            try {
                System.out.println("Lock acquired, executing business logic");
            } finally {
                lock.unlock();
            }
        }

    }

    @Test
    public void loadShopData() {
        String keyPrefix = RedisConstants.SHOP_GEO_KEY;
        // query shops info
        List<Shop> shopList = shopService.list();

        // group the shops by typeId
        Map<Long, List<Shop>> collect = shopList.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        for (Map.Entry<Long, List<Shop>> entry : collect.entrySet()) {
            String key = entry.getKey().toString();
            List<Shop> value = entry.getValue();
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(value.size());
            for (Shop shop : value) {
                locations.add(new RedisGeoCommands.GeoLocation<>(shop.getId().toString(), new Point(shop.getX(), shop.getY())));
            }

            // write into redis
            stringRedisTemplate.opsForGeo().add(keyPrefix + key, locations);
        }
    }

    @Test
    public void testHyperLogLog() {
        String[] values = new String[1000];
        for (int i = 0; i < 1000000; ++i) {
            values[i % 1000] = "user_" + i;
            if (i % 1000 == 999) {
                stringRedisTemplate.opsForHyperLogLog().add("hl2", values);
            }
        }

        stringRedisTemplate.opsForHyperLogLog().size("hl2");

    }

}
