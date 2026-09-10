package dev.yoonsooc.device_registry.data;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

public record DeviceStatus(String deviceId, String status, String serverId, Instant lastSeen) {
    public static DeviceStatus from(String deviceId, Map<String, String> origin) {
        return new DeviceStatus(
                deviceId,
                Optional.ofNullable(origin.get("status")).orElse("OFFLINE"),
                origin.get("server_id"),
                Optional.ofNullable(origin.get("last_seen")).map(Instant::parse).orElse(null));
    }
}