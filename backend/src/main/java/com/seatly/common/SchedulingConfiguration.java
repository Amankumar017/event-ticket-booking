package com.seatly.common;

import com.seatly.booking.HoldProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(HoldProperties.class)
public class SchedulingConfiguration {
}
