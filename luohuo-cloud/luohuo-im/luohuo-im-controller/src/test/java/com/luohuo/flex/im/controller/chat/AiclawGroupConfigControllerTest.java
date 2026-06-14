package com.luohuo.flex.im.controller.chat;

import com.luohuo.basic.context.ContextUtil;
import com.luohuo.flex.im.core.chat.service.AiclawGroupConfigService;
import com.luohuo.flex.im.domain.vo.resp.aiclaw.AiclawGroupConfigResp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.Collections;
import java.util.List;

import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * aichatoverview#26: AiclawGroupConfigController /list 端点的 controller 接线层行为测试。
 *
 * <p>锁定的安全面（IDOR）：{@code /aiclaw/group/config/list} 的 aiclawUid <b>只</b>来自
 * 认证身份 {@link ContextUtil#getUid()}，端点不声明、也不消费任何 query 参数。
 * service 层的 scope 测试只锁住 service 那半；本测试锁住「controller 把谁的 uid 喂给 service」。
 *
 * <p>若将来有人给 /list 加一个 {@code @RequestParam Long aiclawUid} 并转发给 service，
 * service 层测试仍会绿，但 IDOR 复活——本测试会 RED（service 会收到 query 里的 B_UID 而非认证的 A_UID）。
 *
 * <p>采用 standalone MockMvc（不起完整 Spring context，最轻）+ Mockito mock service
 * + {@link MockedStatic} mock 静态 {@code ContextUtil.getUid()}。
 */
class AiclawGroupConfigControllerTest {

	/** 认证身份 A —— 端点应当只用这个 uid 调 service。 */
	private static final Long A_UID = 1001L;
	/** query 里注入的他人 uid B —— 端点必须忽略它。 */
	private static final Long B_UID = 2002L;

	private AiclawGroupConfigService aiclawGroupConfigService;
	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		aiclawGroupConfigService = mock(AiclawGroupConfigService.class);
		AiclawGroupConfigController controller = new AiclawGroupConfigController();
		// @Resource 字段注入 → 反射注入 mock。
		org.springframework.test.util.ReflectionTestUtils
				.setField(controller, "aiclawGroupConfigService", aiclawGroupConfigService);
		mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
	}

	@AfterEach
	void tearDown() {
		// 无静态状态需清理；MockedStatic 在各测试方法内以 try-with-resources 管理。
	}

	@Test
	@DisplayName("/list 只用认证身份的 uid 调 service，并忽略 query 注入的他人 uid（防 IDOR）")
	void listEndpointUsesAuthenticatedUidAndIgnoresQuery() throws Exception {
		// given: 认证身份是 A；service 对 A 返回一条 A 自己的配置。
		AiclawGroupConfigResp aConfig = AiclawGroupConfigResp.builder()
				.aiclawUid(A_UID)
				.roomId(10L)
				.rateLimitPerMinute(20)
				.mentionRequired(1)
				.dailyLimit(500)
				.respondToAi(0)
				.build();
		when(aiclawGroupConfigService.listSelfConfigs(A_UID)).thenReturn(List.of(aConfig));

		try (MockedStatic<ContextUtil> ctx = mockStatic(ContextUtil.class)) {
			ctx.when(ContextUtil::getUid).thenReturn(A_UID);

			// when: 故意带 query 注入 B 的 uid（外加一个探测性的 roomId）。
			mockMvc.perform(get("/aiclaw/group/config/list")
							.param("aiclawUid", String.valueOf(B_UID))
							.param("roomId", "9"))
					// then: 200，且 body 里返回的是 A 的配置。
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.data[0].aiclawUid").value(A_UID));

			// then(核心): service 收到的是认证身份 A，绝不是 query 里的 B。
			verify(aiclawGroupConfigService).listSelfConfigs(A_UID);
			verify(aiclawGroupConfigService, never()).listSelfConfigs(B_UID);

			// then(强化): 用 captor 抓 service 入参，断言恒等于 A_UID。
			ArgumentCaptor<Long> uidCaptor = ArgumentCaptor.forClass(Long.class);
			verify(aiclawGroupConfigService).listSelfConfigs(uidCaptor.capture());
			assertEquals(A_UID, uidCaptor.getValue(),
					"controller 必须把认证身份的 uid 传给 service，而非 query 参数");
		}
	}

	@Test
	@DisplayName("/list 在没有配置时返回 200 + 空数组")
	void listEndpointReturnsEmptyWhenNoConfigs() throws Exception {
		when(aiclawGroupConfigService.listSelfConfigs(anyLong()))
				.thenReturn(Collections.emptyList());

		try (MockedStatic<ContextUtil> ctx = mockStatic(ContextUtil.class)) {
			ctx.when(ContextUtil::getUid).thenReturn(A_UID);

			mockMvc.perform(get("/aiclaw/group/config/list"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.data").isArray())
					.andExpect(jsonPath("$.data").isEmpty());

			verify(aiclawGroupConfigService).listSelfConfigs(A_UID);
		}
	}
}
