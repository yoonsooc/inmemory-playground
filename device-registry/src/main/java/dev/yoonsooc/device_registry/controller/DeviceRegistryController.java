package dev.yoonsooc.device_registry.controller;

import org.springframework.web.bind.annotation.RestController;

import dev.yoonsooc.device_registry.data.DeviceStatus;
import dev.yoonsooc.device_registry.service.DeviceRegistryService;
import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;

@RestController
@RequestMapping("/devices")
@RequiredArgsConstructor
public class DeviceRegistryController {

    private final DeviceRegistryService service;

    @PostMapping("/{id}/heartbeat")
    public void heartbeat(@PathVariable String id) {
        service.heartbeat(id);
    }

    @GetMapping("/{id}")
    public ResponseEntity<DeviceStatus> findDevice(@PathVariable String id) {
        return ResponseEntity.of(service.find(id));
    }

    @GetMapping
    public List<DeviceStatus> findAllDevices() {
        return service.findAll();
    }

}
