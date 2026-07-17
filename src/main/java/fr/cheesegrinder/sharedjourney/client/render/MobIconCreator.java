package fr.cheesegrinder.sharedjourney.client.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.logging.LogUtils;
import com.mojang.math.Axis;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;

/**
 * Renders one mob head icon into a {@link MobIconAtlas} cell, off-screen
 * (Xaero-style render-to-texture, done ONCE per icon):
 *
 * <ol>
 *   <li>The head geometry is drawn face-on into a private framebuffer
 *       under an orthographic projection spanning one atlas cell, at 8×
 *       the cell resolution (supersampling), with real depth testing and
 *       both shader lights aimed at the camera (full texture colors).</li>
 *   <li>Mipmaps are generated on that framebuffer's texture: level 3 is
 *       an exact box-filtered downscale to the cell resolution.</li>
 *   <li>The icon is composed directly into the atlas cell (viewport on
 *       the cell): 8 copies offset by {@link #OUTLINE_PX} tinted black
 *       (silhouette-dilation outline), then the icon itself on top.</li>
 * </ol>
 *
 * <p>All touched GL state (projection + vertex sorting, modelview,
 * framebuffer, viewport, scissor, blend, depth, lights, shader color) is
 * restored before returning, and the pending GUI batch is flushed before
 * any framebuffer switch — both are classic sources of corrupted HUD
 * rendering when missed.
 */
final class MobIconCreator {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Form framebuffer resolution: 8× supersampling of the cell. */
    private static final int FORM_RES = MobIconAtlas.CELL * 8;
    /** Mip level of the form texture matching the cell resolution. */
    private static final int FORM_MIP_LEVELS = 3;

    private static final int FAR_PLANE = 500;
    /** GL_TEXTURE_MAX_LEVEL / GL_TEXTURE_MAX_LOD raw ids. */
    private static final int GL_TEXTURE_MAX_LEVEL = 33085;

    private static final int GL_TEXTURE_MAX_LOD = 33083;

    /** Packed full-bright light, same as inventory entity rendering. */
    private static final int FULL_BRIGHT = 15728880;
    /** Share of the cell filled by the framed cubes (outline margin). */
    private static final float FIT = 0.72f;
    /** Outline thickness in cell pixels (~0.6 screen px at icon size). */
    private static final int OUTLINE_PX = 1;

    /**
     * Both shader lights aimed straight at the face-on geometry: full
     * texture colors. The Lighting presets assume vanilla's inventory
     * entity orientation and leave our front faces in ambient shadow.
     */
    private static final Vector3f LIGHT_TOWARD_VIEWER = new Vector3f(0f, 0f, 1f);

    private final MobIconAtlas atlas = new MobIconAtlas();
    private final MultiBufferSource.BufferSource bufferSource =
            MultiBufferSource.immediate(new ByteBufferBuilder(1536));
    private TextureTarget formTarget;
    private int atlasFramebuffer;

    /**
     * Caller's scissor box, captured when its scissor test is on:
     * {@link #clearCell} repoints the GL scissor box at the atlas cell,
     * and re-enabling the test on restore without putting the box back
     * clipped the rest of the frame's minimap draws (radar icons, bridged
     * rail overlays) into a stray 32x32 window corner — a one-frame
     * overlay flicker on every non-fresh-page icon creation.
     */
    private final int[] scissorBoxBackup = new int[4];

    /**
     * Renders the icon described by {@code geometry} (whose model must
     * already be in its neutral pose) into a fresh atlas cell. Returns
     * null when anything fails; GL state is restored either way.
     */
    MobIconAtlas.Slot create(GuiGraphics gg, Entity entity, ResourceLocation texture, MobHeadIcons.Geometry geometry) {
        Minecraft mc = Minecraft.getInstance();
        boolean scissorWasOn = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        if (scissorWasOn) {
            GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, scissorBoxBackup);
        }

        Matrix4f projectionBackup = RenderSystem.getProjectionMatrix();
        VertexSorting sortingBackup = RenderSystem.getVertexSorting();

