package com.eu.habbo.networking.gameserver.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class PasswordResetEndpointsTest {
    @Test
    void storesOnlyDeterministicSha256DigestOfResetToken() {
        String token = AuthHttpUtil.mintResetToken();
        String digest = PasswordResetEndpoints.hashToken(token);
        assertEquals(43, token.length());
        assertEquals(64, digest.length());
        assertNotEquals(token, digest);
        assertEquals(digest, PasswordResetEndpoints.hashToken(token));
        assertNotEquals(digest, PasswordResetEndpoints.hashToken(AuthHttpUtil.mintResetToken()));
    }
}
