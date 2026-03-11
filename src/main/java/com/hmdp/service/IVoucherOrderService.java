package com.hmdp.service;

import com.hmdp.entity.VoucherOrder;
import com.hmdp.dto.SeckillOrderMessage;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {
    void handleSeckillOrder(SeckillOrderMessage message);
}
