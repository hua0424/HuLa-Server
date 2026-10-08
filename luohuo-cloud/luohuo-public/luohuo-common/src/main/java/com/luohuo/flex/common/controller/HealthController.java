package com.luohuo.flex.common.controller;

import com.luohuo.basic.annotation.response.IgnoreResponseBodyAdvice;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Set;

/** Process liveness only: no dependency checks, user context or business writes. */
@RestController
@IgnoreResponseBodyAdvice
@ConditionalOnExpression("'${spring.application.name:}'.matches('luohuo-(gateway|oauth|im|system|ws)-server')")
public class HealthController {
    // Reuse the existing anonymous /anno/** convention, including gateway StripPrefix=1 routes.
    public static final String PATH = "/anno/health";
    public static final Set<String> GATEWAY_PATHS = Set.of(PATH,
            "/oauth" + PATH, "/im" + PATH, "/system" + PATH, "/ws" + PATH);

    @Hidden // Liveness is not a business permission resource.
    @GetMapping(value = PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }
}
