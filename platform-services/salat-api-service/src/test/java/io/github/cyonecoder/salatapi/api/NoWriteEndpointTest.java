package io.github.cyonecoder.salatapi.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.cyonecoder.salatapi.api.controller.DataStatusController;
import io.github.cyonecoder.salatapi.api.controller.HabousController;
import io.github.cyonecoder.salatapi.api.controller.PrayerTimesController;
import io.github.cyonecoder.salatapi.api.controller.SecApproachController;
import io.github.cyonecoder.salatapi.api.controller.VerificationController;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * The REST surface is read-only and unauthenticated (spec 25, 28): there is no
 * public write, sync or delete endpoint, and no security dependency is present.
 */
class NoWriteEndpointTest {

    private static final List<Class<?>> CONTROLLERS = List.of(
            HabousController.class, SecApproachController.class, PrayerTimesController.class,
            VerificationController.class, DataStatusController.class);

    @Test
    void noControllerDeclaresAWriteMapping() {
        for (Class<?> controller : CONTROLLERS) {
            for (Method method : controller.getDeclaredMethods()) {
                assertThat(method.isAnnotationPresent(PostMapping.class))
                        .as("%s.%s must not be a POST endpoint", controller.getSimpleName(), method.getName())
                        .isFalse();
                assertThat(method.isAnnotationPresent(PutMapping.class)).isFalse();
                assertThat(method.isAnnotationPresent(DeleteMapping.class)).isFalse();
                assertThat(method.isAnnotationPresent(PatchMapping.class)).isFalse();

                RequestMapping rm = method.getAnnotation(RequestMapping.class);
                if (rm != null) {
                    assertThat(rm.method())
                            .as("%s.%s must be GET-only", controller.getSimpleName(), method.getName())
                            .allMatch(m -> m == org.springframework.web.bind.annotation.RequestMethod.GET);
                }
            }
        }
    }

    @Test
    void everyEndpointIsAGet() {
        long getMappings = CONTROLLERS.stream()
                .flatMap(c -> java.util.Arrays.stream(c.getDeclaredMethods()))
                .filter(m -> m.isAnnotationPresent(GetMapping.class))
                .count();
        // All 22 routes are reads; if a write mapping were ever added the previous
        // test fails, and this confirms the read surface is non-empty.
        assertThat(getMappings).isGreaterThanOrEqualTo(20);
    }

    @Test
    void noSpringSecurityIsOnTheClasspath() {
        // No authentication is required because no security starter is present.
        assertThat(springSecurityPresent())
                .as("no Spring Security dependency — the API is deliberately unauthenticated")
                .isFalse();
    }

    private static boolean springSecurityPresent() {
        try {
            Class.forName("org.springframework.security.web.SecurityFilterChain");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
