package com.luohuo.flex.gateway.filter;

import cn.dev33.satoken.config.SaTokenConfig;
import com.luohuo.flex.common.properties.IgnoreProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.reactive.function.client.WebClient;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

/**
 * #295 回归：gateway 启动级实例化用例。
 * TokenContextFilter 有两个显式构造器且无默认构造器——Spring 必须靠唯一 @Autowired
 * 构造器消歧，否则 BeanInstantiationException(No default constructor found)、gateway
 * 上下文刷新失败（verify-issue 在 .83 实锤）。本用例走真实 Spring 构造器解析路径
 * （含 @Qualifier("aiclawLbWebClientBuilder") 按 bean 名匹配），构造器丢注解即红。
 */
class TokenContextFilterSpringWiringTest {

	@Test
	void springInstantiatesFilterViaUniqueAutowiredConstructor() {
		new ApplicationContextRunner()
				.withPropertyValues("THINKING_INTERNAL_SECRET=test-secret")
				.withBean(IgnoreProperties.class, () -> mock(IgnoreProperties.class))
				.withBean(SaTokenConfig.class, SaTokenConfig::new)
				.withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
				.withBean("aiclawLbWebClientBuilder", WebClient.Builder.class, WebClient::builder)
				.withBean(TokenContextFilter.class)
				.run(context -> {
					assertNotNull(context.getBean(TokenContextFilter.class));
				});
	}
}
