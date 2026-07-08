package fr.cheesegrinder.sharedjourney.client.render;

import fr.cheesegrinder.sharedjourney.api.SharedJourneyConstants;
import fr.cheesegrinder.sharedjourney.api.Waypoint;
import fr.cheesegrinder.sharedjourney.client.config.ClientConfig;
import fr.cheesegrinder.sharedjourney.client.service.WaypointStore;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BeaconRenderer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4f;

import java.util.List;

/**
 * Beacons de waypoints dans le monde : faisceau de beacon VANILLA (texture
 * animée, cœur opaque + halo) teinté de la couleur du waypoint, et étiquette
 * flottante (nom + distance) visible à travers les blocs.
 * Affichés entre les distances min et max de la config client.
 */
@EventBusSubscriber(modid = SharedJourneyConstants.MOD_ID, value = Dist.CLIENT)
public final class WaypointBeaconRenderer {

    /** Décalage horizontal de l'étiquette vers la caméra (sort du faisceau). */
    private static final double LABEL_CAMERA_OFFSET = 0.75;

    private WaypointBeaconRenderer() {}

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || !ClientConfig.WAYPOINT_BEACONS.get()) {
            return;
        }

        List<Waypoint> waypoints = WaypointStore.forDimension(mc.level.dimension().location());
        if (waypoints.isEmpty()) {
            return;
        }

        int minDist = ClientConfig.BEACON_MIN_DISTANCE.get();
        int maxDist = ClientConfig.BEACON_MAX_DISTANCE.get();
        Vec3 cam = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(true);
        for (Waypoint wp : waypoints) {
            if (!wp.visible()) {
                continue;
            }

            double dx = wp.x() + 0.5 - cam.x;
            double dz = wp.z() + 0.5 - cam.z;
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist < minDist || dist > maxDist) {
                continue;
            }

            drawBeam(pose, buffers, cam, mc.level, wp, partialTick);
            drawLabel(pose, event.getCamera(), buffers, mc.font, cam, wp, dist);
        }
        buffers.endBatch();
    }

    /**
     * Faisceau de beacon vanilla (même rendu que le bloc beacon : texture
     * animée, cœur opaque + halo translucide), teinté de la couleur du
     * waypoint, sur toute la hauteur du monde.
     */
    private static void drawBeam(
            PoseStack pose,
            MultiBufferSource.BufferSource buffers,
            Vec3 cam,
            Level level,
            Waypoint wp,
            float partialTick) {
        pose.pushPose();
        pose.translate(wp.x() - cam.x, level.getMinBuildHeight() - cam.y, wp.z() - cam.z);
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
     * Étiquette flottante (nom + distance) : 1 bloc au-dessus du sommet du
     * point (waystone = 2 blocs de haut), légèrement décalée vers la caméra
     * pour sortir du faisceau et rester lisible. Orientée face caméra et
     * grossie avec la distance.
     */
    private static void drawLabel(
            PoseStack pose, Camera camera, MultiBufferSource buffers, Font font, Vec3 cam, Waypoint wp, double dist) {
        String text = wp.name() + " (" + (int) dist + "m)";
        double bx = wp.x() + 0.5;
        double bz = wp.z() + 0.5;
        double dx = cam.x - bx;
        double dz = cam.z - bz;
        double len = Math.sqrt(dx * dx + dz * dz);
        double offX = len < 0.01 ? 0 : dx / len * LABEL_CAMERA_OFFSET;
        double offZ = len < 0.01 ? 0 : dz / len * LABEL_CAMERA_OFFSET;
        pose.pushPose();
        pose.translate(bx - cam.x + offX, wp.y() + 3.0 - cam.y, bz - cam.z + offZ);
        pose.mulPose(camera.rotation());
        float scale = 0.025f * (float) Math.max(1.0, dist / 12.0);
        pose.scale(scale, -scale, scale);
        Matrix4f mat = pose.last().pose();
        float xOff = -font.width(text) / 2f;
        int bg = 0x66000000;
        font.drawInBatch(
                text, xOff, 0, 0xFFFFFFFF, false, mat, buffers, Font.DisplayMode.SEE_THROUGH, bg, LightTexture.FULL_BRIGHT);
        pose.popPose();
    }
}
