package com.seatly.event.stream;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
public class SeatStreamConfiguration {

	/**
	 * Subscribes this instance to the seat-update channel.
	 * <p>
	 * The container owns its own thread: Redis pub/sub is a blocking read, and it
	 * has no business sitting on a request thread.
	 */
	@Bean
	public RedisMessageListenerContainer seatUpdateListenerContainer(
			RedisConnectionFactory connectionFactory, SeatUpdateSubscriber subscriber) {

		RedisMessageListenerContainer container = new RedisMessageListenerContainer();
		container.setConnectionFactory(connectionFactory);
		container.addMessageListener(subscriber, new ChannelTopic(SeatUpdateBroadcaster.CHANNEL));
		return container;
	}

}
