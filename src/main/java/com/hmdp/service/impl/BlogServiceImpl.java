package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;


@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Resource
    private IUserService userService;
    @Resource
    private BlogMapper blogMapper;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryBlogById(Long id) {
        // query blog by id with join
        Blog blog = blogMapper.queryBlogById(id);
        if (blog == null) {
            return Result.fail("Blog not found");
        }
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    private void isBlogLiked(Blog blog) {
        Boolean isMember = stringRedisTemplate.opsForSet().isMember(RedisConstants.BLOG_LIKED_KEY + blog.getId(), UserHolder.getUser().getId().toString());
        blog.setIsLike(BooleanUtil.isTrue(isMember));
    }

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            Long userId = blog.getUserId();
            User user = userService.getById(userId);
            blog.setName(user.getNickName());
            blog.setIcon(user.getIcon());
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result likeBlog(Long id) {
        Long userId = UserHolder.getUser().getId();
        String key = RedisConstants.BLOG_LIKED_KEY + id;

        Long added = stringRedisTemplate.opsForSet().add(key, userId.toString());
        if (added != null && added > 0) {
            stringRedisTemplate.opsForSet().add(key, userId.toString());
            update().setSql("liked = liked + 1").eq("id", id).update();
            return Result.ok("Liked successfully");
        } else {
            Long removed = stringRedisTemplate.opsForSet().remove(key, userId.toString());
            if (removed != null && removed > 0) {
                update().setSql("liked = liked - 1").eq("id", id).update();
                return Result.ok("Unliked successfully");
            }
        }

        return Result.fail("Operation failed");
    }
}
