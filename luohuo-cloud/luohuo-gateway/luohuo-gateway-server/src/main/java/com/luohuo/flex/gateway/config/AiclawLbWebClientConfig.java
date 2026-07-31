package com.luohuo.flex.gateway.config;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * #184(a) 方案 B: gateway 用 WebClient 经 service discovery 直调 im internal verify-token。
 *
 * <p>gateway 进程作为 client 直连 luohuo-im-server（{@code lb://}），不经 gateway 自己的反向代理路由表，
 * 故不会形成"gateway 调 gateway 路由"的自环。</p>
 */
@Configuration
public class AiclawLbWebClientConfig {

	@Bean
	@LoadBalanced
	public WebClient.Builder aiclawLbWebClientBuilder() {
		return WebClient.builder();
	}
}
