package com.notificationapp.ratelimiter.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

@Configuration
public class RedisTransactionScriptConfig {

    private static final Logger log = LoggerFactory.getLogger(RedisTransactionScriptConfig.class);

    private final StringRedisTemplate redisTemplate;
    private final List<RedisScript<?>> allScripts;

    public RedisTransactionScriptConfig(StringRedisTemplate redisTemplate, @Lazy List<RedisScript<?>> allScripts) {
        this.redisTemplate = redisTemplate;
        this.allScripts = allScripts;
    }

    /**
     * Automatically pre-loads all Lua script SHAs into Redis on startup.
     * Prevents NOSCRIPT errors during low-level connection/pipelined execution.
     */
    @EventListener(ContextRefreshedEvent.class)
    public void preloadScriptsIntoRedis() {
        log.info("Pre-loading {} Redis Lua scripts into script cache...", allScripts.size());
        redisTemplate.execute((RedisCallback<Void>) (connection) -> {
            for (RedisScript<?> script : allScripts) {
                try {
                    byte[] scriptBytes = script.getScriptAsString().getBytes();
                    String sha = connection.scriptingCommands().scriptLoad(scriptBytes);
                    log.debug("Pre-loaded Lua script SHA: {}", sha);
                } catch (Exception e) {
                    log.error("Failed to pre-load Lua script with SHA: {}", script.getSha1(), e);
                }
            }
            return null;
        });
    }

    @Bean
    public RedisScript<Long> slidingWindowCounterReadScript() {
        log.info("Initializing Redis Read script bean for sliding window counter");
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/sliding_window_counter_read.lua"));
        script.setResultType(Long.class);
        return script;
    }

    @Bean
    public RedisScript<Long> slidingWindowCounterWriteScript() {
        log.info("Initializing Redis Write script bean for sliding window counter");
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/sliding_window_counter_write.lua"));
        script.setResultType(Long.class);
        return script;
    }

    @Bean
    public RedisScript<Long> slidingWindowLogWriteScript() {
        log.info("Initializing Redis Write script bean for sliding window log");
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/sliding_window_log_write.lua"));
        script.setResultType(Long.class);
        return script;
    }

    @Bean
    public RedisScript<Long> slidingWindowLogReadScript() {
        log.info("Initializing Redis Read script bean for sliding window log");
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/sliding_window_log_read.lua"));
        script.setResultType(Long.class);
        return script;
    }

    @Bean
    public RedisScript<Long> fixedWindowReadScript() {
        log.info("Initializing Redis Read script bean for fixed window");
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/fixed_window_read.lua"));
        script.setResultType(Long.class);
        return script;
    }

    @Bean
    public RedisScript<Long> fixedWindowWriteScript() {
        log.info("Initializing Redis Write script bean for fixed window");
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/fixed_window_write.lua"));
        script.setResultType(Long.class);
        return script;
    }

    @Bean
    public RedisScript<Long> tokenBucketReadScript() {
        log.info("Initializing Redis read script bean for token bucket");
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/token_bucket_read.lua"));
        script.setResultType(Long.class);
        return script;
    }

    @Bean
    public RedisScript<Long> tokenBucketWriteScript() {
        log.info("Initializing Redis write script bean for token bucket");
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/token_bucket_write.lua"));
        script.setResultType(Long.class);
        return script;
    }

    @Bean
    public RedisScript<Long> tokenBucketLeaseScript() {
        log.info("Initializing Redis script bean for token bucket lease");
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/token_bucket_lease.lua"));
        script.setResultType(Long.class);
        return script;
    }

    @Bean
    public RedisScript<Long> slidingWindowCounterScript() {
        log.info("Initializing Redis script bean for sliding window counter");
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/sliding_window_counter.lua"));
        script.setResultType(Long.class);
        return script;
    }

    @Bean
    public RedisScript<Long> slidingWindowLogScript() {
        log.info("Initializing Redis script bean for sliding window log");
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/sliding_window_log.lua"));
        script.setResultType(Long.class);
        return script;
    }

    @Bean
    public RedisScript<Long> fixedWindowScript() {
        log.info("Initializing Redis script bean for fixed window");
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/fixed_window.lua"));
        script.setResultType(Long.class);
        return script;
    }
}
