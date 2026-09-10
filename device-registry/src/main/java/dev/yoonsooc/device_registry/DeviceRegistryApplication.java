package dev.yoonsooc.device_registry;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class DeviceRegistryApplication {

	public static void main(String[] args) {
		SpringApplication.run(DeviceRegistryApplication.class, args);
	}

}
