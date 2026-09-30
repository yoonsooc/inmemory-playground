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
 */
@Component
@Slf4j
public class DeviceConnectionManager {

    // ConcurrentHashMap -> SSE 연결/해제 요청 스레드 vs PING 전용 스케줄러 스레드 간 경합 배제
    private final Map<String, SseEmitter> connections = new ConcurrentHashMap<>();

    private final DeviceRegistryService registry;
    private final DeviceRegistryProperties properties;

    public DeviceConnectionManager(DeviceRegistryService registry, DeviceRegistryProperties properties) {
        this.registry = registry;
        this.properties = properties;
    }

    public SseEmitter connect(String deviceId) {
        SseEmitter emitter = new SseEmitter(0L); // No Servlet Timeout. 끊김은 ping 쓰기 실패로 감지
        emitter.onCompletion(() -> disconnect(deviceId, emitter, "completed"));
        emitter.onTimeout(() -> disconnect(deviceId, emitter, "timeout"));
        emitter.onError(e -> disconnect(deviceId, emitter, "error: " + e));

        SseEmitter previous = connections.put(deviceId, emitter);
        if (previous != null) { // 같은 서버로 재연결한 경우. 옛 emitter의 콜백은 인스턴스가 달라 새 emitter를 지우지 못한다 (ADR-0004).
            log.warn("Replacing stale connection: id={}", deviceId);
            previous.complete();
        }

        try {
            registry.register(deviceId); // 연결 즉시 기록. 다음 ping까지 기다리지 않는다 (ADR-0003).
        } catch (RuntimeException e) {
            log.warn("Register failed, will retry on next ping: id={}, cause={}", deviceId, e.toString());
        }
        send(deviceId, emitter, "connected", Map.of("serverId", properties.serverId()));
        log.info("SSE connected: id={}, local connections={}", deviceId, connections.size());
        return emitter;
    }

    /** 이 서버가 연결을 쥐고 있을 때만 보낸다. 4단계(Pub/Sub)에서 명령 전달에 쓴다. */
    public boolean send(String deviceId, String event, Object data) {
        SseEmitter emitter = connections.get(deviceId);
        return emitter != null && send(deviceId, emitter, event, data);
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
        connections.forEach((deviceId, emitter) -> {
            if (!send(deviceId, emitter, "ping", properties.serverId())) {
                return; // send 내부에서 예외 처리 진행
            }
            try {
                registry.renew(deviceId);
            } catch (RuntimeException e) {
                log.warn("Lease renew failed: id={}, cause={}", deviceId, e.toString());
            }
        });
    }

    private boolean send(String deviceId, SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
            return true;
        } catch (IOException | IllegalStateException e) {
            // - IOException: 소켓이 죽음.
            // - IllegalStateException: 이미 완료된 emitter. 둘 다 관리 대상에서 빠져야 한다.
            log.warn("SSE send failed, closing: id={}, event={}, cause={}", deviceId, event, e.toString());
            emitter.completeWithError(e);
            return false;
        }
    }

    private void disconnect(String deviceId, SseEmitter emitter, String reason) {
        // 이 콜백의 emitter 인스턴스가 맵에 있을 때만 제거한다. 재연결로 새 emitter가 들어와 있으면 그대로 둔다.
        if (!connections.remove(deviceId, emitter)) {
            log.debug("SSE disconnect ignored (already replaced or removed): id={}, reason={}", deviceId, reason);
            return;
        }
        log.info("SSE disconnected: id={}, reason={}, local connections={}", deviceId, reason, connections.size());
        try {
            registry.unregister(deviceId);
        } catch (RuntimeException e) {
            log.warn("Unregister failed, TTL will expire it: id={}, cause={}", deviceId, e.toString());
        }
    }
}
