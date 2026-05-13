package com.zamagi.kas.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;
import java.security.Key;
import java.util.Date;
import org.springframework.beans.factory.annotation.Value;
import java.util.Base64;

@Component
public class JwtUtils {

    @Value("${app.jwt.secret}")
    private String jwtSecret;

    // Access token: 15 menit (900_000 ms)
    // Diambil dari config, fallback 900000
    @Value("${app.jwt.expiration-ms:900000}")
    private int jwtExpirationMs;

    // Refresh token: 7 hari (604_800_000 ms)
    @Value("${app.jwt.refresh-expiration-ms:604800000}")
    private long refreshExpirationMs;

    // Gunakan Base64-decoded key agar panjang key selalu aman (>= 256 bit)
    private Key getSigningKey() {
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(jwtSecret);
        } catch (IllegalArgumentException e) {
            // Fallback jika secret bukan Base64 (backward-compatible)
            keyBytes = jwtSecret.getBytes();
        }
        // Pastikan panjang key >= 32 byte (256 bit) untuk HS256
        if (keyBytes.length < 32) {
            byte[] padded = new byte[32];
            System.arraycopy(keyBytes, 0, padded, 0, keyBytes.length);
            keyBytes = padded;
        }
        return Keys.hmacShaKeyFor(keyBytes);
    }

    // ── ACCESS TOKEN (15 menit) ──────────────────────────────────────────────

    public String generateAccessToken(String username) {
        return generateAccessToken(username, "LOCAL");
    }

    public String generateAccessToken(String username, String loginProvider) {
        return Jwts.builder()
                .setSubject(username)
                .claim("type", "access")
                .claim("loginProvider", normalizeLoginProvider(loginProvider))
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + jwtExpirationMs))
                .signWith(getSigningKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    // ── REFRESH TOKEN (7 hari) ───────────────────────────────────────────────

    public String generateRefreshToken(String username) {
        return generateRefreshToken(username, "LOCAL");
    }

    public String generateRefreshToken(String username, String loginProvider) {
        return Jwts.builder()
                .setSubject(username)
                .claim("type", "refresh")
                .claim("loginProvider", normalizeLoginProvider(loginProvider))
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + refreshExpirationMs))
                .signWith(getSigningKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    // ── PARSE ────────────────────────────────────────────────────────────────

    public String getUserNameFromJwtToken(String token) {
        return parseClaims(token).getSubject();
    }

    public String getTokenType(String token) {
        return (String) parseClaims(token).get("type");
    }

    public Date getExpirationFromToken(String token) {
        return parseClaims(token).getExpiration();
    }

    public String getLoginProviderFromToken(String token) {
        Object loginProvider = parseClaims(token).get("loginProvider");
        return loginProvider == null ? null : loginProvider.toString();
    }

    private String normalizeLoginProvider(String loginProvider) {
        if (loginProvider == null || loginProvider.isBlank()) {
            return "LOCAL";
        }
        return loginProvider.trim().toUpperCase();
    }

    private Claims parseClaims(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(getSigningKey())
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    // ── VALIDASI ─────────────────────────────────────────────────────────────

    public boolean validateJwtToken(String token) {
        try {
            Jwts.parserBuilder()
                    .setSigningKey(getSigningKey())
                    .setAllowedClockSkewSeconds(60)
                    .build()
                    .parseClaimsJws(token);
            return true;
        } catch (ExpiredJwtException e) {
            System.out.println("JWT expired: " + e.getMessage());
        } catch (UnsupportedJwtException e) {
            System.out.println("JWT unsupported: " + e.getMessage());
        } catch (MalformedJwtException e) {
            System.out.println("JWT malformed: " + e.getMessage());
        } catch (Exception e) {
            System.out.println("JWT error: " + e.getMessage());
        }
        return false;
    }

    // Validasi dan pastikan ini adalah access token (bukan refresh)
    public boolean validateAccessToken(String token) {
        if (!validateJwtToken(token)) return false;
        try {
            String type = getTokenType(token);
            return "access".equals(type);
        } catch (Exception e) {
            // Token lama (sebelum ada claim "type") — tetap diterima untuk backward-compat
            return true;
        }
    }

    // Validasi khusus refresh token
    public boolean validateRefreshToken(String token) {
        if (!validateJwtToken(token)) return false;
        try {
            return "refresh".equals(getTokenType(token));
        } catch (Exception e) {
            return false;
        }
    }

    // ── BACKWARD COMPAT: generateJwtToken tetap ada ──────────────────────────
    // Supaya AuthController yang sudah ada tidak perlu diubah besar-besaran
    public String generateJwtToken(String username) {
        return generateAccessToken(username);
    }
}
