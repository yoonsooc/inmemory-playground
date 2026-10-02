package dev.yoonsooc.device_registry.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import dev.yoonsooc.device_registry.data.CommandResult;
import dev.yoonsooc.device_registry.data.DeviceConnectionManager;
import dev.yoonsooc.device_registry.data.DeviceStatus;
import dev.yoonsooc.device_registry.service.DeviceCommandService;
import dev.yoonsooc.device_registry.service.DeviceRegistryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@RestController
@RequestMapping("/devices")
@RequiredArgsConstructor
public class DeviceRegistryController {

    private final DeviceRegistryService registry;
    private final DeviceConnectionManager connections;
    private final DeviceCommandService commands;

    /**
     * 디바이스 - 서버 간 SSE 스트림.
     * 이 요청을 받은 서버가 디바이스 연결의 주인.
     * connected, ping 이벤트를 보낸다.
     */
    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable String id) {
        return connections.connect(id);
    }

    /**
     * 기기에게 명령을 보낸다. 아무 서버에 보내도 연결을 쥔 서버가 SSE "command" 이벤트로 전달한다.
     * 202: 바로 보냈거나(DELIVERED) 연결을 쥔 서버의 채널에 발행했다(PUBLISHED).
     * 404: 기기가 오프라인. 409: 기록은 이 서버를 가리키는데 연결이 없다.
     */
    @PostMapping("/{id}/command")
    public ResponseEntity<CommandResult> command(@PathVariable String id, @RequestBody String body) {
        CommandResult result = commands.command(id, body);
        HttpStatus status = switch (result.outcome()) {
            case DELIVERED, PUBLISHED -> HttpStatus.ACCEPTED;
            case OFFLINE -> HttpStatus.NOT_FOUND;
            case NOT_CONNECTED -> HttpStatus.CONFLICT;
        };
        return ResponseEntity.status(status).body(result);
    }

    @GetMapping("/{id}")
    public ResponseEntity<DeviceStatus> findDevice(@PathVariable String id) {
        return ResponseEntity.of(registry.find(id));
    }

    @GetMapping
    public List<DeviceStatus> findAllDevices() {
        return registry.findAll();
    }
}
