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

import java.util.ArrayList;
import java.util.List;

/**
 * Beacons de waypoints dans le monde : faisceau de beacon VANILLA (texture
 * animée, cœur opaque + halo) teinté de la couleur du waypoint, et étiquette
 * flottante (nom + distance) visible à travers les blocs.
 * Affichés entre les distances min et max de la config client.
 */
@EventBusSubscriber(modid = SharedJourneyConstants.MOD_ID, value = Dist.CLIENT)
public final class WaypointBeaconRenderer {

    /** Décalage latéral de l'étiquette (blocs) : hors du faisceau (rayon 0.25). */
    private static final float LABEL_SIDE_OFFSET_BLOCKS = 0.45f;

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
        // Deux passes : tous les faisceaux, flush, puis toutes les étiquettes.
        // Dans un même batch le faisceau (translucide) serait dessiné après le
        // texte et le masquerait.
        List<Waypoint> shown = new ArrayList<>();
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

            shown.add(wp);
            drawBeam(pose, buffers, cam, mc.level, wp, partialTick);
        }
        buffers.endBatch();

        if (ClientConfig.SHOW_WAYPOINT_NAMES.get()) {
            for (Waypoint wp : shown) {
                double dx = wp.x() + 0.5 - cam.x;
                double dz = wp.z() + 0.5 - cam.z;
                drawLabel(pose, event.getCamera(), buffers, mc.font, cam, wp, Math.sqrt(dx * dx + dz * dz));
            }
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
     * Étiquette flottante (nom + distance) : 1 bloc au-dessus du point,
     * décalée SUR LE CÔTÉ du faisceau (en unités billboard, donc toujours
     * visuellement hors du faisceau) pour rester lisible. Deux passes comme
     * les nameplates vanilla : version assombrie visible à travers les blocs,
     * puis version pleine testée en profondeur.
     */
    private static void drawLabel(
            PoseStack pose, Camera camera, MultiBufferSource buffers, Font font, Vec3 cam, Waypoint wp, double dist) {
        String text = wp.name() + " (" + (int) dist + "m)";
        pose.pushPose();
        pose.translate(wp.x() + 0.5 - cam.x, wp.y() + 1.0 - cam.y, wp.z() + 0.5 - cam.z);
        pose.mulPose(camera.rotation());
        float scale = 0.025f * (float) Math.max(1.0, dist / 12.0);
        pose.scale(scale, -scale, scale);
        Matrix4f mat = pose.last().pose();
        // Bord gauche du texte à ~0.45 bloc du centre : hors du faisceau
        // (rayon 0.25). SEE_THROUGH pleine intensité : l'étiquette reste
        // lisible en toute circonstance, même un bloc devant.
        float xStart = LABEL_SIDE_OFFSET_BLOCKS / scale;
        font.drawInBatch(
                text,
                xStart,
                -4,
                0xFFFFFFFF,
                false,
                mat,
                buffers,
                Font.DisplayMode.SEE_THROUGH,
                0x80000000,
                LightTexture.FULL_BRIGHT);
        pose.popPose();
    }
}
