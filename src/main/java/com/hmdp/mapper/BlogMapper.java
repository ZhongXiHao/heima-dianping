package com.hmdp.mapper;

import com.hmdp.entity.Blog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;


public interface BlogMapper extends BaseMapper<Blog> {
    Blog queryBlogById(Long id);
}
