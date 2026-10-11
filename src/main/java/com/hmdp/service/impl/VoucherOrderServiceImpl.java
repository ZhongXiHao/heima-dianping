package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.UUID;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder>
        implements IVoucherOrderService {

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    //    private final BlockingQueue<VoucherOrder> orderTasks = new LinkedBlockingQueue<>(1024 * 1024);
    private final ExecutorService orderExecutor = Executors.newSingleThreadExecutor();
    private final String mqName = "stream.voucher.order";

    private final ISeckillVoucherService seckillVoucherService;
    private final RedisIdWorker redisIdWorker;
    private final StringRedisTemplate stringRedisTemplate;
    private final IVoucherOrderService self;   // 代理对象，调用事务方法用

    public VoucherOrderServiceImpl(ISeckillVoucherService seckillVoucherService,
                                   RedisIdWorker redisIdWorker,
                                   StringRedisTemplate stringRedisTemplate,
                                   @Lazy IVoucherOrderService self) {
        this.seckillVoucherService = seckillVoucherService;
        this.redisIdWorker = redisIdWorker;
        this.stringRedisTemplate = stringRedisTemplate;
        this.self = self;
    }

    @PostConstruct
    public void init() {
        orderExecutor.submit(this::consumeOrders);
    }

    @PreDestroy
    public void destroy() {
        orderExecutor.shutdownNow();   // 中断消费线程，让它退出循环
    }

    private void consumeOrders() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
//                VoucherOrder order = orderTasks.take();
                List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                        Consumer.from("g1", "c1"),
                        StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                        StreamOffset.create(mqName, ReadOffset.lastConsumed())
                );
                if (list == null || list.isEmpty()) {
                    continue;
                }
                MapRecord<String, Object, Object> record = list.get(0);
                Map<Object, Object> value = record.getValue();
                VoucherOrder order = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                self.createVoucherOrder(order);

                stringRedisTemplate.opsForStream().acknowledge(mqName, "g1", record.getId());
            } catch (Exception e) {
                log.error("处理订单失败", e);                  // 单条失败不能让消费线程退出
                handlePendingList();
            }
        }
    }

    private void handlePendingList() {
        while (true) {
            try {
                List<MapRecord<String, Object, Object>> pendingList = stringRedisTemplate.opsForStream().read(
                        Consumer.from("g1", "c1"),
                        StreamReadOptions.empty().count(1),
                        StreamOffset.create(mqName, ReadOffset.from("0"))
                );

                if (pendingList == null || pendingList.isEmpty()) {
                    break;
                }

                MapRecord<String, Object, Object> record = pendingList.get(0);
                Map<Object, Object> value = record.getValue();
                VoucherOrder order = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                self.createVoucherOrder(order);

            } catch (Exception e) {
                log.error("处理 pendingList 订单失败", e);
                try {
                    Thread.sleep(1000);   // 避免死循环，休眠一会儿再重试
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    @Override
    public ResponseEntity<Result> seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");

        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT, Collections.emptyList(),
                voucherId.toString(), userId.toString(), orderId + "");

        if (result == null) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Result.fail("系统繁忙，请稍后再试"));
        }
        if (result != 0) {
            String msg = result == 1 ? "库存不足" : "不能重复下单";
            return ResponseEntity.badRequest().body(Result.fail(msg));
        }

        VoucherOrder order = new VoucherOrder();
        order.setId(orderId);
        order.setUserId(userId);
        order.setVoucherId(voucherId);

//        if (!orderTasks.offer(order)) {
//            // 队列满了：把 Lua 里扣掉的库存和记录的用户还回去（key 名按你的脚本改）
//            stringRedisTemplate.opsForValue().increment("seckill:stock:" + voucherId);
//            stringRedisTemplate.opsForSet().remove("seckill:order:" + voucherId, userId.toString());
//            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
//                    .body(Result.fail("系统繁忙，请稍后再试"));
//        }

        return ResponseEntity.ok(Result.ok(orderId));
    }


//    @Override
//    public ResponseEntity<Result> seckillVoucher(Long voucherId) {
//        Long userId = UserHolder.getUser().getId();
//
//        Long result = stringRedisTemplate.execute(
//                SECKILL_SCRIPT, Collections.emptyList(),
//                voucherId.toString(), userId.toString());
//
//        if (result == null) {
//            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
//                    .body(Result.fail("系统繁忙，请稍后再试"));
//        }
//        if (result != 0) {
//            String msg = result == 1 ? "库存不足" : "不能重复下单";
//            return ResponseEntity.badRequest().body(Result.fail(msg));
//        }
//
//        long orderId = redisIdWorker.nextId("order");
//        VoucherOrder order = new VoucherOrder();
//        order.setId(orderId);
//        order.setUserId(userId);
//        order.setVoucherId(voucherId);
//
//        if (!orderTasks.offer(order)) {
//            // 队列满了：把 Lua 里扣掉的库存和记录的用户还回去（key 名按你的脚本改）
//            stringRedisTemplate.opsForValue().increment("seckill:stock:" + voucherId);
//            stringRedisTemplate.opsForSet().remove("seckill:order:" + voucherId, userId.toString());
//            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
//                    .body(Result.fail("系统繁忙，请稍后再试"));
//        }
//
//        return ResponseEntity.ok(Result.ok(orderId));
//    }


    @Override
    @Transactional
    public void createVoucherOrder(VoucherOrder order) {
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", order.getVoucherId())
                .gt("stock", 0)
                .update();
        if (!success) {
            log.error("数据库库存不足，与 Redis 不一致，订单 {}", order.getId());
            return;
        }
        save(order);   // 重复下单会触发唯一索引，抛 DuplicateKeyException，扣库存一起回滚
    }
}