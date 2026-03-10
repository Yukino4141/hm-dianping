package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Autowired
    private IUserService userService;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private IFollowService followService;

    @Override
    public Result queryBlogById(Long id) {
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在！");
        }
        Long userId = blog.getUserId();

        User user = userService.getById(userId);
        blog.setIcon(user.getIcon());
        blog.setName(user.getNickName());
        Double score = stringRedisTemplate.opsForZSet().score("blog:liked:" + id, userId.toString());
        if (BooleanUtil.isTrue(score != null)) {

            blog.setIsLike(true);
        }
        return Result.ok(blog);
    }

    @Override
    public Result likeBlog(Long id) {

        UserDTO userDTO = UserHolder.getUser();
        Long userId = userDTO.getId();
        String key = "blog:liked:" + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (score == null) {
            //未点赞
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            if (isSuccess) {
                //保存用户到redis
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }

        } else {
            //已点赞
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }

        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        String key = "blog:liked:" + id;
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (top5 == null || top5.isEmpty()) {
            return Result.ok();
        }
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        List<UserDTO> userDTOS = userService.listByIds(ids).stream().map(user -> {
            UserDTO userDTO = new UserDTO();
            userDTO.setIcon(user.getIcon());
            userDTO.setId(user.getId());
            userDTO.setNickName(user.getNickName());
            return userDTO;
        }).collect(Collectors.toList());
        return Result.ok(userDTOS);

    }

    @Override
    public Result saveBlog(Blog blog) {
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        boolean isSuccess = save(blog);
        if (!isSuccess) {
            return Result.fail("新增笔记失败！");
        }
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        for (Follow follow : follows) {
            Long userId = follow.getUserId();
            String key = "feed:" + userId;
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        //获取当前用户
        Long userId = UserHolder.getUser().getId();
        String key = "feed:" + userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet().reverseRangeByScoreWithScores(key, 0, max, offset, 3);
        if (typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok();
        }
        //查询数据
        ArrayList<String> ids = new ArrayList<>(3);
        long timeStamp = 0;
        Integer off=1;
        for (ZSetOperations.TypedTuple<String> typedTuple : typedTuples) {
            if (timeStamp == typedTuple.getScore().longValue()) {
                off++;
                continue;
            }else{
                timeStamp = typedTuple.getScore().longValue();
                off=1;
            }
            String blogId = typedTuple.getValue();
            ids.add(blogId);

        }
        //封装数据
        List<Blog> blogs = query().in("id", ids).last("ORDER BY FIELD(id," + String.join(",", ids) + ")").list();
        blogs.forEach(blog -> {
            initializeBlog(blog, blog.getUserId());
        });

        ScrollResult s=new ScrollResult();
        s.setList(blogs);
        s.setOffset(off);
        s.setMinTime(timeStamp);
        return Result.ok(s);


    }

    private void initializeBlog(Blog blog, Long userId) {

        User user = userService.getById(userId);
        blog.setIcon(user.getIcon());
        blog.setName(user.getNickName());
        Double score = stringRedisTemplate.opsForZSet().score("blog:liked:" + blog.getId(), userId.toString());
        if (BooleanUtil.isTrue(score != null)) {

            blog.setIsLike(true);
        }
    }
}
