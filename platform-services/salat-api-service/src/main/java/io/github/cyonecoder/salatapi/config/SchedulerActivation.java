package io.github.cyonecoder.salatapi.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Activates Spring's scheduler, so {@code @Scheduled} methods fire.
 *
 * <p>Enabled by default, but turned off when either {@code salat.scheduling.enabled=false}
 * (tests, or a read-only replica) <strong>or</strong> a one-shot job is requested
 * via {@code --salat.job=...} (spec 25). The latter matters: a non-web CLI run
 * with the scheduler active would keep non-daemon scheduler threads alive, and the
 * process would never exit.
 */
@Configuration
@EnableScheduling
@Conditional(SchedulerActivation.SchedulerEnabled.class)
public class SchedulerActivation {

    /** True unless scheduling is disabled or a manual job is being run. */
    static class SchedulerEnabled implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            var env = context.getEnvironment();
            boolean enabled = env.getProperty("salat.scheduling.enabled", Boolean.class, true);
            String job = env.getProperty("salat.job");
            boolean jobMode = job != null && !job.isBlank();
            return enabled && !jobMode;
        }
    }
}
