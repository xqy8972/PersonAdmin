package com.project.person.admin.service;

import com.project.person.admin.common.BizException;
import com.project.person.admin.mapper.SysUserMapper;
import com.project.person.admin.model.SysUser;
import com.project.person.admin.util.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;

@Service
public class AuthService {

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private JwtUtil jwtUtil;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public void register(String username, String password, String nickname) {
        if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
            throw BizException.badRequest("用户名和密码不能为空");
        }
        if (sysUserMapper.findByUsername(username) != null) {
            throw BizException.conflict("用户名已存在");
        }
        SysUser user = new SysUser();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(password));
        user.setNickname(StringUtils.hasText(nickname) ? nickname : username);
        sysUserMapper.insert(user);
    }

    public Map<String, Object> login(String username, String password) {
        if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
            throw BizException.badRequest("用户名和密码不能为空");
        }
        SysUser user = sysUserMapper.findByUsername(username);
        if (user == null || !passwordEncoder.matches(password, user.getPassword())) {
            throw BizException.unauthorized("用户名或密码错误");
        }
        Map<String, Object> data = new HashMap<>();
        data.put("token", jwtUtil.generateToken(user.getId(), user.getUsername()));
        data.put("nickname", user.getNickname());
        return data;
    }
}
