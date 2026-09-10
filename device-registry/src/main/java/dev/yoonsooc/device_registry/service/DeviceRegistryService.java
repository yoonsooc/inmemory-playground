package dev.yoonsooc.device_registry.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import dev.yoonsooc.device_registry.config.DeviceRegistryProperties;
import dev.yoonsooc.device_registry.data.DeviceStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class DeviceRegistryService {

    private final StringRedisTemplate redis;

    private final DeviceRegistryProperties properties;

    private static final String KNOWN_DEVICES_KEY = "devices:known";

    public void heartbeat(String deviceId) {
        boolean isNewDevice = redis.opsForSet().add(KNOWN_DEVICES_KEY, deviceId) == 1;
        if (isNewDevice) {
            log.info("Device registered: id={}, server={}", deviceId, properties.serverId());
        } else {
            log.debug("The Device already exists: id={}", deviceId);
        }

        String key = getKey(deviceId);
        redis.opsForHash().putAll(key,
                Map.of(
                        "server_id", properties.serverId(),
                        "status", "ONLINE",
                        "last_seen", Instant.now().toString()));
        redis.expire(key, properties.ttl());
    }

    public Optional<DeviceStatus> find(String deviceId) {
        String key = getKey(deviceId);
        Map<String, String> heartbeat = redis.<String, String>opsForHash().entries(key);

        boolean notExist = heartbeat.isEmpty() && !redis.opsForSet().isMember(KNOWN_DEVICES_KEY, deviceId);
        if (notExist) {
            return Optional.empty();
        }
        return Optional.of(DeviceStatus.from(deviceId, heartbeat));
    }

    public List<DeviceStatus> findAll() {
        return redis.opsForSet().members(KNOWN_DEVICES_KEY).stream()
                .map(deviceId -> DeviceStatus.from(deviceId,
                        redis.<String, String>opsForHash().entries(getKey(deviceId))))
                .toList();
    }

    private String getKey(String deviceId) {
        return String.format("device:%s", deviceId);
    }
}