package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;


@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Resource
    private IUserService userService;
    @Resource
    private BlogMapper blogMapper;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IFollowService followService;

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
        if (UserHolder.getUser() == null) {
            return;
        }
        Double score = stringRedisTemplate.opsForZSet().score(RedisConstants.BLOG_LIKED_KEY + blog.getId(), UserHolder.getUser().getId().toString());
        blog.setIsLike(BooleanUtil.isTrue(score != null && score > 0));
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

        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        System.out.println(score);
        if (score == null) {
            Boolean added = stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            update().setSql("liked = liked + 1").eq("id", id).update();
            if (BooleanUtil.isTrue(added)) {
                return Result.ok("Liked successfully");
            }
        } else {
            Long removed = stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            if (removed != null && removed > 0) {
                update().setSql("liked = liked - 1").eq("id", id).gt("liked", 0).update();
                return Result.ok("Unliked successfully");
            }
        }

        return Result.fail("Operation failed");
    }

    @Override
    public Result queryBlogLikes(Long id) {
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        LinkedHashSet<String> top5 = (LinkedHashSet<String>) stringRedisTemplate.opsForZSet().range(key, 0L, 4L);

        assert top5 != null;
        List<Long> top5Ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        List<User> users = userService.listByIds(top5Ids);

        // 查询出来之后转成一个map key为id value为user对象 然后用stream把ids逐一map成user就可以了 保证了原本顺序
        Map<Long, UserDTO> collect = users.stream().map(user ->
                BeanUtil.copyProperties(user, UserDTO.class)
        ).collect(Collectors.toMap(UserDTO::getId, userDTO -> userDTO, (existing, replacement) -> existing));
        List<UserDTO> userDTOS = top5Ids.stream().map(collect::get).collect(Collectors.toList());

        System.out.println(top5Ids);
        return Result.ok(userDTOS);
    }

    @Override
    public Result queryMyBlogByUserId(Integer current, Long id) {
        Page<Blog> page = query().eq("user_id", id).page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        List<Blog> records = page.getRecords();
        return Result.ok(records);
    }

    @Override
    public Result saveBlog(Blog blog) {

        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean save = save(blog);
        if (!save) {
            return Result.fail("Save failed");
        }

        List<Follow> followers = followService.query().eq("follow_user_id", user.getId()).list();
        if (followers == null || followers.isEmpty()) {
            return Result.ok();
        }

        // 推送给粉丝
        for (Follow follower : followers) {
            Long followerId = follower.getUserId();
            String key = RedisConstants.FEED_KEY + followerId;
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
        }


        // 返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfByFollow(Long max, Integer offset) {
        Long userId = UserHolder.getUser().getId();

        long minTime = 0;
        int os = 1; // 除了第一次查询，后续查询时，当前元素的都是从下一条数据开始的，所以初始值为 1

        String key = RedisConstants.FEED_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet().reverseRangeByScoreWithScores(key, 0, max, offset, 3);

        if (typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok();
        }

        List<Long> blogIds = new ArrayList<>(typedTuples.size());
        for (ZSetOperations.TypedTuple<String> typedTuple : typedTuples) {
            String blogIdStr = typedTuple.getValue();
            assert blogIdStr != null;
            blogIds.add(Long.valueOf(blogIdStr));
            long time = Objects.requireNonNull(typedTuple.getScore()).longValue();
            if (time == minTime) {
                os++;
            } else {
                minTime = time;
                os = 1;
            }
        }

        if(minTime == max){
            os += offset;
        }

        List<Blog> collect = listByIds(blogIds);
        for (Blog blog : collect) {
            Blog queryBlogById = blogMapper.queryBlogById(blog.getId());
            if (queryBlogById != null) {
                BeanUtil.copyProperties(blog, queryBlogById);
            }
            isBlogLiked(blog);
        }

        Map<Long, Blog> longBlogMap = collect.stream().collect(Collectors.toMap(
                Blog::getId, blog -> blog, (existing, replacement) -> existing));

        List<Blog> blogs = blogIds.stream().map(longBlogMap::get).collect(Collectors.toList());
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogs);
        scrollResult.setOffset(os);
        scrollResult.setMinTime(minTime);
        return Result.ok(scrollResult);
    }
}
