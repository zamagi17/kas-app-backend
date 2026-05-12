package com.zamagi.kas.security;

import java.util.Arrays;
import java.util.List;
import java.nio.file.Paths;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class WebSecurityConfig {

    @Autowired
    private AuthEntryPointJwt unauthorizedHandler;

    @Autowired
    private AuthTokenFilter authTokenFilter;

    @Autowired
    private RateLimitFilter rateLimitFilter;

    // Baca allowed origins dari application.properties
    // Contoh: app.cors.allowed-origins=https://zamagi.app,https://www.zamagi.app
    // Fallback ke "*" untuk development
    @Value("${app.cors.allowed-origins:*}")
    private String allowedOriginsRaw;

    @Value("${app.upload.avatar-dir:uploads/avatars}")
    private String avatarUploadDirRaw;

    @Bean
    public PasswordEncoder passwordEncoder() {
        // BCrypt strength 12 lebih aman dari default 10, masih performa wajar
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .exceptionHandling(exception ->
                        exception.authenticationEntryPoint(unauthorizedHandler))
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/**").permitAll()  // login, register, refresh
                        .requestMatchers("/api/health").permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/avatars/**").permitAll()  // avatar files publik
                        .anyRequest().authenticated()
                );

        http.addFilterBefore(authTokenFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();

        // Parse allowed origins dari config (bisa multiple, dipisah koma)
        if ("*".equals(allowedOriginsRaw.trim())) {
            // Development mode: izinkan semua (tetap aman karena stateless JWT)
            configuration.setAllowedOriginPatterns(List.of("*"));
        } else {
            // Production: whitelist domain spesifik
            List<String> origins = Arrays.stream(allowedOriginsRaw.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
            configuration.setAllowedOrigins(origins);
        }

        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        configuration.setAllowedHeaders(Arrays.asList("Authorization", "Content-Type", "X-Requested-With"));
        configuration.setExposedHeaders(List.of("Authorization")); // expose header ke frontend
        configuration.setMaxAge(3600L); // cache preflight 1 jam

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration() {
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(rateLimitFilter);
        registration.addUrlPatterns("/api/auth/*");
        registration.setOrder(1);
        return registration;
    }

    @Bean
    public WebMvcConfigurer webMvcConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addResourceHandlers(ResourceHandlerRegistry registry) {
                String avatarResourceLocation = Paths.get(avatarUploadDirRaw)
                        .toAbsolutePath()
                        .normalize()
                        .toUri()
                        .toString();
                if (!avatarResourceLocation.endsWith("/")) {
                    avatarResourceLocation = avatarResourceLocation + "/";
                }
                registry.addResourceHandler("/avatars/**")
                        .addResourceLocations(avatarResourceLocation)
                        .setCachePeriod(3600); // cache 1 jam
            }
        };
    }
}
