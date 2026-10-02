package dev.yoonsooc.device_registry.service;

import java.util.Optional;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import dev.yoonsooc.device_registry.config.DeviceRegistryProperties;
import dev.yoonsooc.device_registry.data.CommandResult;
import dev.yoonsooc.device_registry.data.CommandResult.Outcome;
import dev.yoonsooc.device_registry.data.DeviceConnectionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * 기기에게 보내는 명령을 연결을 쥔 서버까지 전달한다 (ADR-0005).
 * <p>
 * 명령은 nginx를 거쳐 아무 서버에나 도착한다. 받은 서버가 Valkey 기록의 server_id를 보고
 * 자기가 연결을 쥐고 있으면 바로 보내고, 아니면 그 서버만 듣는 채널 {@code server:{server_id}}에 발행한다.
 * 각 서버는 자기 채널 하나만 구독한다 (구독 설정은 CommandSubscriberConfig).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeviceCommandService {

    /** 기기가 받는 SSE 이벤트 이름. */
    private static final String EVENT_NAME = "command";

    private final DeviceRegistryService registry;
    private final DeviceConnectionManager connections;
    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final DeviceRegistryProperties properties;

    /** 채널로 주고받는 메시지. 채널이 서버 단위라서 어느 기기 것인지 메시지 안에 담는다. */
    record CommandMessage(String deviceId, String payload) {
    }

    public static String channelOf(String serverId) {
        return "server:" + serverId;
    }

    public CommandResult command(String deviceId, String payload) {
        Optional<String> ownerServer = registry.findDeviceOwnerServer(deviceId);
        if (ownerServer.isEmpty()) {
            log.info("Command dropped, device is offline: id={}", deviceId);
            return new CommandResult(Outcome.OFFLINE, null);
        }

        String ownerId = ownerServer.get();
        if (ownerId.equals(properties.serverId())) {
            boolean sent = connections.commandDevice(deviceId, EVENT_NAME, payload);
            if (!sent) {
                // 기록은 나를 가리키는데 맵에 연결이 없다. 서버가 막 재시작했거나 연결이 방금 끊긴 경우다.
                log.warn("Command dropped, record points here but no local connection: id={}", deviceId);
                return new CommandResult(Outcome.NOT_CONNECTED, ownerId);
            }
            log.info("Command delivered locally: id={}", deviceId);
            return new CommandResult(Outcome.DELIVERED, ownerId);
        }

        return publish(deviceId, payload, ownerId);
    }

    private CommandResult publish(String deviceId, String payload, String ownerId) {
        // 발행 명령이 돌려주는 "받은 구독자 수"는 쓰지 않는다.
        // 클러스터에서는 발행을 받은 노드에 붙은 구독자만 세므로, 실제로 전달됐어도 0이 나올 수 있다.
        redis.convertAndSend(channelOf(ownerId), json.writeValueAsString(new CommandMessage(deviceId, payload)));
        log.info("Command published: id={}, to={}", deviceId, ownerId);
        return new CommandResult(Outcome.PUBLISHED, ownerId);
    }

    /** 이 서버의 채널에 메시지가 오면 구독 리스너가 부른다. */
    public void onPublished(String body) {
        CommandMessage message;
        try {
            message = json.readValue(body, CommandMessage.class);
        } catch (JacksonException e) {
            log.warn("Command message ignored, cannot parse: body={}, cause={}", body, e.toString());
            return;
        }

        boolean sent = connections.commandDevice(message.deviceId(), EVENT_NAME, message.payload());
        if (sent) {
            log.info("Command delivered from channel: id={}", message.deviceId());
        } else {
            // 보낸 서버가 본 기록과 지금 이 서버의 맵이 다르다. 그 사이 연결이 끊겼거나, 서버가 재시작한 뒤 TTL이 남은 기록이다.
            log.warn("Command dropped, published here but no local connection: id={}", message.deviceId());
        }
    }
}
