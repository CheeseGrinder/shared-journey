package fr.cheesegrinder.sharedjourney.client.render;

import net.minecraft.client.model.BeeModel;
import net.minecraft.client.model.CodModel;
import net.minecraft.client.model.ColorableHierarchicalModel;
import net.minecraft.client.model.DolphinModel;
import net.minecraft.client.model.EndermiteModel;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.GuardianModel;
import net.minecraft.client.model.HoglinModel;
import net.minecraft.client.model.HorseModel;
import net.minecraft.client.model.LavaSlimeModel;
import net.minecraft.client.model.PhantomModel;
import net.minecraft.client.model.PufferfishBigModel;
import net.minecraft.client.model.PufferfishMidModel;
import net.minecraft.client.model.PufferfishSmallModel;
import net.minecraft.client.model.SalmonModel;
import net.minecraft.client.model.SilverfishModel;
import net.minecraft.client.model.SlimeModel;
import net.minecraft.client.model.WitherBossModel;

/**
 * Per-model framing exceptions for the radar mob icons.
 *
 * <p>The default framing (head part when one is found, whole model
 * otherwise, centered on the cubes' bounds, fixed pixels-per-block scale)
 * covers most mobs. This table only lists the models whose natural pose
 * reads badly face-on. It is keyed by model class (not entity type) so
 * variants and modded mobs sharing a vanilla model inherit the fix.
 *
 * <p>Rotations are in degrees, applied to the model in its own space
 * (Y first, then X) before centering. Values come from the exceptions
 * Xaero's Minimap needs for the same vanilla models; they are starting
 * points to tune in game, a wrong sign shows as a mirrored tilt.
 */
final class MobIconTweaks {

    /**
     * Framing exception for one model. {@code rotX} tilts the model
     * (head-only icons of angled heads, top views), {@code rotY} turns it
     * (profile views), {@code wholeModel} skips head detection and frames
     * the entire model.
     */
    record Tweak(float rotX, float rotY, boolean wholeModel) {}

    private static final Tweak DEFAULT = new Tweak(0f, 0f, false);
    /** Whole model seen in profile (long flat bodies: fish). */
    private static final Tweak WHOLE_PROFILE = new Tweak(0f, 90f, true);
    /** Whole model, face-on: no meaningful head to isolate. */
    private static final Tweak WHOLE = new Tweak(0f, 0f, true);
    /** Head tilted toward the viewer (long angled snouts). */
    private static final Tweak HORSE = new Tweak(65f, 0f, false);

    private static final Tweak HOGLIN = new Tweak(45f, 0f, false);
    /** Flying body seen from above. */
    private static final Tweak PHANTOM = new Tweak(90f, 0f, true);

    private MobIconTweaks() {}

    /** The framing exception for {@code model}, or the default framing. */
    static Tweak forModel(EntityModel<?> model) {
        if (isProfileFish(model)) {
            return WHOLE_PROFILE;
        }

        if (isWholeModel(model)) {
            return WHOLE;
        }

        if (model instanceof HorseModel<?>) {
            return HORSE;
        }

        if (model instanceof HoglinModel<?>) {
            return HOGLIN;
        }

        if (model instanceof PhantomModel<?>) {
            return PHANTOM;
        }

        return DEFAULT;
    }

    private static boolean isProfileFish(EntityModel<?> model) {
        return model instanceof CodModel<?>
                || model instanceof SalmonModel<?>
                || model instanceof ColorableHierarchicalModel<?>;
    }

    private static boolean isWholeModel(EntityModel<?> model) {
        return model instanceof BeeModel<?>
                || model instanceof DolphinModel<?>
                || model instanceof GuardianModel
                || model instanceof EndermiteModel<?>
                || model instanceof LavaSlimeModel<?>
                || model instanceof SlimeModel<?>
                || model instanceof PufferfishBigModel<?>
                || model instanceof PufferfishMidModel<?>
                || model instanceof PufferfishSmallModel<?>
                || model instanceof SilverfishModel<?>
                || model instanceof WitherBossModel<?>;
    }
}
