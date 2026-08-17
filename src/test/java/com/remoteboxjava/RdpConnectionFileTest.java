package com.remoteboxjava;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RdpConnectionFileTest {

    @Test
    void requestsTheConfiguredStartSizeWithoutSmartSizing() {
        String contents = RdpConnectionFile.contents("vbox.example.org", 5000, 1800, 950, 32, true);
        assertTrue(contents.contains("full address:s:vbox.example.org:5000"));
        assertTrue(contents.contains("screen mode id:i:1"));
        assertTrue(contents.contains("use multimon:i:0"));
        assertTrue(contents.contains("dynamic resolution:i:1"));
        assertTrue(contents.contains("smart sizing:i:0"));
        assertTrue(contents.contains("desktopwidth:i:1800"));
        assertTrue(contents.contains("desktopheight:i:950"));
        assertTrue(contents.contains("session bpp:i:32"));
    }

    @Test
    void redirectsNothingButTheClipboardAndOnlyOnRequest() {
        String shared = RdpConnectionFile.contents("host", 3389, 1024, 768, 32, true);
        assertTrue(shared.contains("redirectclipboard:i:1"));
        assertTrue(shared.contains("redirectwebauthn:i:0"));
        assertTrue(shared.contains("redirectprinters:i:0"));
        assertTrue(shared.contains("redirectcomports:i:0"));
        assertTrue(shared.contains("redirectsmartcards:i:0"));
        assertTrue(shared.contains("drivestoredirect:s:\r\n"));

        assertTrue(RdpConnectionFile.contents("host", 3389, 1024, 768, 32, false)
                .contains("redirectclipboard:i:0"));
    }

    @Test
    void leavesCredsspAtItsDefaultSoMstscStillNegotiates() {
        String contents = RdpConnectionFile.contents("host", 3389, 1024, 768, 32, true);
        assertFalse(contents.contains("enablecredsspsupport"));
        assertTrue(contents.contains("authentication level:i:0"));
    }

    @Test
    void omitsTheScaleFactorTheVrdeServerIgnores() {
        assertFalse(RdpConnectionFile.contents("host", 3389, 1024, 768, 32, true)
                .contains("desktopscalefactor"));
    }

    @Test
    void bracketsLiteralIpv6Addresses() {
        assertTrue(RdpConnectionFile.contents("fe80::1", 3389, 1024, 768, 32, true)
                .contains("full address:s:[fe80::1]:3389"));
        assertTrue(RdpConnectionFile.contents("[fe80::1]", 3389, 1024, 768, 32, true)
                .contains("full address:s:[fe80::1]:3389"));
    }

    @Test
    void rejectsAHostThatCouldInjectAnotherSetting() {
        assertThrows(IllegalArgumentException.class,
                () -> RdpConnectionFile.contents("host\r\nsmart sizing:i:0", 3389, 1024, 768, 32, true));
        assertThrows(IllegalArgumentException.class,
                () -> RdpConnectionFile.contents("", 3389, 1024, 768, 32, true));
    }
}
