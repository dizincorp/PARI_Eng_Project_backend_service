package pl.pollub.pari.spot.domain;

import jakarta.persistence.Embeddable;

/** Postal address of a parking space. Columns: street, city, postal_code, country (ISO 3166 alpha-2). */
@Embeddable
public record Address(String street, String city, String postalCode, String country) {
}
