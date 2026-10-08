package com.luohuo.flex.common.controller;

import com.luohuo.basic.boot.handler.AbstractGlobalResponseBodyAdvice;
import com.luohuo.flex.common.properties.IgnoreProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class HealthControllerTest {
    @ParameterizedTest
    @ValueSource(strings = {"gateway", "oauth", "im", "system", "ws"})
    void onlyFiveServicesRegisterDependencyFreeLiveness(String service) {
        new ApplicationContextRunner().withUserConfiguration(HealthController.class)
                .withPropertyValues("spring.application.name=luohuo-" + service + "-server")
                .run(context -> {
                    assertEquals(1, context.getBeansOfType(HealthController.class).size());
                    HealthController controller = context.getBean(HealthController.class);
                    assertEquals(0, HealthController.class.getDeclaredConstructors()[0].getParameterCount());
                    assertEquals(Map.of("status", "UP"), controller.health());
                    assertThrows(UnsupportedOperationException.class, () -> controller.health().put("secret", "value"));
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "luohuo-base-server", "luohuo-boot-server", "luohuo-monitor-server"})
    void doesNotExtendOtherServices(String name) {
        new ApplicationContextRunner().withUserConfiguration(HealthController.class)
                .withPropertyValues("spring.application.name=" + name)
                .run(context -> assertTrue(context.getBeansOfType(HealthController.class).isEmpty()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"oauth", "im", "system"})
    void servletGetIsAnonymousStableAndNotWrapped(String service) throws Exception {
        new ApplicationContextRunner().withUserConfiguration(HealthController.class)
                .withPropertyValues("spring.application.name=luohuo-" + service + "-server")
                .run(context -> {
                    MockMvc mvc = MockMvcBuilders.standaloneSetup(context.getBean(HealthController.class))
                            .setControllerAdvice(new AbstractGlobalResponseBodyAdvice() {}).build();
                    for (int i = 0; i < 2; i++) {
                        mvc.perform(get(HealthController.PATH)).andExpect(status().isOk())
                                .andExpect(content().contentTypeCompatibleWith("application/json"))
                                .andExpect(content().json("{\"status\":\"UP\"}", true));
                    }
                    for (HttpMethod method : new HttpMethod[]{HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE}) {
                        mvc.perform(request(method, HealthController.PATH)).andExpect(status().isMethodNotAllowed());
                    }
                    mvc.perform(get(HealthController.PATH + "/details")).andExpect(status().isNotFound());
                });
    }

    @Test
    void reusesExistingAnonymousConventionWithoutWideningBusinessPaths() {
        IgnoreProperties ignored = new IgnoreProperties();
        for (String path : HealthController.GATEWAY_PATHS) {
            assertTrue(ignored.isIgnoreUser("GET", path), path);
            assertTrue(ignored.isIgnoreTenant("GET", path), path);
            assertTrue(ignored.isIgnoreAnyone("GET", path), path);
        }
        assertFalse(ignored.isIgnoreUser("GET", "/im/chat/msg"));
        assertFalse(ignored.isIgnoreUser("GET", "/ws/ws"));
        assertFalse(ignored.isIgnoreUser("GET", "/health"));
    }
}
