package dev.yoonsooc.device_registry.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param serverId 이 인스턴스의 이름. device:{id} 해시의 server_id에 기록된다.
 * @param ttl      device:{id} 해시의 만료 시간. ping이 이 시간 안에 갱신하지 못하면 저절로 사라진다.
 * @param ping     연결 주인 서버가 SSE로 ping을 보내고 TTL을 갱신하는 주기. ttl보다 충분히 짧아야 한다.
 */
@ConfigurationProperties("device.registry")
public record DeviceRegistryProperties(String serverId, Duration ttl, Duration ping) {
}
