package pl.pollub.pari.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import pl.pollub.pari.common.domain.BaseEntity;

import java.util.Locale;

@Entity
@Table(name = "vehicles")
@Getter
public class Vehicle extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Setter
    @Column(nullable = false)
    private String model;

    @Column(nullable = false)
    private String plateNumber;

    protected Vehicle() {
    }

    public Vehicle(User user, String model, String plateNumber) {
        this.user = user;
        this.model = model;
        changePlateNumber(plateNumber);
    }

    public void changePlateNumber(String plateNumber) {
        this.plateNumber = plateNumber.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }
}
