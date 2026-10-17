package pl.pollub.pari.spot.domain;

import jakarta.persistence.Embeddable;

/** Size of a parking space in centimetres. Height is optional (open-air spaces). */
@Embeddable
public record Dimensions(int lengthCm, int widthCm, Integer heightCm) {

    public Dimensions {
        if (lengthCm <= 0 || widthCm <= 0 || (heightCm != null && heightCm <= 0)) {
            throw new IllegalArgumentException("dimensions must be positive");
        }
    }

    /** True when a vehicle of the given size fits into this space. */
    public boolean fits(Dimensions vehicle) {
        boolean heightOk = heightCm == null || vehicle.heightCm == null || vehicle.heightCm <= heightCm;
        return vehicle.lengthCm <= lengthCm && vehicle.widthCm <= widthCm && heightOk;
    }
}
