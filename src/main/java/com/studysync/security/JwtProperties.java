package com.studysync.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.jwt")
public class JwtProperties {
    private String secret;
    private long expiresMinutes = 60 * 24; // 24h default

    public String getSecret() { return secret; }
    public void setSecret(String secret) { this.secret = secret; }

    public long getExpiresMinutes() { return expiresMinutes; }
    public void setExpiresMinutes(long expiresMinutes) { this.expiresMinutes = expiresMinutes; }
}
