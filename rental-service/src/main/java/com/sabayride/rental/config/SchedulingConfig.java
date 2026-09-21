package com.sabayride.rental.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns on @Scheduled. Without this the hold sweeper never runs. */
@Configuration
@EnableScheduling
public class SchedulingConfig { }
