package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.impl.FollowServiceImpl;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;


@RestController
@RequestMapping("/follow")
public class FollowController {
    @Resource
    public FollowServiceImpl followService;

    @PutMapping("/{id}/{isFollow}")
    public Result follow(@PathVariable Long id, @PathVariable boolean isFollow) {
        return followService.follow(id, isFollow);
    }

    @GetMapping("/or/not/{id}")
    public Result followOrNot(@PathVariable Long id) {
        return followService.followOrNot(id);
    }

    @GetMapping("/common/{id}")
    public Result followCommons(@PathVariable Long id) {
        return followService.followCommons(id);
    }


}
