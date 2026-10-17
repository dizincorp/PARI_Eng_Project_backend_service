package pl.pollub.pari.spot.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import pl.pollub.pari.common.domain.BaseEntity;

/** Tag describing a parking space (covered, EV charger, gate, ...). Seeded by the migration. */
@Entity
@Table(name = "amenities")
@Getter
public class Amenity extends BaseEntity {

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String label;

    protected Amenity() {
    }

    public Amenity(String code, String label) {
        this.code = code;
        this.label = label;
    }
}
