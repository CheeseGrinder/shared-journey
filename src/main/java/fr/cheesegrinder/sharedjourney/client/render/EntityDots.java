package fr.cheesegrinder.sharedjourney.client.render;

import fr.cheesegrinder.sharedjourney.client.config.ClientConfig;

import net.minecraft.client.gui.GuiGraphics;
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

/**
 * Points d'entités du radar (minimap + carte plein écran) : classification
 * par catégorie avec les filtres de la config client, points stylés à
 * contour, et flèche directionnelle du joueur (style JourneyMap).
 */
public final class EntityDots {

    public static final int COLOR_PLAYER = 0xFFFFFFFF;
    public static final int COLOR_PET = 0xFF3B82F6;
    public static final int COLOR_VILLAGER = 0xFF7CD97C;
    public static final int COLOR_HOSTILE = 0xFFFF4040;
    public static final int COLOR_PASSIVE = 0xFFC8C8C8;

    private EntityDots() {}

    /**
     * Couleur du point pour une entité, ou null si sa catégorie est masquée.
     * Ordre : joueur > animal apprivoisé (pet) > villageois > hostile > passif.
     */
    public static Integer colorFor(Entity e) {
        if (e instanceof Player) {
            return ClientConfig.RADAR_PLAYERS.get() ? COLOR_PLAYER : null;
        }
        if (e instanceof TamableAnimal tamed && tamed.isTame()) {
            return ClientConfig.RADAR_PETS.get() ? COLOR_PET : null;
        }
        if (e instanceof AbstractVillager) {
            return ClientConfig.RADAR_VILLAGERS.get() ? COLOR_VILLAGER : null;
        }
        if (e instanceof Enemy) {
            return ClientConfig.RADAR_HOSTILE.get() ? COLOR_HOSTILE : null;
        }
        if (e instanceof Animal) {
            return ClientConfig.RADAR_PASSIVE.get() ? COLOR_PASSIVE : null;
        }
        return null;
    }

    /** Point stylé : contour noir + remplissage couleur. */
    public static void draw(GuiGraphics gg, int x, int y, int argb) {
        gg.fill(x - 2, y - 2, x + 3, y + 3, 0xFF000000);
        gg.fill(x - 1, y - 1, x + 2, y + 2, argb);
    }

    /** Losange de waypoint (style JourneyMap) : carré tourné à 45°, contour sombre. */
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
     * Flèche du joueur : triangle blanc bordé de bleu pointant dans la
     * direction donnée (0 = vers le haut de l'écran). Dessin immédiat : le
     * batch GUI en attente est vidé avant.
     */
    public static void drawPlayerArrow(GuiGraphics gg, float cx, float cy, float angleDeg, float scale) {
        gg.flush();
        gg.pose().pushPose();
        gg.pose().translate(cx, cy, 0);
        gg.pose().mulPose(Axis.ZP.rotationDegrees(angleDeg));
        gg.pose().scale(scale, scale, 1f);
        // Contour bleu, corps blanc, puis encoche arrière bleue (dos concave)
        // pour rendre la direction lisible d'un coup d'oeil.
        triangle(gg, 0xFF2653C1, 0f, -7f, 5.5f, 6f, -5.5f, 6f);
        triangle(gg, 0xFFFFFFFF, 0f, -4.5f, 3.6f, 4.6f, -3.6f, 4.6f);
        triangle(gg, 0xFF2653C1, 0f, 1.5f, 2.8f, 6f, -2.8f, 6f);
        gg.pose().popPose();
    }

    /** Triangle plein en mode immédiat, dans la pose courante. */
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
