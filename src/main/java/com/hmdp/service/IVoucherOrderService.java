package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;
import org.springframework.http.ResponseEntity;

public interface IVoucherOrderService extends IService<VoucherOrder> {

    ResponseEntity<Result> seckillVoucher(Long voucherId);
}
