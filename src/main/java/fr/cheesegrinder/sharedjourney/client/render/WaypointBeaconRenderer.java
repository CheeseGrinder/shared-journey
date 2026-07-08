package fr.cheesegrinder.sharedjourney.client.render;

import fr.cheesegrinder.sharedjourney.api.SharedJourneyConstants;
import fr.cheesegrinder.sharedjourney.api.Waypoint;
import fr.cheesegrinder.sharedjourney.client.config.ClientConfig;
import fr.cheesegrinder.sharedjourney.client.service.WaypointStore;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.joml.Matrix4f;

import java.util.List;

/**
 * Beacons de waypoints dans le monde : faisceau vertical translucide de la
 * couleur du waypoint (deux quads croisés, toute la hauteur du monde) et
 * étiquette flottante (nom + distance) visible à travers les blocs.
 * Affichés entre les distances min et max de la config client.
 */
@EventBusSubscriber(modid = SharedJourneyConstants.MOD_ID, value = Dist.CLIENT)
public final class WaypointBeaconRenderer {

    /** Demi-largeur (blocs) des quads du faisceau. */
    private static final float BEAM_HALF_WIDTH = 0.3f;

    private static final int BEAM_ALPHA = 0x60;

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

            drawBeam(pose, cam, mc.level, wp);
            drawLabel(pose, event.getCamera(), buffers, mc.font, cam, wp, dist);
        }
        buffers.endBatch();
    }

    /** Faisceau vertical : deux quads croisés sur toute la hauteur du monde. */
    private static void drawBeam(PoseStack pose, Vec3 cam, Level level, Waypoint wp) {
        pose.pushPose();
        pose.translate(wp.x() + 0.5 - cam.x, 0, wp.z() + 0.5 - cam.z);
        Matrix4f mat = pose.last().pose();
        float y0 = (float) (level.getMinBuildHeight() - cam.y);
        float y1 = (float) (level.getMaxBuildHeight() - cam.y);
        int argb = (BEAM_ALPHA << 24) | wp.colorRgb();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder buf =
                Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        // Plan orienté X puis plan orienté Z.
        buf.addVertex(mat, -BEAM_HALF_WIDTH, y0, 0).setColor(argb);
        buf.addVertex(mat, -BEAM_HALF_WIDTH, y1, 0).setColor(argb);
        buf.addVertex(mat, BEAM_HALF_WIDTH, y1, 0).setColor(argb);
        buf.addVertex(mat, BEAM_HALF_WIDTH, y0, 0).setColor(argb);
        buf.addVertex(mat, 0, y0, -BEAM_HALF_WIDTH).setColor(argb);
        buf.addVertex(mat, 0, y1, -BEAM_HALF_WIDTH).setColor(argb);
        buf.addVertex(mat, 0, y1, BEAM_HALF_WIDTH).setColor(argb);
        buf.addVertex(mat, 0, y0, BEAM_HALF_WIDTH).setColor(argb);
        BufferUploader.drawWithShader(buf.buildOrThrow());
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        pose.popPose();
    }

    /**
     * Étiquette flottante (nom + distance) au-dessus du point, orientée face
     * caméra et grossie avec la distance pour rester lisible de loin.
     */
    private static void drawLabel(
            PoseStack pose, Camera camera, MultiBufferSource buffers, Font font, Vec3 cam, Waypoint wp, double dist) {
        String text = wp.name() + " (" + (int) dist + "m)";
        pose.pushPose();
        pose.translate(wp.x() + 0.5 - cam.x, wp.y() + 1.2 - cam.y, wp.z() + 0.5 - cam.z);
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
