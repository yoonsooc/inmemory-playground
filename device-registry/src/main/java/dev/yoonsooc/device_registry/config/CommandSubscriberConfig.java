package dev.yoonsooc.device_registry.config;

import java.nio.charset.StandardCharsets;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import dev.yoonsooc.device_registry.service.DeviceCommandService;

/**
 * 이 서버만 듣는 채널 {@code server:{server_id}} 하나를 구독한다.
 * 컨테이너가 구독용 연결을 따로 열고, 메시지가 오면 자기 스레드에서 리스너를 부른다.
 */
@Configuration
public class CommandSubscriberConfig {

    @Bean
    public RedisMessageListenerContainer commandListenerContainer(RedisConnectionFactory connectionFactory,
            DeviceCommandService commands, DeviceRegistryProperties properties) {
        MessageListener listener = (message, pattern) -> commands
                .onPublished(new String(message.getBody(), StandardCharsets.UTF_8));

        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(listener, new ChannelTopic(DeviceCommandService.channelOf(properties.serverId())));
        return container;
    }
}
