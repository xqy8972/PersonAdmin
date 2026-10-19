package com.project.person.admin.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * JWT 生成与解析（payload: userId / username / exp）
 */
@Component
public class JwtUtil {

    /** 签名密钥（生产环境请放入配置文件/环境变量） */
    private static final String SECRET = "person-admin-secret-key-2026";

    /** 有效期 7 天 */
    private static final long EXPIRE_MILLIS = 7 * 24 * 60 * 60 * 1000L;

    public String generateToken(Long userId, String username) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("username", username);
        return Jwts.builder()
                .setClaims(claims)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + EXPIRE_MILLIS))
                .signWith(SignatureAlgorithm.HS256, SECRET)
                .compact();
    }

    /**
     * 解析令牌，返回 Claims；无效或过期则抛异常
     */
    public Claims parseToken(String token) {
        return Jwts.parser().setSigningKey(SECRET).parseClaimsJws(token).getBody();
    }
}
