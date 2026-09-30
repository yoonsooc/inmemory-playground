package dev.yoonsooc.device_registry.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import dev.yoonsooc.device_registry.config.DeviceRegistryProperties;
import dev.yoonsooc.device_registry.data.DeviceStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 서버 간 공유 상태(Valkey)만 다룬다. 인스턴스 로컬 상태는 갖지 않는다.
 *
 * <pre>
 * device:{id}    HASH  server_id / status / last_seen   (TTL, 연결 주인 서버가 갱신)
 * devices:known  SET   한 번이라도 연결한 디바이스 id       (만료된 디바이스를 OFFLINE으로 보여주기 위한 목록)
 * </pre>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeviceRegistryService {

    private static final String KNOWN_DEVICES_KEY = "devices:known";

    private final StringRedisTemplate redis;
    private final RedisScript<Long> registerScript;
    private final RedisScript<Long> unregisterScript;
    private final DeviceRegistryProperties properties;

    /** SSE 연결이 맺은 직후 호출 */
    public void register(String deviceId) {
        boolean isNew = redis.opsForSet().add(KNOWN_DEVICES_KEY, deviceId) == 1;
        upsert(deviceId);
        log.info("Device registered: id={}, server={}, new={}", deviceId, properties.serverId(), isNew);
    }

    /** ping 쓰기에 성공할 때마다 호출한다. 기록이 만료되어 있었다면 여기서 되살아난다. */
    public void renew(String deviceId) {
        upsert(deviceId);
        log.debug("Lease renewed: id={}, server={}", deviceId, properties.serverId());
    }

    /** 연결이 끊긴 서버가 호출. 디바이스가 이미 다른 서버로 옮겨갔다면 아무것도 지우지 않음 */
    public void unregister(String deviceId) {
        Long deleted = redis.execute(unregisterScript, List.of(key(deviceId)), properties.serverId());
        if (deleted != null && deleted == 1) {
            log.info("Device unregistered: id={}, server={}", deviceId, properties.serverId());
        } else {
            log.info("Device record kept (moved or expired): id={}, server={}", deviceId, properties.serverId());
        }
    }

    public Optional<DeviceStatus> find(String deviceId) {
        Map<String, String> fields = redis.<String, String>opsForHash().entries(key(deviceId));
        boolean unknown = fields.isEmpty() && !redis.opsForSet().isMember(KNOWN_DEVICES_KEY, deviceId);
        if (unknown) {
            return Optional.empty();
        }
        return Optional.of(DeviceStatus.from(deviceId, fields));
    }

    public List<DeviceStatus> findAll() {
        return redis.opsForSet().members(KNOWN_DEVICES_KEY).stream()
                .sorted()
                .map(deviceId -> DeviceStatus.from(deviceId,
                        redis.<String, String>opsForHash().entries(key(deviceId))))
                .toList();
    }

    /** 등록과 갱신은 동일한 Write에 해당 */
    private void upsert(String deviceId) {
        redis.execute(registerScript, List.of(key(deviceId)),
                String.valueOf(properties.ttl().toSeconds()),
                "server_id", properties.serverId(),
                "status", "ONLINE",
                "last_seen", Instant.now().toString());
    }

    private String key(String deviceId) {
        return "device:" + deviceId;
    }
}
