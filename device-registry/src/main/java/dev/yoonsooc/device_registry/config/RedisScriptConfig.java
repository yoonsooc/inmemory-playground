package dev.yoonsooc.device_registry.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.RedisScript;

@Configuration
public class RedisScriptConfig {

    @Bean
    public RedisScript<Long> writeIfNewestScript() {
        return RedisScript.of(new ClassPathResource("scripts/write_if_newest.lua"), Long.class);
    }

    @Bean
    public RedisScript<Long> deleteIfMineScript() {
        return RedisScript.of(new ClassPathResource("scripts/delete_if_mine.lua"), Long.class);
    }
}
