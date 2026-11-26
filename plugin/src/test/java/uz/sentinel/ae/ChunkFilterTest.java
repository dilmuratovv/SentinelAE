package uz.sentinel.ae;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Random;
import org.junit.jupiter.api.Test;

class ChunkFilterTest {
    static final int AIR = 0, STONE = 1, CHEST = 3000, BARREL = 20000; // 2-byte and 3-byte VarInts

    /** Builds sections the way the server writes them; returns the bytes and the expected block ids. */
    static final class Builder {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final DataOutputStream out = new DataOutputStream(bytes);
        final boolean fluid, prefix;
        Builder(boolean fluid, boolean prefix) { this.fluid = fluid; this.prefix = prefix; }

        void varInt(int v) throws IOException {
            while ((v & ~0x7F) != 0) { out.writeByte((v & 0x7F) | 0x80); v >>>= 7; }
            out.writeByte(v);
        }

        void data(int[] values, int bits) throws IOException {
            int perLong = 64 / bits, longs = (values.length + perLong - 1) / perLong;
            if (prefix) varInt(longs);
            long[] words = new long[longs];
            for (int i = 0; i < values.length; i++) words[i / perLong] |= (long) values[i] << ((i % perLong) * bits);
            for (long w : words) out.writeLong(w);
        }

        /** A section with a palette (bits 4..8) or direct ids (bits 15). */
        void section(int[] ids, int bits) throws IOException {
            out.writeShort(4096);
            if (fluid) out.writeShort(0);
            out.writeByte(bits);
            if (bits <= 8) {
                int[] palette = java.util.Arrays.stream(ids).distinct().toArray();
                varInt(palette.length);
                for (int p : palette) varInt(p);
                int[] idx = new int[ids.length];
                for (int i = 0; i < ids.length; i++) for (int k = 0; k < palette.length; k++) if (palette[k] == ids[i]) idx[i] = k;
                data(idx, bits);
            } else {
                data(ids, bits);
            }
            biomes();
        }

        void single(int id) throws IOException {
            out.writeShort(id == AIR ? 0 : 4096);
            if (fluid) out.writeShort(0);
            out.writeByte(0);
            varInt(id);
            if (prefix) varInt(0);
            biomes();
        }

        void biomes() throws IOException {
            out.writeByte(0); varInt(7); if (prefix) varInt(0);
        }
    }

    /** Reads the block ids back like a client would (normal VarInt reading accepts the over-long entries). */
    static int[] decode(byte[] buf, boolean fluid, boolean prefix, int section) {
        int[] pos = {0};
        for (int s = 0; ; s++) {
            pos[0] += fluid ? 4 : 2;
            int bits = buf[pos[0]++] & 0xFF;
            int[] out = new int[4096];
            if (bits == 0) {
                int id = ChunkFilter.readVarInt(buf, pos);
                if (prefix) ChunkFilter.readVarInt(buf, pos);
                java.util.Arrays.fill(out, id);
            } else {
                int[] palette = null;
                if (bits <= 8) {
                    palette = new int[ChunkFilter.readVarInt(buf, pos)];
                    for (int i = 0; i < palette.length; i++) palette[i] = ChunkFilter.readVarInt(buf, pos);
                }
                int perLong = 64 / bits, longs = (4096 + perLong - 1) / perLong;
                if (prefix) ChunkFilter.readVarInt(buf, pos);
                for (int i = 0; i < 4096; i++) {
                    int at = pos[0] + (i / perLong) * 8;
                    long w = 0;
                    for (int k = 0; k < 8; k++) w = w << 8 | (buf[at + k] & 0xFF);
                    int v = (int) ((w >>> ((i % perLong) * bits)) & ((1L << bits) - 1));
                    out[i] = palette == null ? v : palette[v];
                }
                pos[0] += longs * 8;
            }
            pos[0]++; ChunkFilter.readVarInt(buf, pos); if (prefix) ChunkFilter.readVarInt(buf, pos); // biomes
            if (s == section) return out;
        }
    }

    private static int[] world(long seed) {
        Random r = new Random(seed);
        int[] ids = new int[4096];
        for (int i = 0; i < ids.length; i++) ids[i] = r.nextInt(10) == 0 ? CHEST : r.nextInt(30) == 0 ? BARREL : r.nextBoolean() ? STONE : AIR;
        return ids;
    }

    @Test
    void everyLayoutPaletteAndDirect() throws IOException {
        for (boolean fluid : new boolean[]{false, true}) {
            for (boolean prefix : new boolean[]{false, true}) {
                for (int bits : new int[]{4, 8, 15}) {
                    Builder b = new Builder(fluid, prefix);
                    int[] ids = world(bits * 31L + (fluid ? 1 : 0) + (prefix ? 2 : 0));
                    b.single(AIR);
                    b.section(ids, bits);
                    b.single(STONE);
                    byte[] buf = b.bytes.toByteArray();
                    int length = buf.length;
                    ChunkFilter.Result r = ChunkFilter.hide(buf, id -> id == CHEST || id == BARREL, AIR);
                    assertNotNull(r, "layout fluid=" + fluid + " prefix=" + prefix + " bits=" + bits);
                    assertEquals(3, r.sections());
                    assertEquals(fluid, r.fluidCount());
                    assertEquals(length, buf.length);
                    int[] after = decode(buf, fluid, prefix, 1);
                    int expectedHidden = 0;
                    for (int i = 0; i < 4096; i++) {
                        boolean storage = ids[i] == CHEST || ids[i] == BARREL;
                        if (storage) expectedHidden++;
                        assertEquals(storage ? AIR : ids[i], after[i], "block " + i);
                    }
                    assertEquals(expectedHidden, r.hidden().length);
                    for (int h : r.hidden()) assertEquals(1, h >>> 12);
                    assertArrayEquals(new int[4096], decode(buf, fluid, prefix, 0));
                }
            }
        }
    }

