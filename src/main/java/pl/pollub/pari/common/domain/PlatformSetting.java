package pl.pollub.pari.common.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/** A tunable business value (commission rate, penalty multiplier, payout delay, ...). */
@Entity
@Table(name = "platform_settings")
@Getter
public class PlatformSetting {

    @Id
    @Column(name = "setting_key")
    private String key;

    @Column(name = "setting_value", nullable = false)
    private String value;

    private String description;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    protected PlatformSetting() {
    }

    public PlatformSetting(String key, String value, String description) {
        this.key = key;
        this.value = value;
        this.description = description;
    }

    public void changeValue(String newValue) {
        this.value = newValue;
    }
}
