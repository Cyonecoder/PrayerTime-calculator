package io.github.cyonecoder.salatapi.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.cyonecoder.salatapi.scheduling.HabousDailySyncJob;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.scheduling.config.ScheduledTaskHolder;

/**
 * The scheduler activation condition (TLS-fix task, scheduler verification).
 *
 * <p>The scheduler must not run when disabled or in job mode, and must run by
 * default. {@code salat.scheduling.enabled} binds from the environment variable
 * {@code SALAT_SCHEDULING_ENABLED} via Spring's relaxed binding.
 */
class SchedulerActivationTest {

    private final SchedulerActivation.SchedulerEnabled condition =
            new SchedulerActivation.SchedulerEnabled();

    @Test
    void schedulingIsOnByDefault() {
        assertThat(matches(new MockEnvironment())).isTrue();
    }

    @Test
    void schedulingIsOffWhenExplicitlyDisabled() {
        assertThat(matches(new MockEnvironment().withProperty("salat.scheduling.enabled", "false")))
                .isFalse();
    }

    @Test
    void schedulingIsOffInJobModeSoTheCliCanExit() {
        assertThat(matches(new MockEnvironment().withProperty("salat.job", "sync-now")))
                .isFalse();
    }

    @Test
    void schedulingIgnoresABlankJob() {
        assertThat(matches(new MockEnvironment().withProperty("salat.job", "  ")))
                .isTrue();
    }

    private boolean matches(org.springframework.core.env.Environment env) {
        var context = org.mockito.Mockito.mock(org.springframework.context.annotation.ConditionContext.class);
        org.mockito.Mockito.when(context.getEnvironment()).thenReturn(env);
        return condition.matches(context, null);
    }

    /** With scheduling disabled, no scheduled task is registered in the context. */
    @Nested
    @SpringBootTest(properties = "salat.scheduling.enabled=false")
    class SchedulingDisabledContext {

        @Autowired(required = false)
        private ScheduledTaskHolder scheduledTaskHolder;
        @Autowired
        private HabousDailySyncJob syncJob;

        @Test
        void noScheduledTasksAreRegistered() {
            // The job bean still exists (so it can be triggered manually)...
            assertThat(syncJob).isNotNull();
            // ...but nothing is scheduled to fire.
            assertThat(scheduledTaskHolder == null
                    || scheduledTaskHolder.getScheduledTasks().isEmpty()).isTrue();
        }
    }
}
