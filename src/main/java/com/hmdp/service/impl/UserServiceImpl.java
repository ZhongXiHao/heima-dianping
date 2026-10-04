package com.hmdp.service.impl;

import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Override
    public Result sendCode(String phone, HttpSession session) {
        // check if the phone number is valid
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("Invalid phone number");
        }

        // generate a random 6-digit code and store it in the session with the phone number as the key
        String code = RandomUtil.randomNumbers(6);
        session.setAttribute("code:" + phone, code);

        // send the code to the phone number (this is a placeholder, actual implementation would involve an SMS service)
        log.debug("Sending code {} to phone number {}", code, phone);
        return Result.ok();
    }
}
