package fr.cheesegrinder.sharedjourney.client.render;

import fr.cheesegrinder.sharedjourney.client.config.RadarClientConfig;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import org.joml.Matrix4f;

import java.util.UUID;

/**
 * Radar entity dots (minimap + fullscreen map): classification by
 * category using the client config filters, outlined stylized dots, and
 * the player's directional arrow (JourneyMap style).
 */
public final class EntityDots {

    public static final int COLOR_PLAYER = 0xFFFFFFFF;
    public static final int COLOR_PET = 0xFF3B82F6;
    public static final int COLOR_VILLAGER = 0xFF7CD97C;
    public static final int COLOR_HOSTILE = 0xFFFF4040;
    public static final int COLOR_PASSIVE = 0xFFC8C8C8;

    private EntityDots() {}

    /**
     * Dot color for an entity, or null if its category is hidden.
     * Order: player > tamed animal (pet) > villager > hostile > passive.
     */
    public static Integer colorFor(Entity e) {
        if (e instanceof Player) {
            // Players are rendered as a "skin head" via the positions
            // broadcast by the server (drawPlayerHead), not as a radar dot.
            return null;
        }
        if (e instanceof TamableAnimal tamed && tamed.isTame()) {
            return RadarClientConfig.RADAR_PETS.get() ? COLOR_PET : null;
        }
        if (e instanceof AbstractVillager) {
            return RadarClientConfig.RADAR_VILLAGERS.get() ? COLOR_VILLAGER : null;
        }
        if (e instanceof Enemy) {
            return RadarClientConfig.RADAR_HOSTILE.get() ? COLOR_HOSTILE : null;
        }
        if (e instanceof Animal) {
            return RadarClientConfig.RADAR_PASSIVE.get() ? COLOR_PASSIVE : null;
        }
        return null;
    }

    /** Stylized dot: black outline + color fill. */
    public static void draw(GuiGraphics gg, int x, int y, int argb) {
        gg.fill(x - 2, y - 2, x + 3, y + 3, 0xFF000000);
        gg.fill(x - 1, y - 1, x + 2, y + 2, argb);
    }

    /** A player's head (skin face), centered, with a white border. */
    public static void drawPlayerHead(GuiGraphics gg, int x, int y, UUID playerId, int size) {
        var connection = Minecraft.getInstance().getConnection();
        PlayerInfo info = connection == null ? null : connection.getPlayerInfo(playerId);
        gg.fill(x - size / 2 - 1, y - size / 2 - 1, x + size / 2 + 1, y + size / 2 + 1, 0xFFFFFFFF);
        if (info != null) {
            PlayerFaceRenderer.draw(gg, info.getSkin(), x - size / 2, y - size / 2, size);
        } else {
            gg.fill(x - size / 2, y - size / 2, x + size / 2, y + size / 2, 0xFF808080);
        }
    }

    /** Waypoint diamond (JourneyMap style): square rotated 45°, dark outline. */
    public static void drawWaypointDiamond(GuiGraphics gg, float x, float y, int rgb, float scale) {
        gg.pose().pushPose();
        gg.pose().translate(x, y, 0);
        gg.pose().mulPose(Axis.ZP.rotationDegrees(45f));
        gg.pose().scale(scale, scale, 1f);
        gg.fill(-3, -3, 3, 3, 0xFF000000);
        gg.fill(-2, -2, 2, 2, 0xFF000000 | rgb);
        gg.pose().popPose();
    }

    /**
     * Player arrow: white triangle with a blue border pointing in the
     * given direction (0 = up the screen). Immediate drawing: the pending
     * GUI batch is flushed first.
     */
    public static void drawPlayerArrow(GuiGraphics gg, float cx, float cy, float angleDeg, float scale) {
        gg.flush();
        // Always-visible HUD marker: no depth test or culling, and a
        // neutral shader color (bridged plugin overlays can leave a
        // different GL state behind).
        RenderSystem.disableDepthTest();
        RenderSystem.disableCull();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        gg.pose().pushPose();
        gg.pose().translate(cx, cy, 0);
        gg.pose().mulPose(Axis.ZP.rotationDegrees(angleDeg));
        gg.pose().scale(scale, scale, 1f);
        // Blue outline, white body, then a blue rear notch (concave back)
        // so the direction reads at a glance. Vertices in screen clockwise
        // order (tip, bottom-left, bottom-right): same winding as vanilla
        // GUI quads, otherwise back-face culling would eliminate the arrow.
        triangle(gg, 0xFF2653C1, 0f, -7f, -5.5f, 6f, 5.5f, 6f);
        triangle(gg, 0xFFFFFFFF, 0f, -4.5f, -3.6f, 4.6f, 3.6f, 4.6f);
        triangle(gg, 0xFF2653C1, 0f, 1.5f, -2.8f, 6f, 2.8f, 6f);
        gg.pose().popPose();
        RenderSystem.enableCull();
        RenderSystem.enableDepthTest();
    }

    /** Solid triangle in immediate mode, within the current pose. */
    private static void triangle(GuiGraphics gg, int argb, float x1, float y1, float x2, float y2, float x3, float y3) {
        Matrix4f mat = gg.pose().last().pose();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder buf =
                Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        buf.addVertex(mat, x1, y1, 0).setColor(argb);
        buf.addVertex(mat, x2, y2, 0).setColor(argb);
        buf.addVertex(mat, x3, y3, 0).setColor(argb);
        BufferUploader.drawWithShader(buf.buildOrThrow());
    }
}
