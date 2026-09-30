package dev.yoonsooc.device_registry.controller;

import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import dev.yoonsooc.device_registry.data.DeviceConnectionManager;
import dev.yoonsooc.device_registry.data.DeviceStatus;
import dev.yoonsooc.device_registry.service.DeviceRegistryService;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/devices")
@RequiredArgsConstructor
public class DeviceRegistryController {

    private final DeviceRegistryService registry;
    private final DeviceConnectionManager connections;

    /**
     * 디바이스 - 서버 간 SSE 스트림.
     * 이 요청을 받은 서버가 디바이스 연결의 주인.
     * connected, ping 이벤트를 보낸다.
     */
    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable String id) {
        return connections.connect(id);
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
