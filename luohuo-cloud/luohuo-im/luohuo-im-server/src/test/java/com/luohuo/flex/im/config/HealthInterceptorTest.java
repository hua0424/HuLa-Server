package com.luohuo.flex.im.config;

import com.luohuo.flex.common.controller.HealthController;
import com.luohuo.flex.im.common.interceptor.BlackInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class HealthInterceptorTest {
    @Configuration
    @EnableWebMvc
    static class HttpConfig {
        @Bean BlackInterceptor blacklist() { return mock(BlackInterceptor.class); }
    }

    @RestController
    static class BusinessController {
        @GetMapping("/business") public String business() { return "must remain protected"; }
    }

    @Test
    void actualImRegistrationSkipsOnlyHealthAndKeepsBusinessBlacklist() throws Exception {
        try (AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                    "spring.application.name=luohuo-im-server");
            context.register(HttpConfig.class, ImWebMvcConfiguration.class, HealthController.class, BusinessController.class);
            context.refresh();
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
            BlackInterceptor blacklist = context.getBean(BlackInterceptor.class);
            mvc.perform(get(HealthController.PATH)).andExpect(status().isOk())
                    .andExpect(content().json("{\"status\":\"UP\"}", true));
            verifyNoInteractions(blacklist);
            // The mock's default false stops the ordinary handler, proving registration is intact.
            mvc.perform(get("/business")).andExpect(content().string(""));
            verify(blacklist).preHandle(any(), any(), any());
        }
    }
}