    @Test
    void unknownLayoutIsLeftAlone() {
        byte[] junk = {1, 2, 3, 4, 5, 6, 7};
        assertNull(ChunkFilter.hide(junk, id -> true, AIR));
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5, 6, 7}, junk);
    }

    @Test
    void overlongVarIntReadsBack() {
        byte[] b = new byte[3];
        ChunkFilter.writeOverlong(b, 0, 3, 0);
        assertEquals(0, ChunkFilter.readVarInt(b, new int[]{0}));
        ChunkFilter.writeOverlong(b, 0, 2, 5);
        assertEquals(5, ChunkFilter.readVarInt(b, new int[]{0}));
    }

    private static byte[] emptyChunk(boolean fluid, boolean prefix, int sections) throws IOException {
        Builder b = new Builder(fluid, prefix);
        for (int i = 0; i < sections; i++) b.single(AIR);
        return b.bytes.toByteArray();
    }

    @org.junit.jupiter.api.AfterEach
    void resetExpected() { ChunkFilter.expect("unknown"); }

    @Test
    void versionsMapToTheirLayout() {
        assertArrayEquals(new boolean[]{true, false}, ChunkFilter.layoutOf("26.2"));
        assertArrayEquals(new boolean[]{true, false}, ChunkFilter.layoutOf("26.3.1"));
        assertArrayEquals(new boolean[]{false, false}, ChunkFilter.layoutOf("1.21.11"));
        assertArrayEquals(new boolean[]{false, false}, ChunkFilter.layoutOf("1.21.5"));
        assertArrayEquals(new boolean[]{false, true}, ChunkFilter.layoutOf("1.21.4"));
        assertArrayEquals(new boolean[]{false, true}, ChunkFilter.layoutOf("1.21"));
        assertNull(ChunkFilter.layoutOf("nonsense"));
    }

    @Test
    void anAllAirChunkFitsSeveralLayoutsAndIsNotGuessed() throws IOException {
        // 24 empty sections of the 26.x layout are also a complete read in another layout: without knowing the
        // server's layout nothing may be rewritten, and with it the right layout is used.
        byte[] chunk = emptyChunk(true, false, 24);
        ChunkFilter.AMBIGUOUS.reset();
        assertNull(ChunkFilter.hide(chunk.clone(), id -> true, AIR));
        assertEquals(1, ChunkFilter.AMBIGUOUS.sum());
        ChunkFilter.expect("26.2");
        ChunkFilter.Result r = ChunkFilter.hide(chunk.clone(), id -> id == CHEST, AIR);
        assertNotNull(r);
        assertEquals(24, r.sections());
        assertTrue(r.fluidCount());
    }

    @Test
    void theServersLayoutWinsOverAnotherThatAlsoFits() throws IOException {
        for (boolean[] l : new boolean[][]{{true, false}, {false, false}, {false, true}}) {
            Builder b = new Builder(l[0], l[1]);
            for (int i = 0; i < 3; i++) b.section(world(i + 5), 8);
            byte[] buf = b.bytes.toByteArray();
            ChunkFilter.expect(l[0] ? "26.2" : l[1] ? "1.21.4" : "1.21.11");
            ChunkFilter.Result r = ChunkFilter.hide(buf, id -> id == CHEST || id == BARREL, AIR);
            assertNotNull(r);
            assertEquals(l[0], r.fluidCount());
            assertEquals(l[1], r.lengthPrefix());
            for (int[] ids : new int[][]{decode(buf, l[0], l[1], 0), decode(buf, l[0], l[1], 2)})
                for (int id : ids) assertTrue(id != CHEST && id != BARREL);
        }
    }

    @Test
    void aLayoutThatDoesNotMatchTheServerIsOnlyUsedWhenItIsTheOnlyFit() throws IOException {
        Builder b = new Builder(false, true);
        b.section(world(9), 8);
        byte[] buf = b.bytes.toByteArray();
        ChunkFilter.expect("26.2"); // the server says fluid layout, the data is the old prefixed one
        ChunkFilter.Result r = ChunkFilter.hide(buf, id -> id == CHEST, AIR);
        assertNotNull(r);
        assertTrue(r.lengthPrefix());
    }

    @Test
    void unreadableChunksAreCountedNotTouched() {
        byte[] junk = new byte[64];
        java.util.Arrays.fill(junk, (byte) 0xFF);
        ChunkFilter.UNPARSED.reset();
        ChunkFilter.expect("26.2");
        assertNull(ChunkFilter.hide(junk, id -> true, AIR));
        assertEquals(1, ChunkFilter.UNPARSED.sum());
        for (byte x : junk) assertEquals((byte) 0xFF, x);
    }
}
