package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;


@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    private final ISeckillVoucherService seckillVoucherService;
    private final RedisIdWorker redisIdWorker;

    public VoucherOrderServiceImpl(ISeckillVoucherService seckillVoucherService, RedisIdWorker redisIdWorker) {
        this.seckillVoucherService = seckillVoucherService;
        this.redisIdWorker = redisIdWorker;
    }

    @Override
    public ResponseEntity<Result> seckillVoucher(Long voucherId) {
        // query id exists or not
        if (voucherId == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Result.fail("Voucher id is null"));
        }
        SeckillVoucher seckillVoucherById = seckillVoucherService.getById(voucherId);
        if (seckillVoucherById == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Result.fail("Voucher not found"));
        }

        // check if the seckill is started or not
        if (seckillVoucherById.getBeginTime().isAfter(LocalDateTime.now())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Result.fail("Voucher begin time is after now"));
        }

        // check if the seckill is ended or not
        if (seckillVoucherById.getEndTime().isBefore(LocalDateTime.now())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Result.fail("Voucher end time is before now"));
        }

        // check if the stock is enough or not
        if (seckillVoucherById.getStock() < 1) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Result.fail("Seckill stock is not enough"));
        }


        Long userId = UserHolder.getUser().getId();

        // 用 intern 是因为每次调用 toString() 都会创建一个新的 String 对象，而 intern() 方法会返回字符串常量池中的唯一实例，这样可以保证锁的唯一性，避免不同的线程持有不同的锁对象，从而导致锁失效的问题。
        synchronized (userId.toString().intern()) {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.crateVoucherOrder(voucherId);
        }

    }

    @NonNull
    @Transactional
    public ResponseEntity<Result> crateVoucherOrder(Long voucherId) {
        // check if the user has already ordered the voucher
        Long userId = UserHolder.getUser().getId();

        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Result.fail("User has already ordered the voucher"));
        }

        // subtract the stock and update the database
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)   // 乐观锁思路：库存大于 0 才能扣
                .update();
        if (!success) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Result.fail("Seckill stock is not enough"));
        }


        // create a new voucher order
        VoucherOrder voucherOrder = new VoucherOrder();
        long voucherOrderId = redisIdWorker.nextId("order");
        voucherOrder.setId(voucherOrderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);

        return ResponseEntity.ok(Result.ok(voucherOrderId));

    }
}
