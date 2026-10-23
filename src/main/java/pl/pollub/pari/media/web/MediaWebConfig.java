package pl.pollub.pari.media.web;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import pl.pollub.pari.media.MediaProperties;

import java.nio.file.Path;
import java.time.Duration;

/** Serves the stored images publicly under {@code GET /media/**}. File names are random, so they can be cached for long. */
@Configuration
@EnableConfigurationProperties(MediaProperties.class)
public class MediaWebConfig implements WebMvcConfigurer {

    private final MediaProperties properties;

    public MediaWebConfig(MediaProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = Path.of(properties.storageDir()).toAbsolutePath().normalize().toUri().toString();
        registry.addResourceHandler("/media/**")
                .addResourceLocations(location.endsWith("/") ? location : location + "/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic());
    }
}
