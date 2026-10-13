package com.showcase.fx.config;

import io.lettuce.core.tracing.MicrometerTracing;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.ObservationFilter;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.boot.autoconfigure.data.redis.ClientResourcesBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Boot instruments the HTTP server and the RestClient but not Lettuce (it only records Redis
// command metrics), so without this a cached /fx/rates trace shows no Redis at all. Each command
// becomes a child span of the current observation; the in-process spans come from @Observed on
// FxRateController, FxRateService, RateCache and RateProvider (management.observations.annotations).
@Configuration
public class TracingConfig {

    @Bean
    public ClientResourcesBuilderCustomizer redisTracing(ObservationRegistry observationRegistry) {
        // Command args are tagged so a span shows which key it touched (fx:rates:EUR, fx:lock:EUR).
        // Nothing sensitive goes through this Redis: rates, and a random lock token.
        return builder -> builder.tracing(new MicrometerTracing(observationRegistry, "redis", true));
    }

    /**
     * Puts every argument of @Observed methods on their spans as {@code args.<name>} (args.base, args.quote,
     * and RateCache's args.table and args.token). High cardinality, so span attributes only, never tags on the method.observed timer: the controller
     * and FxRateService.quote receive the query parameters before they are validated, and a
     * low-cardinality tag would let any caller create unbounded Prometheus series.
     */
    @Bean
    public ObservationFilter observedArguments() {
        return context -> {
            if (context instanceof ObservedAspect.ObservedAspectContext observed && observed.getProceedingJoinPoint().getSignature() instanceof MethodSignature signature) {
                String[] names = signature.getParameterNames();
                Object[] args = observed.getProceedingJoinPoint().getArgs();
                for (int i = 0; i < names.length; i++) {
                    context.addHighCardinalityKeyValue(KeyValue.of("args." + names[i], String.valueOf(args[i])));
                }
            }
            return context;
        };
    }
}

