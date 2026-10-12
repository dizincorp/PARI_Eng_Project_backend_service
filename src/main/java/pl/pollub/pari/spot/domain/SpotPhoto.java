package pl.pollub.pari.spot.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import pl.pollub.pari.common.domain.BaseEntity;

/** Photo of a space. The file lives in object storage; only its key is stored. */
@Entity
@Table(name = "spot_photos")
@Getter
public class SpotPhoto extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "spot_id", nullable = false)
    private ParkingSpot spot;

    @Column(nullable = false)
    private String storageKey;

    /** 0 is the cover photo. */
    @Setter
    private int position;

    protected SpotPhoto() {
    }

    SpotPhoto(ParkingSpot spot, String storageKey, int position) {
        this.spot = spot;
        this.storageKey = storageKey;
        this.position = position;
    }
}
