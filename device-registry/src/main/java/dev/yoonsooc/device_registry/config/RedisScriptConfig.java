package dev.yoonsooc.device_registry.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.RedisScript;

@Configuration
public class RedisScriptConfig {

    @Bean
    public RedisScript<Long> registerScript() {
        return RedisScript.of(new ClassPathResource("scripts/register.lua"), Long.class);
    }

    @Bean
    public RedisScript<Long> unregisterScript() {
        return RedisScript.of(new ClassPathResource("scripts/unregister.lua"), Long.class);
    }
}
