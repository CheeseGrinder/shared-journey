package fr.cheesegrinder.sharedjourney.client.render;

/**
 * Data model of the mob icon settings files (see {@link MobIconTweaks}):
 * the partial entries read from JSON, their field-by-field merge, and the
 * resolved values the icon renderer consumes.
 */
final class MobIconSettings {

    private MobIconSettings() {}

    /** Which part of the model an icon shows. */
    enum Mode {
        /** The head when one is found (and big enough), the whole model otherwise. */
        AUTO,
        /** Same as AUTO: the head, falling back to the whole model when none is usable. */
        HEAD,
        /** The whole model, skipping head detection. */
        WHOLE
    }

    /**
     * Resolved framing of one mob. {@code rotX}/{@code rotY} turn the
     * model (degrees), {@code scale} multiplies the natural icon scale,
     * {@code headPart} (nullable) names the model part to treat as the
     * head, and the offsets shift the content inside the cell, in blocks
     * (positive = right / down).
     */
    record Tweak(float rotX, float rotY, Mode mode, float scale, String headPart, float offsetX, float offsetY) {

        static final Tweak DEFAULT = new Tweak(0f, 0f, Mode.AUTO, 1f, null, 0f, 0f);
    }

    /** Resolved global tuning shared by every icon. */
    record Globals(float wholeModelScale, float maxFill, float minFill) {

        static final Globals DEFAULT = new Globals(0.5f, 0.5f, 0.2f);
    }

    /** Partial per-mob settings: a null field means "not specified here". */
    record Entry(Float rotX, Float rotY, Mode mode, Float scale, String headPart, Float offsetX, Float offsetY) {

        static final Entry EMPTY = new Entry(null, null, null, null, null, null, null);

        /** This entry with every field {@code over} specifies replaced. */
        Entry overlay(Entry over) {
            if (over == null) {
                return this;
            }

            return new Entry(
                    pick(over.rotX, rotX),
                    pick(over.rotY, rotY),
                    pick(over.mode, mode),
                    pick(over.scale, scale),
                    pick(over.headPart, headPart),
                    pick(over.offsetX, offsetX),
                    pick(over.offsetY, offsetY));
        }

        /** Fills the unspecified fields with the defaults. */
        Tweak resolve() {
            Tweak d = Tweak.DEFAULT;
            return new Tweak(
                    pick(rotX, d.rotX()),
                    pick(rotY, d.rotY()),
                    pick(mode, d.mode()),
                    pick(scale, d.scale()),
                    headPart,
                    pick(offsetX, d.offsetX()),
                    pick(offsetY, d.offsetY()));
        }
    }

    /** Partial global tuning: a null field means "not specified here". */
    record GlobalEntry(Float wholeModelScale, Float maxFill, Float minFill) {

        static final GlobalEntry EMPTY = new GlobalEntry(null, null, null);

        GlobalEntry overlay(GlobalEntry over) {
            return new GlobalEntry(
                    pick(over.wholeModelScale, wholeModelScale),
                    pick(over.maxFill, maxFill),
                    pick(over.minFill, minFill));
        }

        Globals resolve() {
            Globals d = Globals.DEFAULT;
            return new Globals(
                    pick(wholeModelScale, d.wholeModelScale()), pick(maxFill, d.maxFill()), pick(minFill, d.minFill()));
        }
    }

    private static <T> T pick(T preferred, T fallback) {
        if (preferred != null) {
            return preferred;
        }

        return fallback;
    }
}
