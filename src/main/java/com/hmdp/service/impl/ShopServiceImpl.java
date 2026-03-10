package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.json.JSONUtil;
import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SentinelResources;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    // 1. 注入 RedissonClient
    @Autowired
    private RedissonClient redissonClient;

    // 定义布隆过滤器
    private RBloomFilter<Long> shopBloomFilter;

    // 2. 在项目启动时初始化并预热布隆过滤器
    @PostConstruct
    public void initBloomFilter() {
        // 获取布隆过滤器
        shopBloomFilter = redissonClient.getBloomFilter(RedisConstants.BLOOM_SHOP_ID_KEY);

        // 初始化布隆过滤器
        // 参数1: 预计插入的元素数量
        // 参数2: 期望的误判率 (0.01 表示 1%)
        // 注意：只有在布隆过滤器不存在时，tryInit 才会生效
        shopBloomFilter.tryInit(10000L, 0.01);

        // 预热：从数据库查询所有商铺的 ID，写入布隆过滤器
        List<Shop> shops = this.list(); // MyBatis-Plus 提供的全量查询
        if (shops != null && !shops.isEmpty()) {
            for (Shop shop : shops) {
                shopBloomFilter.add(shop.getId());
            }
        }
    }
    @Override
    @SentinelResource(value = SentinelResources.SHOP_QUERY_BY_ID, blockHandler = "queryByIdBlockHandler")
    public Result queryById(Long id) {
        if (!shopBloomFilter.contains(id)) {
            // 如果布隆过滤器判断不存在，直接快速失败，不再查Redis和数据库
            log.debug("布隆过滤器判断店铺不存在！");
            return Result.fail("店铺不存在");

        }
        String key= RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if(shopJson != null){
            Shop shop = BeanUtil.toBean(shopJson, Shop.class);
            if(shop != null){
                return Result.ok(shop);
            }
            return Result.fail("店铺不存在");
        }
        Shop shop = this.getById(id);
        if(shop == null){

            // 缓存穿透
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return Result.fail("店铺不存在");
        }
        long ttl = RedisConstants.CACHE_SHOP_TTL + ThreadLocalRandom.current()
                .nextLong(RedisConstants.CACHE_SHOP_TTL_RANDOM_BOUND + 1);
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), ttl, TimeUnit.MINUTES);
        return Result.ok(shop);

    }

    public Result queryByIdBlockHandler(Long id, BlockException ex) {
        log.warn("shop query blocked by sentinel, shopId={}, rule={}", id, ex.getRule());
        return Result.fail("当前访问量过高，请稍后重试");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result update(Shop shop) {
        Long id = shop.getId();
        if(id == null){
            return Result.fail("店铺id不能为空");
        }
        this.updateById(shop);
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + id);
        return Result.ok();

    }

    /**
     *
     * @param entity
     * @return
     */
    // 在 ShopServiceImpl.java 中添加
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean save(Shop entity) {
        // 1. 先保存进数据库
        boolean success = super.save(entity);
        // 2. 数据库保存成功后，将新生成的 ID 加入布隆过滤器
        if (success && entity.getId() != null) {
            shopBloomFilter.add(entity.getId());
        }
        return success;
    }
}
