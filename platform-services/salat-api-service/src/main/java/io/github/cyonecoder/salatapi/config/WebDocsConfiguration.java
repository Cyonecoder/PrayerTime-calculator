package io.github.cyonecoder.salatapi.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Serves the bundled API reference page at {@code /docs}.
 *
 * <p>The page is a static file at {@code static/docs/index.html}, so it ships
 * inside the jar and needs no external hosting. Spring's resource handler serves
 * {@code /docs/index.html} directly; these view controllers make the friendlier
 * {@code /docs} URL work, and send the bare root there too.
 */
@Configuration
public class WebDocsConfiguration implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // /docs and /docs/ render the reference page.
        registry.addViewController("/docs").setViewName("forward:/docs/index.html");
        registry.addViewController("/docs/").setViewName("forward:/docs/index.html");
        // The bare root redirects to the docs, so hitting the base URL finds them.
        registry.addRedirectViewController("/", "/docs");
    }
}