        // The pending GUI batch must land on screen before the target
        // framebuffer changes, and the caller's scissor (minimap edge
        // clip) must not crop the off-screen passes.
        gg.flush();
        if (scissorWasOn) {
            GlStateManager._disableScissorTest();
        }

        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        modelView.identity();
        RenderSystem.applyModelViewMatrix();
        Matrix4f ortho = new Matrix4f().setOrtho(0f, MobIconAtlas.CELL, MobIconAtlas.CELL, 0f, -1f, FAR_PLANE);
        RenderSystem.setProjectionMatrix(ortho, VertexSorting.ORTHOGRAPHIC_Z);

        MobIconAtlas.Slot slot = null;
        try {
            ensureResources();
            slot = atlas.allocate();
            if (slot != null) {
                renderForm(texture, geometry);
                composeIntoCell(slot);
            }
        } catch (Exception ex) {
            LOGGER.debug("Mob head icon creation failed for {}", entity.getType(), ex);
            slot = null;
        } finally {
            restoreState(mc, scissorWasOn, projectionBackup, sortingBackup);
        }

        return slot;
    }

    /** Deletes the atlas pages; existing slots become invalid. */
    void reset() {
        atlas.clear();
    }

    /** Lazy GL resources, created on the first icon. */
    private void ensureResources() {
        if (formTarget == null) {
            formTarget = new TextureTarget(FORM_RES, FORM_RES, true, Minecraft.ON_OSX);
            GlStateManager._bindTexture(formTarget.getColorTextureId());
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL_TEXTURE_MAX_LEVEL, FORM_MIP_LEVELS);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL_TEXTURE_MAX_LOD, FORM_MIP_LEVELS);
            GlStateManager._texParameter(
                    GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST_MIPMAP_NEAREST);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GlStateManager._bindTexture(0);
        }

        if (atlasFramebuffer == 0) {
            atlasFramebuffer = GlStateManager.glGenFramebuffers();
        }
    }

    /**
     * Draws the head geometry face-on into the form framebuffer, then
     * generates its mipmaps (level {@link #FORM_MIP_LEVELS} = the
     * box-filtered cell-resolution version used by the compose pass).
     */
    private void renderForm(ResourceLocation texture, MobHeadIcons.Geometry geometry) {
        formTarget.bindWrite(true);
        GlStateManager._clearColor(0f, 0f, 0f, 0f);
        GlStateManager._clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
        RenderSystem.setShaderLights(LIGHT_TOWARD_VIEWER, LIGHT_TOWARD_VIEWER);

        // Face-on view: model -Z (the mob's front) toward the viewer,
        // model +Y (down) staying down. Only Z flips: visible faces are
        // the ones wound counter-clockwise on screen, which needs a
        // NEGATIVE pose determinant — same as vanilla's
        // renderEntityInInventory. Profile views rotate the model before
        // everything else (the rotation is rightmost, so it applies
        // first). Real depth testing sorts the head layers (hat...).
        PoseStack pose = new PoseStack();
        pose.translate(MobIconAtlas.CELL / 2f, MobIconAtlas.CELL / 2f, -FAR_PLANE / 2f);
        float k = MobIconAtlas.CELL * FIT / geometry.span();
        pose.scale(k, k, -k);
        pose.translate(-geometry.cx(), -geometry.cy(), -geometry.cz());
        if (geometry.sideView()) {
            pose.mulPose(Axis.YP.rotationDegrees(90f));
        }

        VertexConsumer vc = bufferSource.getBuffer(RenderType.entityCutout(texture));
        if (geometry.parts().isEmpty()) {
            geometry.model().renderToBuffer(pose, vc, FULL_BRIGHT, OverlayTexture.NO_OVERLAY, -1);
        } else {
            for (ModelPart part : geometry.parts()) {
                part.render(pose, vc, FULL_BRIGHT, OverlayTexture.NO_OVERLAY, -1);
            }
        }

        bufferSource.endBatch();
        formTarget.unbindWrite();
        GlStateManager._bindTexture(formTarget.getColorTextureId());
        GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
        GlStateManager._bindTexture(0);
    }

    /**
     * Outline + icon composition, drawn straight into the atlas cell
     * (the cell is the viewport; the form texture is sampled at its
     * cell-resolution mip level).
     */
    private void composeIntoCell(MobIconAtlas.Slot slot) {
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, atlasFramebuffer);
        GlStateManager._glFramebufferTexture2D(
                GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, slot.textureId(), 0);
        clearCell(slot);

        GlStateManager._viewport(slot.x(), slot.y(), MobIconAtlas.CELL, MobIconAtlas.CELL);
        GlStateManager._disableDepthTest();
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, formTarget.getColorTextureId());
        RenderSystem.enableBlend();

        // 1 px (screen) black outline by silhouette dilation: the icon is
        // stamped 8 times with offsets, tinted black through the shader
        // color; alpha accumulates (ONE, ONE) so overlaps stay opaque.
        RenderSystem.blendFuncSeparate(
                GlStateManager.SourceFactor.SRC_ALPHA,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE,
                GlStateManager.DestFactor.ONE);
        RenderSystem.setShaderColor(0f, 0f, 0f, 1f);
        for (int ox = -1; ox <= 1; ox++) {
            for (int oy = -1; oy <= 1; oy++) {
                if (ox != 0 || oy != 0) {
                    drawFormQuad(ox * OUTLINE_PX, oy * OUTLINE_PX);
                }
            }
        }

        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.blendFuncSeparate(
                GlStateManager.SourceFactor.SRC_ALPHA,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        drawFormQuad(0, 0);
    }

    /**
     * Clears the cell to transparent. A fresh page is cleared whole:
     * its texels are undefined at creation, and bilinear sampling on a
     * cell border would otherwise pull garbage from the neighbor cell.
     */
    private void clearCell(MobIconAtlas.Slot slot) {
        GlStateManager._clearColor(0f, 0f, 0f, 0f);
        if (slot.fresh()) {
            GlStateManager._clear(GL11.GL_COLOR_BUFFER_BIT, Minecraft.ON_OSX);
        } else {
            GlStateManager._enableScissorTest();
            GlStateManager._scissorBox(slot.x(), slot.y(), MobIconAtlas.CELL, MobIconAtlas.CELL);
            GlStateManager._clear(GL11.GL_COLOR_BUFFER_BIT, Minecraft.ON_OSX);
            GlStateManager._disableScissorTest();
        }
    }

    /**
     * One full-cell textured quad sampling the whole form texture, at
     * the given offset in cell pixels. The form render put the icon's
     * top at v = 1 (GL framebuffers are bottom-up), hence the flip.
     */
    private void drawFormQuad(int dx, int dy) {
        int cell = MobIconAtlas.CELL;
        Matrix4f identity = new Matrix4f();
        BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        buf.addVertex(identity, dx, dy, 0f).setUv(0f, 1f);
        buf.addVertex(identity, dx, dy + cell, 0f).setUv(0f, 0f);
        buf.addVertex(identity, dx + cell, dy + cell, 0f).setUv(1f, 0f);
        buf.addVertex(identity, dx + cell, dy, 0f).setUv(1f, 1f);
        BufferUploader.drawWithShader(buf.buildOrThrow());
    }

    /** Restores every piece of GL state touched by {@link #create}. */
    private void restoreState(
            Minecraft mc, boolean scissorWasOn, Matrix4f projectionBackup, VertexSorting sortingBackup) {
        RenderSystem.getModelViewStack().popMatrix();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.setProjectionMatrix(projectionBackup, sortingBackup);
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        mc.getMainRenderTarget().bindWrite(true);
        if (scissorWasOn) {
            // Box BEFORE test: clearCell left the box on the atlas cell.
            GlStateManager._scissorBox(
                    scissorBoxBackup[0], scissorBoxBackup[1], scissorBoxBackup[2], scissorBoxBackup[3]);
            GlStateManager._enableScissorTest();
        }

        RenderSystem.defaultBlendFunc();
        RenderSystem.enableBlend();
        RenderSystem.enableDepthTest();
        GlStateManager._depthFunc(GL11.GL_LEQUAL);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        Lighting.setupFor3DItems();
        GlStateManager._bindTexture(0);
    }
}
