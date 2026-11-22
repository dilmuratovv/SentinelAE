package uz.sentinel.ae;

/**
 * The points of a subject that a viewer may see. The subject's box is widened by a margin and by where the subject
 * will be a few ticks from now, so a subject appears slightly before it actually leaves cover (no pop-in under lag).
 * Order matters: the most likely visible points come first so a visible subject costs few rays.
 */
public final class SamplePoints {
    public static final int COUNT = 15;

    private SamplePoints() {}

    /**
     * Fills {@code out} (length {@code COUNT * 3}) with x,y,z triples.
     *
     * @param box     minX, minY, minZ, maxX, maxY, maxZ of the subject now
     * @param ahead   predicted displacement (x, y, z) over the look-ahead window
     * @param margin  extra width added on every horizontal side
     */
    public static void fill(double[] box, double[] ahead, double margin, double[] out) {
        double cx = (box[0] + box[3]) / 2, cz = (box[2] + box[5]) / 2;
        double minX = Math.min(box[0], box[0] + ahead[0]) - margin, maxX = Math.max(box[3], box[3] + ahead[0]) + margin;
        double minZ = Math.min(box[2], box[2] + ahead[2]) - margin, maxZ = Math.max(box[5], box[5] + ahead[2]) + margin;
        double minY = Math.min(box[1], box[1] + ahead[1]), maxY = Math.max(box[4], box[4] + ahead[1]);
        double inset = Math.min(0.05, (maxY - minY) / 4);
        double top = maxY - inset, bottom = minY + inset, mid = (box[1] + box[4]) / 2;
        int i = 0;
        // centre column first, then the four widened corners at head, middle and feet height
        i = put(out, i, cx, mid, cz);
        i = put(out, i, cx, top, cz);
        i = put(out, i, cx, bottom, cz);
        for (double y : new double[]{top, mid, bottom}) {
            i = put(out, i, minX, y, minZ);
            i = put(out, i, maxX, y, minZ);
            i = put(out, i, minX, y, maxZ);
            i = put(out, i, maxX, y, maxZ);
        }
    }

    private static int put(double[] out, int i, double x, double y, double z) {
        out[i] = x; out[i + 1] = y; out[i + 2] = z;
        return i + 3;
    }

    /** Ticks until a pair is checked again: close pairs every tick, far pairs less often; concealed pairs faster. */
    public static int interval(double distance, boolean concealed) {
        // concealed pairs decide how fast a subject appears (gameplay), so they are checked often;
        // shown pairs only decide how fast it disappears behind cover, which may take a few ticks
        if (concealed) return distance < 8 ? 1 : distance < 16 ? 2 : distance < 32 ? 3 : 4;
        return distance < 16 ? 4 : distance < 32 ? 6 : 8;
    }
}
