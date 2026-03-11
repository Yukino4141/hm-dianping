package com.hmdp.mq;

import com.hmdp.dto.OrderCreatedEvent;
import com.hmdp.dto.SeckillOrderMessage;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class SeckillOrderProducer {

    private final RocketMQTemplate rocketMQTemplate;

    @Value("${hmdp.rocketmq.topics.seckill-order:topic_seckill_order}")
    private String seckillOrderTopic;

    @Value("${hmdp.rocketmq.topics.order-created:topic_order_created}")
    private String orderCreatedTopic;

    public SeckillOrderProducer(RocketMQTemplate rocketMQTemplate) {
        this.rocketMQTemplate = rocketMQTemplate;
    }

    public void sendSeckillOrder(SeckillOrderMessage message) {
        rocketMQTemplate.convertAndSend(seckillOrderTopic, message);
    }

    public void sendOrderCreatedEvent(OrderCreatedEvent event) {
        try {
            rocketMQTemplate.convertAndSend(orderCreatedTopic, event);
        } catch (Exception e) {
            log.error("send order created event failed, event={}", event, e);
        }
    }
}
