package io.freemytv;

import java.nio.charset.StandardCharsets;

/**
 * A small QR code encoder (byte mode, error correction M, versions 1 to 3: up to 42 bytes), for the Trakt sign-in
 * address. Nothing else in the app needs one, so there is no library. Follows ISO/IEC 18004; the layout and the
 * Reed-Solomon code are the usual ones.
 */
final class Qr {
    private Qr() {}

    /** {version, data codewords, error correction codewords} at level M; one block each. */
    private static final int[][] VERSIONS = {{1, 16, 10}, {2, 28, 16}, {3, 44, 26}};

    /** The code as rows of modules (true = dark), without the quiet zone; null when `text` is longer than 42 bytes. */
    static boolean[][] encode(String text) {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        int[] v = null;
        for (int[] candidate : VERSIONS) if (data.length <= candidate[1] - 2) { v = candidate; break; }
        if (v == null) return null;
        int version = v[0], dataWords = v[1], ecWords = v[2];

        // Mode (byte), length, the bytes, a terminator, then padding up to the capacity.
        int[] bits = new int[dataWords * 8];
        int n = 0;
        n = put(bits, n, 0x4, 4);
        n = put(bits, n, data.length, 8);
        for (byte b : data) n = put(bits, n, b & 0xFF, 8);
        n += Math.min(4, bits.length - n);
        n = (n + 7) / 8 * 8;
        for (int pad = 0xEC; n < bits.length; pad ^= 0xEC ^ 0x11) n = put(bits, n, pad, 8);
        int[] words = new int[dataWords + ecWords];
        for (int i = 0; i < bits.length; i++) words[i >>> 3] |= bits[i] << (7 - (i & 7));
        int[] ec = remainder(java.util.Arrays.copyOf(words, dataWords), ecWords);
        System.arraycopy(ec, 0, words, dataWords, ecWords);

        int size = version * 4 + 17;
        boolean[][] dark = new boolean[size][size], fixed = new boolean[size][size];
        for (int i = 0; i < size; i++) {       // timing patterns
            set(dark, fixed, 6, i, i % 2 == 0);
            set(dark, fixed, i, 6, i % 2 == 0);
        }
        finder(dark, fixed, 3, 3);
        finder(dark, fixed, size - 4, 3);
        finder(dark, fixed, 3, size - 4);
        if (version >= 2) {                    // the one alignment pattern
            for (int dy = -2; dy <= 2; dy++) for (int dx = -2; dx <= 2; dx++) {
                set(dark, fixed, size - 7 + dx, size - 7 + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
            }
        }
        format(dark, fixed, 0);                // reserves the format areas
        place(dark, fixed, words);

        int best = 0, lowest = Integer.MAX_VALUE;
        for (int mask = 0; mask < 8; mask++) {
            mask(dark, fixed, mask);
            format(dark, fixed, mask);
            int penalty = penalty(dark);
            if (penalty < lowest) { lowest = penalty; best = mask; }
            mask(dark, fixed, mask);           // undo
        }
        mask(dark, fixed, best);
        format(dark, fixed, best);
        return dark;
    }

    private static int put(int[] bits, int at, int value, int count) {
        for (int i = count - 1; i >= 0; i--) bits[at++] = (value >>> i) & 1;
        return at;
    }

    private static void set(boolean[][] dark, boolean[][] fixed, int x, int y, boolean on) {
        if (x < 0 || y < 0 || y >= dark.length || x >= dark.length) return;
        dark[y][x] = on;
        fixed[y][x] = true;
    }

    /** A finder pattern with its separator, centred on (cx, cy). */
    private static void finder(boolean[][] dark, boolean[][] fixed, int cx, int cy) {
        for (int dy = -4; dy <= 4; dy++) for (int dx = -4; dx <= 4; dx++) {
            int d = Math.max(Math.abs(dx), Math.abs(dy));
            set(dark, fixed, cx + dx, cy + dy, d != 2 && d != 4);
        }
    }

    /** The two copies of the 15 format bits (level M and the mask), and the always-dark module. */
    private static void format(boolean[][] dark, boolean[][] fixed, int mask) {
        int size = dark.length, data = mask;     // level M is 00
        int rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = ((data << 10) | rem) ^ 0x5412;
        for (int i = 0; i <= 5; i++) set(dark, fixed, 8, i, bit(bits, i));
        set(dark, fixed, 8, 7, bit(bits, 6));
        set(dark, fixed, 8, 8, bit(bits, 7));
        set(dark, fixed, 7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i++) set(dark, fixed, 14 - i, 8, bit(bits, i));
        for (int i = 0; i < 8; i++) set(dark, fixed, size - 1 - i, 8, bit(bits, i));
        for (int i = 8; i < 15; i++) set(dark, fixed, 8, size - 15 + i, bit(bits, i));
        set(dark, fixed, 8, size - 8, true);
    }

    private static boolean bit(int value, int i) { return ((value >>> i) & 1) != 0; }

    /** The codeword bits, in the two-column zigzag from the bottom right. */
    private static void place(boolean[][] dark, boolean[][] fixed, int[] words) {
        int size = dark.length, i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean up = ((right + 1) & 2) == 0;
                    int y = up ? size - 1 - vert : vert;
                    if (!fixed[y][x] && i < words.length * 8) {
                        dark[y][x] = bit(words[i >>> 3], 7 - (i & 7));
                        i++;
                    }
                }
            }
        }
    }

    private static void mask(boolean[][] dark, boolean[][] fixed, int mask) {
        for (int y = 0; y < dark.length; y++) for (int x = 0; x < dark.length; x++) {
            boolean flip;
            switch (mask) {
                case 0: flip = (x + y) % 2 == 0; break;
                case 1: flip = y % 2 == 0; break;
                case 2: flip = x % 3 == 0; break;
                case 3: flip = (x + y) % 3 == 0; break;
                case 4: flip = (x / 3 + y / 2) % 2 == 0; break;
                case 5: flip = x * y % 2 + x * y % 3 == 0; break;
                case 6: flip = (x * y % 2 + x * y % 3) % 2 == 0; break;
                default: flip = ((x + y) % 2 + x * y % 3) % 2 == 0; break;
            }
            if (flip && !fixed[y][x]) dark[y][x] = !dark[y][x];
        }
    }

    /** The standard four penalties: runs of five or more, 2x2 blocks, finder-like patterns, and an uneven dark share. */
    private static int penalty(boolean[][] m) {
        int size = m.length, total = 0, darkCount = 0;
        for (int a = 0; a < size; a++) {
            for (int dir = 0; dir < 2; dir++) {
                int run = 1;
                for (int b = 1; b < size; b++) {
                    boolean same = dir == 0 ? m[a][b] == m[a][b - 1] : m[b][a] == m[b - 1][a];
                    if (same) run++;
                    else { if (run >= 5) total += run - 2; run = 1; }
                }
                if (run >= 5) total += run - 2;
                for (int b = 0; b + 11 <= size; b++) {
                    boolean one = true, two = true;
                    for (int k = 0; k < 11; k++) {
                        boolean cell = dir == 0 ? m[a][b + k] : m[b + k][a];
                        one &= cell == (FINDER[k] == 1);
                        two &= cell == (FINDER[10 - k] == 1);
                    }
                    if (one) total += 40;
                    if (two) total += 40;
                }
            }
        }
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
            if (m[y][x]) darkCount++;
            if (x + 1 < size && y + 1 < size && m[y][x] == m[y][x + 1] && m[y][x] == m[y + 1][x] && m[y][x] == m[y + 1][x + 1]) total += 3;
        }
        int percent = darkCount * 100 / (size * size);
        total += Math.abs(percent - 50) / 5 * 10;
        return total;
    }

    private static final int[] FINDER = {1, 0, 1, 1, 1, 0, 1, 0, 0, 0, 0};

    // MARK: - Reed-Solomon over GF(256), x^8 + x^4 + x^3 + x^2 + 1

    private static int multiply(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z;
    }

    private static int[] remainder(int[] data, int degree) {
        int[] divisor = new int[degree];
        divisor[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < degree; j++) {
                divisor[j] = multiply(divisor[j], root);
                if (j + 1 < degree) divisor[j] ^= divisor[j + 1];
            }
            root = multiply(root, 0x02);
        }
        int[] result = new int[degree];
        for (int b : data) {
            int factor = b ^ result[0];
            System.arraycopy(result, 1, result, 0, degree - 1);
            result[degree - 1] = 0;
            for (int i = 0; i < degree; i++) result[i] ^= multiply(divisor[i], factor);
        }
        return result;
    }
}
