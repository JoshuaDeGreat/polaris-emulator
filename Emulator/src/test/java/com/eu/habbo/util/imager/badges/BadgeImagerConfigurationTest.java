package com.eu.habbo.util.imager.badges;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class BadgeImagerConfigurationTest {
    @Test
    void deploymentOverrideCanDisableUnusedGenerationWithoutChangingLegacyDefaults() {
        assertTrue(BadgeImager.enabledFor(null, true));
        assertFalse(BadgeImager.enabledFor(null, false));
        assertTrue(BadgeImager.enabledFor(" ", true));
        assertFalse(BadgeImager.enabledFor("false", true));
        assertFalse(BadgeImager.enabledFor("0", true));
        assertTrue(BadgeImager.enabledFor("TRUE", false));
        assertTrue(BadgeImager.enabledFor("1", false));
        assertThrows(IllegalArgumentException.class, () -> BadgeImager.enabledFor("typo", true));
    }
}
