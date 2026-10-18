package pl.pollub.pari.common.service;

import org.springframework.stereotype.Service;
import pl.pollub.pari.common.domain.PlatformSetting;
import pl.pollub.pari.common.repository.PlatformSettingRepository;

import java.math.BigDecimal;

/** Typed access to the tunable business values in {@code platform_settings} (commission, fees, timeouts). */
@Service
public class PlatformSettings {

    private final PlatformSettingRepository repository;

    public PlatformSettings(PlatformSettingRepository repository) {
        this.repository = repository;
    }

    public BigDecimal decimal(String key) {
        return new BigDecimal(value(key));
    }

    public int integer(String key) {
        return Integer.parseInt(value(key));
    }

    private String value(String key) {
        return repository.findById(key)
                .map(PlatformSetting::getValue)
                .orElseThrow(() -> new IllegalStateException("Missing platform setting: " + key));
    }
}
