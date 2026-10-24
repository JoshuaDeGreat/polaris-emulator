package com.eu.habbo.networking.gameserver.auth;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class AccountEmailTemplateTest {
    @Test
    void escapesUserContentAndPreservesOneTimeFragmentLinks() {
        String url = "https://play.heavenhotel.uk/?recovery=1#token=" + "A".repeat(43);
        String html = AccountEmailTemplate.passwordReset("<img src=x onerror=alert(1)> {{url}}", url);
        assertTrue(html.contains("&lt;img src=x onerror=alert(1)&gt; {{url}}"));
        assertFalse(html.contains("<img src=x"));
        assertTrue(html.contains("href=\"" + url + "\""));
        assertTrue(html.contains("20 minutes"));
        assertTrue(html.contains("heaven-logo.png"));
    }

    @Test
    void verificationUsesItsOwnActionAndExpiry() {
        String html = AccountEmailTemplate.emailVerification("https://play.heavenhotel.uk/?verify=1#token=test");
        assertTrue(html.contains("Verify my email"));
        assertTrue(html.contains("30 minutes"));
        assertFalse(html.contains("{{"));
        assertTrue(html.contains("lang=\"en\""));
    }
}
