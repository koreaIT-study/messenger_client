package com.teamride.messenger.client.config;

import java.util.concurrent.TimeUnit;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
	@Override
	public void addResourceHandlers(ResourceHandlerRegistry registry) {
		registry.addResourceHandler("/**")
		.addResourceLocations("classpath:/templates/","classpath:/static/")
		.setCachePeriod(0);
//		.setCacheControl(CacheControl.maxAge(10, TimeUnit.MINUTES));
	}
}