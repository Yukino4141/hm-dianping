package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.SeckillOrderMessage;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.mapper.VoucherMapper;
import com.hmdp.mq.SeckillOrderProducer;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SentinelResources;
import com.hmdp.utils.UserHolder;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class VoucherServiceImpl extends ServiceImpl<VoucherMapper, Voucher> implements IVoucherService {

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    private static final DefaultRedisScript<Long> SECKILL_ROLLBACK_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);

        SECKILL_ROLLBACK_SCRIPT = new DefaultRedisScript<>();
        SECKILL_ROLLBACK_SCRIPT.setLocation(new ClassPathResource("seckill_rollback.lua"));
        SECKILL_ROLLBACK_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private SeckillOrderProducer seckillOrderProducer;

    @Override
    public Result queryVoucherOfShop(Long shopId) {
        String key = RedisConstants.CACHE_VOUCHER_LIST_KEY + shopId;
        String voucherJson = stringRedisTemplate.opsForValue().get(key);
        if (voucherJson != null) {
            if (voucherJson.isEmpty()) {
                return Result.ok(Collections.emptyList());
            }
            List<Voucher> vouchers = JSONUtil.toList(voucherJson, Voucher.class);
            refreshSeckillStockFromRedis(vouchers);
            return Result.ok(vouchers);
        }

        List<Voucher> vouchers = getBaseMapper().queryVoucherOfShop(shopId);
        if (CollectionUtils.isEmpty(vouchers)) {
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return Result.ok(Collections.emptyList());
        }

        long ttl = RedisConstants.CACHE_VOUCHER_LIST_TTL + ThreadLocalRandom.current().nextLong(5);
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(vouchers), ttl, TimeUnit.MINUTES);
        refreshSeckillStockFromRedis(vouchers);
        return Result.ok(vouchers);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addSeckillVoucher(Voucher voucher) {
        save(voucher);

        SeckillVoucher seckillVoucher = new SeckillVoucher();
        seckillVoucher.setVoucherId(voucher.getId());
        seckillVoucher.setStock(voucher.getStock());
        seckillVoucher.setBeginTime(voucher.getBeginTime());
        seckillVoucher.setEndTime(voucher.getEndTime());
        seckillVoucherService.save(seckillVoucher);

        stringRedisTemplate.opsForValue().set(
                RedisConstants.SECKILL_STOCK_KEY + voucher.getId(),
                String.valueOf(voucher.getStock())
        );
        stringRedisTemplate.delete(RedisConstants.CACHE_VOUCHER_LIST_KEY + voucher.getShopId());
    }

    @Override
    @SentinelResource(value = SentinelResources.VOUCHER_SECKILL, blockHandler = "seckillVoucherBlockHandler")
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        Long orderId = redisIdWorker.nextId("order");

        if (!initStockIfAbsent(voucherId)) {
            return Result.fail("秒杀活动不存在");
        }

        String stockKey = RedisConstants.SECKILL_STOCK_KEY + voucherId;
        String orderKey = RedisConstants.SECKILL_ORDER_KEY + voucherId;
        Long luaResult = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                List.of(stockKey, orderKey),
                userId.toString()
        );

        if (luaResult == null) {
            return Result.fail("系统繁忙，请稍后重试");
        }
        if (luaResult == 1L) {
            return Result.fail("库存不足");
        }
        if (luaResult == 2L) {
            return Result.fail("不可重复下单");
        }

        SeckillOrderMessage message = new SeckillOrderMessage();
        message.setOrderId(orderId);
        message.setUserId(userId);
        message.setVoucherId(voucherId);

        try {
            seckillOrderProducer.sendSeckillOrder(message);
        } catch (Exception e) {
            stringRedisTemplate.execute(
                    SECKILL_ROLLBACK_SCRIPT,
                    List.of(stockKey, orderKey),
                    userId.toString()
            );
            log.error("send seckill order message failed, rollback redis, message={}", message, e);
            return Result.fail("系统繁忙，请稍后重试");
        }

        return Result.ok(orderId);
    }

    private boolean initStockIfAbsent(Long voucherId) {
        String stockKey = RedisConstants.SECKILL_STOCK_KEY + voucherId;
        Boolean hasKey = stringRedisTemplate.hasKey(stockKey);
        if (Boolean.TRUE.equals(hasKey)) {
            return true;
        }

        RLock lock = redissonClient.getLock(RedisConstants.LOCK_SECKILL_STOCK_INIT_KEY + voucherId);
        boolean locked = false;
        try {
            locked = lock.tryLock(1, 5, TimeUnit.SECONDS);
            if (!locked) {
                return Boolean.TRUE.equals(stringRedisTemplate.hasKey(stockKey));
            }

            hasKey = stringRedisTemplate.hasKey(stockKey);
            if (Boolean.TRUE.equals(hasKey)) {
                return true;
            }

            SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
            if (voucher == null) {
                return false;
            }
            if (voucher.getBeginTime().isAfter(LocalDateTime.now()) || voucher.getEndTime().isBefore(LocalDateTime.now())) {
                return false;
            }
            stringRedisTemplate.opsForValue().set(stockKey, String.valueOf(voucher.getStock()));
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private void refreshSeckillStockFromRedis(List<Voucher> vouchers) {
        if (CollectionUtils.isEmpty(vouchers)) {
            return;
        }

        List<Voucher> seckillVouchers = new ArrayList<>();
        List<String> stockKeys = new ArrayList<>();
        for (Voucher voucher : vouchers) {
            if (voucher.getStock() != null) {
                seckillVouchers.add(voucher);
                stockKeys.add(RedisConstants.SECKILL_STOCK_KEY + voucher.getId());
            }
        }
        if (seckillVouchers.isEmpty()) {
            return;
        }

        List<String> redisStocks = stringRedisTemplate.opsForValue().multiGet(stockKeys);
        for (int i = 0; i < seckillVouchers.size(); i++) {
            Voucher voucher = seckillVouchers.get(i);
            String stockValue = redisStocks == null ? null : redisStocks.get(i);
            if (stockValue == null) {
                boolean initialized = initStockIfAbsent(voucher.getId());
                if (initialized) {
                    stockValue = stringRedisTemplate.opsForValue().get(stockKeys.get(i));
                }
            }
            if (stockValue == null) {
                continue;
            }
            try {
                voucher.setStock(Integer.parseInt(stockValue));
            } catch (NumberFormatException e) {
                log.warn("invalid seckill stock in redis, voucherId={}, stock={}", voucher.getId(), stockValue);
            }
        }
    }

    public Result seckillVoucherBlockHandler(Long voucherId, BlockException ex) {
        log.warn("voucher seckill blocked by sentinel, voucherId={}, rule={}", voucherId, ex.getRule());
        return Result.fail("当前抢购人数较多，请稍后重试");
    }
}
