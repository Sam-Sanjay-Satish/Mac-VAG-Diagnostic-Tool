package io.github.samsanjaysatish.macvagdiag.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VinTest {

    /** The textbook example VIN (check digit X); made up, not a real car of ours. */
    static final String TEST_VIN = "1M8GDM9AXKP042788";

    @Test
    void checkDigitOfTheTextbookExample() {
        assertEquals('X', Vin.checkDigit(TEST_VIN));
        assertTrue(Vin.hasValidCheckDigit(TEST_VIN));
        assertFalse(Vin.hasValidCheckDigit("1M8GDM9A1KP042788"));
    }

    @Test
    void theReplacementVinIsWellFormedWithAValidCheckDigit() {
        assertTrue(Vin.isWellFormed(Vin.FAKE));
        assertTrue(Vin.hasValidCheckDigit(Vin.FAKE));
        assertEquals(Vin.LENGTH, Vin.FAKE.length());
        assertTrue(Vin.FAKE.startsWith("3VW"), "keeps the VW Mexico WMI");
        assertEquals('D', Vin.FAKE.charAt(9), "keeps model year 2013");
    }

    @Test
    void characterSetExcludesIOQAndLowercase() {
        assertFalse(Vin.isWellFormed("1M8GDM9AXKP04278I"));
        assertFalse(Vin.isWellFormed("1M8GDM9AXKP04278O"));
        assertFalse(Vin.isWellFormed("1M8GDM9AXKP04278Q"));
        assertFalse(Vin.isWellFormed("1m8gdm9axkp042788"));
        assertFalse(Vin.isWellFormed("1M8GDM9AXKP04278"));
        assertFalse(Vin.isWellFormed("1M8GDM9AXKP0427888"));
        assertThrows(IllegalArgumentException.class, () -> Vin.checkDigit("short"));
    }
}
