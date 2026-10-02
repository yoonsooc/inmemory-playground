package dev.yoonsooc.device_registry.data;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import dev.yoonsooc.device_registry.config.DeviceRegistryProperties;
import dev.yoonsooc.device_registry.service.DeviceRegistryService;
import lombok.extern.slf4j.Slf4j;

/**
 * 현재 서버 인스턴스가 직접 쥐고 있는 SSE 연결 == "서버 메모리에 든 연결 정보"
 * 다른 서버는 이 맵을 볼 수 없으므로, 누가 누구를 쥐고 있는지는 {@link DeviceRegistryService}를 통해
 * 인메모리 DB에 기록한다.
 * <p>
 * 연결을 끝내는 규칙: 끝내기로 정한 자리에서는 {@code emitter().complete()}만 부른다.
 * 맵에서 빼고 인메모리 DB 기록을 지우는 일은 그 뒤에 Spring이 부르는 {@link #onConnectionClosed}가 한다.
 * 쓰기가 실패한 emitter는 complete()를 부르기 전에 Tomcat이 먼저 오류를 통지해서 같은 정리가 시작된다.
 */
@Component
@Slf4j
public class DeviceConnectionManager {

    // ConcurrentHashMap -> SSE 연결/해제 요청 스레드 vs PING 전용 스케줄러 스레드 간 경합 배제
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();

    private final DeviceRegistryService registry;
    private final DeviceRegistryProperties properties;

    private record Connection(SseEmitter emitter, long epoch) {
    }

    public DeviceConnectionManager(DeviceRegistryService registry, DeviceRegistryProperties properties) {
        this.registry = registry;
        this.properties = properties;
    }

    public SseEmitter connect(String deviceId) {
        long currentEpoch = System.currentTimeMillis();
        SseEmitter emitter = new SseEmitter(0L); // No Servlet Timeout. 끊김은 ping 쓰기 실패로 감지
        Connection newConnection = new Connection(emitter, currentEpoch);

        emitter.onCompletion(() -> onConnectionClosed(deviceId, newConnection, "completed"));
        emitter.onTimeout(() -> onConnectionClosed(deviceId, newConnection, "timeout"));
        emitter.onError(e -> onConnectionClosed(deviceId, newConnection, "error: " + e));

        Connection previous = connections.put(deviceId, newConnection);
        if (previous != null) { // 같은 서버로 재연결한 경우. 옛 Connection의 콜백은 인스턴스가 달라 새 Connection을 지우지 못한다 (ADR-0004).
            log.warn("Replacing stale connection: id={}", deviceId);
            previous.emitter().complete();
        }

        try {
            boolean accepted = registry.register(deviceId, currentEpoch); // 연결 즉시 기록. 다음 ping까지 기다리지 않는다 (ADR-0003).
            if (!accepted) {
                // 드물어야 한다. 같은 기기에서 연달아 찍히면 서버 시계나 기기 id 중복을 의심한다 (ADR-0006).
                log.warn("Register rejected, a newer connection is already recorded: id={}, epoch={}",
                        deviceId, currentEpoch);
                emitter.complete();
                return emitter;
            }

        } catch (RuntimeException e) {
            log.warn("Register failed, will retry on next ping: id={}, cause={}", deviceId, e.toString());
        }

        trySendMessage(deviceId, emitter, "connected", Map.of("serverId", properties.serverId()));
        log.info("SSE connected: id={}, local connections={}", deviceId, connections.size());
        return emitter;
    }

    /** 이 서버가 SSE 연결을 쥐고 있을 때만 명령을 보낸다. */
    public boolean commandDevice(String deviceId, String event, Object data) {
        Connection connection = connections.get(deviceId);
        if (connection == null) {
            return false;
        }
        if (!trySendMessage(deviceId, connection.emitter(), event, data)) {
            connection.emitter().complete();
            return false;
        }
        return true;
    }

    public int size() {
        return connections.size();
    }

    /**
     * 서버 → 디바이스 하트비트 + 인메모리 DB 갱신
     * fixedDelay: 한 주기가 길어져도 다음 실행이 겹치거나 몰리지 않는다.
     * 한 디바이스의 실패가 나머지 디바이스의 갱신을 막지 않도록 항목마다 따로 처리한다.
     */
    @Scheduled(fixedDelayString = "${device.registry.ping}")
    public void ping() {
        connections.forEach(this::pingAndExtend);
    }

    private void pingAndExtend(String deviceId, Connection connection) {
        if (!trySendMessage(deviceId, connection.emitter(), "ping", properties.serverId())) {
            connection.emitter().complete();
            return;
        }

        try {
            boolean accepted = registry.renew(deviceId, connection.epoch());
            if (!accepted) {
                // 기기가 이미 다른 연결로 옮겨간 뒤다. 낡은 연결이 정리되는 정상 동작이다.
                log.info("Renew rejected, a newer connection exists: id={}, epoch={}", deviceId, connection.epoch());
                connection.emitter().complete();
            }
        } catch (RuntimeException e) {
            log.warn("Lease renew failed: id={}, cause={}", deviceId, e.toString());
        }
    }

    private boolean trySendMessage(String deviceId, SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
            return true;
        } catch (IOException | IllegalStateException e) {
            // - IOException: 소켓이 죽음.
            // - IllegalStateException: 이미 완료된 emitter. 닫는 일은 부른 쪽이 한다.
            log.warn("SSE send failed: id={}, event={}, cause={}", deviceId, event, e.toString());
            return false;
        }
    }

    private void onConnectionClosed(String deviceId, Connection connection, String reason) {
        // 맵에 든 것이 바로 이 연결일 때만 제거한다. 재연결로 새 연결이 들어와 있으면 건드리지 않는다.
        if (!connections.remove(deviceId, connection)) {
            log.debug("SSE disconnect ignored (already replaced or removed): id={}, reason={}", deviceId, reason);
            return;
        }
        log.info("SSE disconnected: id={}, reason={}, local connections={}", deviceId, reason, connections.size());
        try {
            registry.deleteIfMine(deviceId, connection.epoch());
        } catch (RuntimeException e) {
            log.warn("Unregister failed, TTL will expire it: id={}, cause={}", deviceId, e.toString());
        }
    }
}
