package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public List<ShopType> querylist() {
        // 1.查询缓存


        String shopTypeJson = stringRedisTemplate.opsForValue().get("cache:shopType:");
        //2.如果存在缓存直接返回
        if(shopTypeJson != null){
            List<ShopType> shopTypeList = JSONUtil.toList(shopTypeJson, ShopType.class);
            return shopTypeList;

        }

        //3.不存在缓存，查询数据库
        List<ShopType> shopTypeList = this.query().orderByAsc("sort").list();

        //4.数据库不存在，返回错误
        if(shopTypeList == null){
            Result.fail("店铺不存在");
        }

        //5.数据库存在，写入缓存

        stringRedisTemplate.opsForValue().set("cache:shopType:", JSONUtil.toJsonStr(shopTypeList));
        //6.返回结果
        return shopTypeList;
    }
}
