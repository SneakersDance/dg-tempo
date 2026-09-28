package com.tupai.dgtempo;

import java.util.List;
import java.util.UUID;

/** DG-Lab BLE protocol helpers. Mirrors beatsync_ble.py. */
public final class Protocol {
    public static final UUID SERVICE = UUID.fromString("0000180c-0000-1000-8000-00805f9b34fb");
    public static final UUID CHAR_WRITE = UUID.fromString("0000150a-0000-1000-8000-00805f9b34fb");
    public static final UUID CHAR_NOTIFY = UUID.fromString("0000150b-0000-1000-8000-00805f9b34fb");
    public static final UUID BATTERY_SERVICE = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb");
    public static final UUID CHAR_BATTERY = UUID.fromString("00001500-0000-1000-8000-00805f9b34fb");
    public static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    public static final String COYOTE_NAME = "47L121000";
    public static final String OPOSSUM_NAME = "47L127000";
    public static final double FRAME_S = 0.100;
    public static final double SLOT_S = 0.025;

    private Protocol() {}

    /** "coyote", "opossum" or null from an advertised name. */
    public static String deviceKind(String name) {
        if (name == null) return null;
        if (name.startsWith(COYOTE_NAME.substring(0, 6))) return "coyote";
        if (name.startsWith(OPOSSUM_NAME.substring(0, 6))) return "opossum";
        return null;
    }

    /** Coyote 20-byte B0. mode: high 2 bits = A, low 2 bits = B (00 keep, 01 +, 10 -, 11 set). */
    public static byte[] buildB0(int seq, int mode, int sa, int sb, int[] fa, int[] ia, int[] fb, int[] ib) {
        if (seq < 0 || seq > 15 || mode < 0 || mode > 15) throw new IllegalArgumentException("seq/mode");
        byte[] f = new byte[20];
        f[0] = (byte) 0xB0;
        f[1] = (byte) ((seq << 4) | mode);
        f[2] = (byte) sa;
        f[3] = (byte) sb;
        for (int i = 0; i < 4; i++) {
            f[4 + i] = (byte) fa[i];
            f[8 + i] = (byte) ia[i];
            f[12 + i] = (byte) fb[i];
            f[16 + i] = (byte) ib[i];
        }
        return f;
    }

    /** Coyote 7-byte BF: soft strength caps (0-200), freq balance, intensity balance. */
    public static byte[] buildBF(int capA, int capB, int freqBalA, int freqBalB, int intBalA, int intBalB) {
        return new byte[]{(byte) 0xBF, (byte) capA, (byte) capB, (byte) freqBalA, (byte) freqBalB,
                (byte) intBalA, (byte) intBalB};
    }

    /** Coyote B1 notification -> {seq, a, b} or null. */
    public static int[] parseB1(byte[] d) {
        if (d != null && d.length >= 4 && (d[0] & 0xFF) == 0xB1) return new int[]{d[1] & 0xFF, d[2] & 0xFF, d[3] & 0xFF};
        return null;
    }

    /** Opossum 20-byte B0: 0xB0 + 7x00 + A slots(4) + 4x00 + B slots(4); slot values 0-100. */
    public static byte[] buildOpossumB0(int[] ia, int[] ib) {
        byte[] f = new byte[20];
        f[0] = (byte) 0xB0;
        for (int i = 0; i < 4; i++) {
            f[8 + i] = (byte) ia[i];
            f[16 + i] = (byte) ib[i];
        }
        return f;
    }

    /** Opossum intensity 0-200 per channel; 0xFF leaves that channel unchanged. */
    public static byte[] buildOpossumB3(int a, int b) {
        return new byte[]{(byte) 0xB3, (byte) a, (byte) b};
    }

    /** Opossum on-screen intensity display update after a B3 (20 bytes). */
    public static byte[] buildOpossumB2(int a, int b) {
        byte[] f = new byte[20];
        f[0] = (byte) 0xB2;
        f[1] = (byte) 0xFF;
        f[2] = (byte) 0xFF;
        f[3] = 0;
        for (int i = 4; i < 16; i++) f[i] = (byte) 0xFF;
        f[16] = 0x08;
        f[17] = 0x09;
        f[18] = (byte) a;
        f[19] = (byte) b;
        return f;
    }

    /** Opossum B3 intensity report -> {a, b} or null. */
    public static int[] parseOpossumB3(byte[] d) {
        if (d != null && d.length >= 3 && (d[0] & 0xFF) == 0xB3) return new int[]{d[1] & 0xFF, d[2] & 0xFF};
        return null;
    }

    /** Which of the 4 slots starting at frameStart overlap any [start,end) burst by >= half a slot. */
    public static int[] slotPattern(double frameStart, List<double[]> bursts, int on) {
        int[] out = new int[4];
        for (int j = 0; j < 4; j++) {
            double s0 = frameStart + j * SLOT_S, s1 = s0 + SLOT_S;
            for (double[] b : bursts) {
                if (Math.min(s1, b[1]) - Math.max(s0, b[0]) >= SLOT_S / 2) { out[j] = on; break; }
            }
        }
        return out;
    }

    public static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02X", b & 0xFF));
        return sb.toString();
    }
}
