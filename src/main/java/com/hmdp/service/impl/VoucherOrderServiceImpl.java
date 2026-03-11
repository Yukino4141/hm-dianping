package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.OrderCreatedEvent;
import com.hmdp.dto.SeckillOrderMessage;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.mq.SeckillOrderProducer;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    private final ISeckillVoucherService seckillVoucherService;
    private final RedissonClient redissonClient;
    private final SeckillOrderProducer seckillOrderProducer;

    public VoucherOrderServiceImpl(
            ISeckillVoucherService seckillVoucherService,
            RedissonClient redissonClient,
            SeckillOrderProducer seckillOrderProducer
    ) {
        this.seckillVoucherService = seckillVoucherService;
        this.redissonClient = redissonClient;
        this.seckillOrderProducer = seckillOrderProducer;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handleSeckillOrder(SeckillOrderMessage message) {
        Long userId = message.getUserId();
        Long voucherId = message.getVoucherId();

        RLock lock = redissonClient.getLock(RedisConstants.LOCK_ORDER_KEY + voucherId + ":" + userId);
        boolean locked = false;
        try {
            locked = lock.tryLock(1, 10, TimeUnit.SECONDS);
            if (!locked) {
                throw new IllegalStateException("acquire order lock failed");
            }

            Long exists = this.baseMapper.selectCount(Wrappers.<VoucherOrder>lambdaQuery()
                    .eq(VoucherOrder::getUserId, userId)
                    .eq(VoucherOrder::getVoucherId, voucherId));
            if (exists > 0) {
                return;
            }

            boolean stockSuccess = seckillVoucherService.update()
                    .setSql("stock = stock - 1")
                    .eq("voucher_id", voucherId)
                    .gt("stock", 0)
                    .update();
            if (!stockSuccess) {
                log.warn("create order failed due to no db stock, message={}", message);
                return;
            }

            VoucherOrder voucherOrder = new VoucherOrder();
            voucherOrder.setId(message.getOrderId());
            voucherOrder.setUserId(userId);
            voucherOrder.setVoucherId(voucherId);
            this.save(voucherOrder);

            OrderCreatedEvent event = new OrderCreatedEvent();
            event.setOrderId(message.getOrderId());
            event.setUserId(userId);
            event.setVoucherId(voucherId);
            seckillOrderProducer.sendOrderCreatedEvent(event);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("acquire order lock interrupted", e);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
