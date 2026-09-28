package com.tupai.dgtempo;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class ProtocolTest {
    @Test public void coyoteB0MatchesDocumentedExample() {
        byte[] f = Protocol.buildB0(0, 0, 0, 0, new int[]{10, 10, 10, 10}, new int[]{0, 10, 20, 30}, new int[4], new int[]{0, 0, 0, 101});
        assertEquals("B00000000A0A0A0A000A141E0000000000000065", Protocol.hex(f));
        byte[] s = Protocol.buildB0(3, 0b1111, 25, 0, new int[]{30, 30, 30, 30}, new int[]{100, 100, 0, 0}, new int[]{30, 30, 30, 30}, new int[4]);
        assertEquals(20, s.length);
        assertEquals(0x3F, s[1] & 0xFF);
        assertEquals(25, s[2]);
    }

    @Test public void bfAndB1() {
        assertEquals("BF1E00A0A00000", Protocol.hex(Protocol.buildBF(30, 0, 160, 160, 0, 0)));
        assertArrayEquals(new int[]{3, 25, 0}, Protocol.parseB1(new byte[]{(byte) 0xB1, 3, 25, 0}));
        assertNull(Protocol.parseB1(new byte[]{(byte) 0xB0, 0}));
    }

    @Test public void opossumFrames() {
        assertEquals("B3A0FF", Protocol.hex(Protocol.buildOpossumB3(160, 0xFF)));
        assertEquals("B3FFC8", Protocol.hex(Protocol.buildOpossumB3(0xFF, 200)));
        assertEquals("B30A14", Protocol.hex(Protocol.buildOpossumB3(10, 20)));
        byte[] b0 = Protocol.buildOpossumB0(new int[]{100, 100, 0, 0}, new int[4]);
        assertEquals("B000000000000000646400000000000000000000", Protocol.hex(b0));
        byte[] b2 = Protocol.buildOpossumB2(40, 0);
        assertEquals(20, b2.length);
        assertEquals("B2FFFF00" + "FF".repeat(12) + "08092800", Protocol.hex(b2));
        assertArrayEquals(new int[]{40, 0}, Protocol.parseOpossumB3(new byte[]{(byte) 0xB3, 40, 0}));
    }

    @Test public void deviceKinds() {
        assertEquals("coyote", Protocol.deviceKind("47L121000"));
        assertEquals("opossum", Protocol.deviceKind("47L127000"));
        assertNull(Protocol.deviceKind("STANMORE"));
        assertNull(Protocol.deviceKind(null));
    }

    @Test public void slotPattern() {
        List<double[]> bursts = Arrays.asList(new double[]{10.03, 10.13});
        assertArrayEquals(new int[]{0, 100, 100, 100}, Protocol.slotPattern(10.0, bursts, 100));
        assertArrayEquals(new int[]{100, 0, 0, 0}, Protocol.slotPattern(10.1, bursts, 100));
        assertArrayEquals(new int[]{0, 0, 0, 0}, Protocol.slotPattern(10.2, bursts, 100));
    }
}
