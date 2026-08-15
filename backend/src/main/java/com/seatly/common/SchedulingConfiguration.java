package com.seatly.common;

import com.seatly.booking.HoldProperties;
import com.seatly.payment.PaymentProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties({HoldProperties.class, PaymentProperties.class})
public class SchedulingConfiguration {
}
