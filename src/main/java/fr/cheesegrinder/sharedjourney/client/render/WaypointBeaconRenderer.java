package fr.cheesegrinder.sharedjourney.client.render;

import fr.cheesegrinder.sharedjourney.api.SharedJourneyConstants;
import fr.cheesegrinder.sharedjourney.api.Waypoint;
import fr.cheesegrinder.sharedjourney.client.config.WaypointClientConfig;
import fr.cheesegrinder.sharedjourney.client.service.WaypointStore;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BeaconRenderer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * In-world waypoint beacons: VANILLA beacon beam (animated texture, opaque
 * core + halo) tinted with the waypoint's color, a camera-facing waypoint
 * icon ({@link WaypointIcons} sprite, always visible through blocks), and
 * a floating label (name + distance) shown only while the player aims
 * near the waypoint (JourneyMap-like).
 * Shown between the client config's min and max distances.
 */
@EventBusSubscriber(modid = SharedJourneyConstants.MOD_ID, value = Dist.CLIENT)
public final class WaypointBeaconRenderer {

    /** Icon/label anchor height above the waypoint (blocks). */
    private static final double LABEL_HEIGHT_BLOCKS = 1.2;

    /** Icon half-extent in label-space units (the sprite spans 16 units, glyphs are 9). */
    private static final float ICON_HALF_EXTENT = WaypointIcons.SIZE / 2.0f;

    /**
     * Label baseline in label-space units: raised above the icon (icon top
     * is at -{@link #ICON_HALF_EXTENT}, the background pad reaches y0 + 9).
     */
    private static final float LABEL_BASELINE = -(ICON_HALF_EXTENT + 10.0f);

    /** Distance (blocks) where the far-label size boost starts. */
    private static final float LABEL_BOOST_START_BLOCKS = 32.0f;

    /** Cap of the far-label size boost (apparent size multiplier). */
    private static final float LABEL_BOOST_MAX = 1.5f;

    /**
     * Fraction of the render distance beyond which the beam is drawn
     * clamped toward the camera (same direction, full radius): a distant
     * beam would otherwise be fogged out with the terrain, and the whole
     * point of a beacon is to be seen from afar.
     */
    private static final double BEAM_CLAMP_RENDER_DISTANCE_FRACTION = 0.8;

    /**
     * Aiming cone within which the label is shown (JourneyMap-like: the
     * name only appears when the crosshair is near the waypoint).
     */
    private static final double LABEL_VIEW_MIN_COS = Math.cos(Math.toRadians(12.0));

    /** Label background: translucent black, vanilla nameplate style. */
    private static final int LABEL_BACKGROUND = 0x80000000;

