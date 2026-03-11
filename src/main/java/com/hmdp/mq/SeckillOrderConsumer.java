package com.hmdp.mq;

import com.hmdp.dto.SeckillOrderMessage;
import com.hmdp.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.MessageModel;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RocketMQMessageListener(
        topic = "${hmdp.rocketmq.topics.seckill-order:topic_seckill_order}",
        consumerGroup = "${hmdp.rocketmq.consumer-group.seckill-order:hmdp-seckill-order-consumer}",
        consumeMode = ConsumeMode.CONCURRENTLY,
        messageModel = MessageModel.CLUSTERING,
        maxReconsumeTimes = 16
)
public class SeckillOrderConsumer implements RocketMQListener<SeckillOrderMessage> {

    private final IVoucherOrderService voucherOrderService;

    public SeckillOrderConsumer(IVoucherOrderService voucherOrderService) {
        this.voucherOrderService = voucherOrderService;
    }

    @Override
    public void onMessage(SeckillOrderMessage message) {
        log.debug("consume seckill order message: {}", message);
        voucherOrderService.handleSeckillOrder(message);
    }
}
