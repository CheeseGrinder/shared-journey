package fr.cheesegrinder.sharedjourney.client.render;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.Arrays;

/**
 * Per-frame queue of mob icon quads, drawn with one draw call per atlas
 * page instead of one flush + immediate quad per entity.
 *
 * <p>Every queued icon bakes the pose matrix current at {@link #add} time
 * into its vertices, so {@link #flush} can run later under any pose (the
 * minimap's zoom/rotation counter-transforms are per icon). The queue
 * uses its own {@link ByteBufferBuilder} rather than the shared
 * {@code Tesselator}: icon creation (which uses the Tesselator) can
 * happen between two {@link #add} calls.
 *
 * <p>Render-thread only. Usage: {@link #begin}, any number of
 * {@link #add}, then {@link #flush}; the caller must flush while its
 * scissor and framebuffer are still the ones the icons belong to.
 */
final class MobIconBatch {

    /** Floats per queued icon: 4 vertices of (x, y, z, u, v). */
    private static final int FLOATS_PER_ICON = 20;
    /** Bytes per POSITION_TEX vertex, 4 vertices per icon. */
    private static final int BYTES_PER_ICON = 4 * 20;

    private static final ByteBufferBuilder BYTES = new ByteBufferBuilder(BYTES_PER_ICON * 64);
    private static final Vector3f SCRATCH = new Vector3f();

    private static float[] vertices = new float[FLOATS_PER_ICON * 64];
    private static int[] pages = new int[64];
    private static int count;
    private static boolean active;

    private MobIconBatch() {}

    /** Starts a batch, dropping anything left over from an aborted one. */
    static void begin() {
        count = 0;
        active = true;
    }

    static boolean isActive() {
        return active;
    }

    /**
     * Queues the icon of {@code slot} centered on (x, y) in the space of
     * {@code pose}, {@code size} units square for the whole cell (the
     * sampled inner area is smaller by the atlas inset).
     */
    static void add(MobIconAtlas.Slot slot, Matrix4f pose, float x, float y, float size) {
        ensureCapacity();
        float inner = (MobIconAtlas.CELL - 2f * MobIconAtlas.INSET) / MobIconAtlas.CELL;
        float half = size * inner / 2f;
        float page = slot.pageSize();
        float u0 = (slot.x() + MobIconAtlas.INSET) / page;
        float u1 = (slot.x() + MobIconAtlas.CELL - MobIconAtlas.INSET) / page;
        // The icon's top is stored at the HIGH v of its cell (GL
        // framebuffers are bottom-up), hence the flip.
        float vTop = (slot.y() + MobIconAtlas.CELL - MobIconAtlas.INSET) / page;
        float vBottom = (slot.y() + MobIconAtlas.INSET) / page;

        int base = count * FLOATS_PER_ICON;
        put(base, pose, x - half, y - half, u0, vTop);
        put(base + 5, pose, x - half, y + half, u0, vBottom);
        put(base + 10, pose, x + half, y + half, u1, vBottom);
        put(base + 15, pose, x + half, y - half, u1, vTop);
        pages[count] = slot.textureId();
        count++;
    }

    /** Draws everything queued (one draw per page) and ends the batch. */
    static void flush(GuiGraphics gg) {
        active = false;
        if (count == 0) {
            return;
        }

        // The pending GUI batch must land first to keep the stacking order.
        gg.flush();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        for (int i = 0; i < count; i++) {
            if (pages[i] != 0) {
                drawPage(pages[i]);
            }
        }

        count = 0;
    }

    /** Draws every queued icon of one atlas page, then marks them done. */
    private static void drawPage(int textureId) {
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, textureId);
        BufferBuilder buf = new BufferBuilder(BYTES, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        for (int i = 0; i < count; i++) {
            if (pages[i] == textureId) {
                emit(buf, i * FLOATS_PER_ICON);
                pages[i] = 0;
            }
        }

        BufferUploader.drawWithShader(buf.buildOrThrow());
    }

    private static void emit(BufferBuilder buf, int base) {
        for (int v = 0; v < 4; v++) {
            int o = base + v * 5;
            buf.addVertex(vertices[o], vertices[o + 1], vertices[o + 2]).setUv(vertices[o + 3], vertices[o + 4]);
        }
    }

    private static void put(int offset, Matrix4f pose, float x, float y, float u, float v) {
        SCRATCH.set(x, y, 0f);
        pose.transformPosition(SCRATCH);
        vertices[offset] = SCRATCH.x;
        vertices[offset + 1] = SCRATCH.y;
        vertices[offset + 2] = SCRATCH.z;
        vertices[offset + 3] = u;
        vertices[offset + 4] = v;
    }

    private static void ensureCapacity() {
        if (count < pages.length) {
            return;
        }

        int capacity = pages.length * 2;
        pages = Arrays.copyOf(pages, capacity);
        vertices = Arrays.copyOf(vertices, capacity * FLOATS_PER_ICON);
    }
}
