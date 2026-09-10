package dev.yoonsooc.device_registry.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("device.registry")
public record DeviceRegistryProperties(String serverId, Duration ttl) {
}