    private WaypointBeaconRenderer() {}

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || !WaypointClientConfig.WAYPOINT_BEACONS.get()) {
            return;
        }

        List<Waypoint> waypoints =
                WaypointStore.forDimension(mc.level.dimension().location());
        if (waypoints.isEmpty()) {
            return;
        }

        int minDist = WaypointClientConfig.BEACON_MIN_DISTANCE.get();
        int maxDist = WaypointClientConfig.BEACON_MAX_DISTANCE.get();
        Vec3 cam = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(true);
        double clampDist = beamClampDistance(mc);
        // Two passes: all the beams, flush, then all the labels. In a
        // single batch the (translucent) beam would be drawn after the
        // text and mask it.
        List<Waypoint> shown = new ArrayList<>();
        for (Waypoint wp : waypoints) {
            if (!WaypointStore.isShown(wp)) {
                continue;
            }

            double dx = wp.x() + 0.5 - cam.x;
            double dz = wp.z() + 0.5 - cam.z;
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist < minDist || dist > maxDist) {
                continue;
            }

            shown.add(wp);
            drawBeam(pose, buffers, cam, mc.level, wp, partialTick, clampDist);
        }
        buffers.endBatch();

        boolean showNames = WaypointClientConfig.SHOW_WAYPOINT_NAMES.get();
        Vector3f look = event.getCamera().getLookVector();
        for (Waypoint wp : shown) {
            double dx = wp.x() + 0.5 - cam.x;
            double dy = wp.y() + LABEL_HEIGHT_BLOCKS - cam.y;
            double dz = wp.z() + 0.5 - cam.z;
            double realDist = Math.sqrt(dx * dx + dz * dz);
            // Distant icons/labels are drawn clamped like the beam: at the
            // real position they would be cut by the projection's far
            // plane and never show.
            double drawDist = realDist;
            if (realDist > clampDist && realDist > 1.0e-3) {
                double factor = clampDist / realDist;
                dx *= factor;
                dz *= factor;
                drawDist = clampDist;
            }

            drawIcon(pose, event.getCamera(), buffers, wp, dx, dy, dz, drawDist, realDist);
            if (!showNames) {
                continue;
            }

            // JourneyMap-like: the name only shows when aiming near the
            // waypoint (angle between the look vector and the label).
            double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (len < 1.0e-3) {
                continue;
            }

            double aimCos = (look.x() * dx + look.y() * dy + look.z() * dz) / len;
            if (aimCos < LABEL_VIEW_MIN_COS) {
                continue;
            }

            drawLabel(pose, event.getCamera(), buffers, mc.font, wp, dx, dy, dz, drawDist, realDist);
        }
        buffers.endBatch();
    }

    /** Distance beyond which beams and labels are drawn pulled toward the camera. */
    private static double beamClampDistance(Minecraft mc) {
        return mc.options.getEffectiveRenderDistance() * 16 * BEAM_CLAMP_RENDER_DISTANCE_FRACTION;
    }

    /**
     * Vanilla beacon beam (same rendering as the beacon block: animated
     * texture, opaque core + translucent halo), tinted with the waypoint's
     * color, spanning the full world height. Beams beyond the render
     * distance are drawn clamped toward the camera along the same
     * direction (radius unscaled): they stay visible as horizon pillars
     * instead of being fogged out.
     */
    private static void drawBeam(
            PoseStack pose,
            MultiBufferSource.BufferSource buffers,
            Vec3 cam,
            Level level,
            Waypoint wp,
            float partialTick,
            double clampDist) {
        double dx = wp.x() - cam.x;
        double dz = wp.z() - cam.z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist > clampDist && dist > 1.0e-3) {
            double factor = clampDist / dist;
            dx *= factor;
            dz *= factor;
        }

        pose.pushPose();
        pose.translate(dx, level.getMinBuildHeight() - cam.y, dz);
        int height = level.getMaxBuildHeight() - level.getMinBuildHeight();
        BeaconRenderer.renderBeaconBeam(
                pose,
                buffers,
                BeaconRenderer.BEAM_LOCATION,
                partialTick,
                1.0f,
                level.getGameTime(),
                0,
                height,
                0xFF000000 | wp.colorRgb(),
                0.2f,
                0.25f);
        pose.popPose();
    }

    /**
     * Waypoint icon ({@link WaypointIcons} sprite tinted with the
     * waypoint's color) drawn as a camera-facing billboard at the same
     * anchor as the label. Unlike the label it is ALWAYS shown while the
     * waypoint is within the beacon distance bounds (no aiming cone), and
     * through blocks (see-through text render type: no depth test). Same
     * constant-apparent-size math as the label so both scale together.
     */
    private static void drawIcon(
            PoseStack pose,
            Camera camera,
            MultiBufferSource buffers,
            Waypoint wp,
            double dx,
            double dy,
            double dz,
            double drawDist,
            double realDist) {
        pose.pushPose();
        pose.translate(dx, dy, dz);
        pose.mulPose(camera.rotation());
        float boost = Math.clamp((float) realDist / LABEL_BOOST_START_BLOCKS, 1.0f, LABEL_BOOST_MAX);
        float scale = 0.025f * (float) Math.max(1.0, drawDist / 12.0) * boost;
        pose.scale(scale, -scale, scale);
        Matrix4f mat = pose.last().pose();
        int rgb = wp.colorRgb();
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        float h = ICON_HALF_EXTENT;

        VertexConsumer buf = buffers.getBuffer(RenderType.textSeeThrough(WaypointIcons.textureFor(wp)));
        buf.addVertex(mat, -h, h, 0).setColor(r, g, b, 255).setUv(0f, 1f).setLight(LightTexture.FULL_BRIGHT);
        buf.addVertex(mat, h, h, 0).setColor(r, g, b, 255).setUv(1f, 1f).setLight(LightTexture.FULL_BRIGHT);
        buf.addVertex(mat, h, -h, 0).setColor(r, g, b, 255).setUv(1f, 0f).setLight(LightTexture.FULL_BRIGHT);
        buf.addVertex(mat, -h, -h, 0).setColor(r, g, b, 255).setUv(0f, 0f).setLight(LightTexture.FULL_BRIGHT);
        pose.popPose();
    }

    /**
     * Floating label (name + distance), centered above the waypoint icon
     * (JourneyMap-like), at the camera-relative offset computed by the
     * caller (clamped for distant waypoints). Full-intensity SEE_THROUGH:
     * the label stays readable in any circumstance, even with a block in
     * front.
     */
    private static void drawLabel(
            PoseStack pose,
            Camera camera,
            MultiBufferSource buffers,
            Font font,
            Waypoint wp,
            double dx,
            double dy,
            double dz,
            double drawDist,
            double realDist) {
        String text = wp.name() + " (" + (int) realDist + "m)";
        pose.pushPose();
        pose.translate(dx, dy, dz);
        pose.mulPose(camera.rotation());
        // drawDist/12 keeps the apparent size constant beyond 12 blocks
        // (at the actually drawn position); the boost then grows far
        // labels for readability, capped so they never fill the screen.
        float boost = Math.clamp((float) realDist / LABEL_BOOST_START_BLOCKS, 1.0f, LABEL_BOOST_MAX);
        float scale = 0.025f * (float) Math.max(1.0, drawDist / 12.0) * boost;
        pose.scale(scale, -scale, scale);
        Matrix4f mat = pose.last().pose();
        float width = font.width(text);
        float x0 = -width / 2.0f;
        float y0 = LABEL_BASELINE;

        // Background quad emitted manually BEFORE the text, in its own
        // render type. drawInBatch's built-in background is a white-glyph
        // quad pushed into the SAME distance-sorted buffer as the glyphs:
        // its centroid (label middle) sorts it OVER whichever half of the
        // glyphs stands slightly farther from the camera, graying that
        // half. Separate buffers flush in insertion order: background
        // first, glyphs always on top.
        VertexConsumer bg = buffers.getBuffer(RenderType.textBackgroundSeeThrough());
        bg.addVertex(mat, x0 - 1, y0 + 9, 0).setColor(LABEL_BACKGROUND).setLight(LightTexture.FULL_BRIGHT);
        bg.addVertex(mat, x0 + width + 1, y0 + 9, 0).setColor(LABEL_BACKGROUND).setLight(LightTexture.FULL_BRIGHT);
        bg.addVertex(mat, x0 + width + 1, y0 - 1, 0).setColor(LABEL_BACKGROUND).setLight(LightTexture.FULL_BRIGHT);
        bg.addVertex(mat, x0 - 1, y0 - 1, 0).setColor(LABEL_BACKGROUND).setLight(LightTexture.FULL_BRIGHT);

        font.drawInBatch(
                text,
                x0,
                y0,
                0xFFFFFFFF,
                false,
                mat,
                buffers,
                Font.DisplayMode.SEE_THROUGH,
                0,
                LightTexture.FULL_BRIGHT);
        pose.popPose();
    }
}
