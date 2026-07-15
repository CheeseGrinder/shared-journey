package fr.cheesegrinder.sharedjourney.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * GL texture atlases holding the prerendered mob head icons.
 *
 * <p>Each page is a square RGBA8 texture split into {@link #CELL}-pixel
 * cells, filled left-to-right then top-to-bottom. A new page is created
 * when the current one is full. Pages are sampled with bilinear
 * filtering and no mipmaps; every icon keeps a transparent border inside
 * its cell (enforced by the creator's framing margin), so full-cell UVs
 * never bleed into a neighbor.
 */
final class MobIconAtlas {

    /** Cell size in pixels — the stored resolution of one icon. */
    static final int CELL = 32;

    /** Preferred page size (clamped by GL_MAX_TEXTURE_SIZE). */
    private static final int PREFERRED_PAGE_SIZE = 1024;

    /** GL_TEXTURE_MAX_LEVEL, absent from the GL11/GL12 constant classes. */
    private static final int GL_TEXTURE_MAX_LEVEL = 33085;

    /**
     * One allocated icon cell. {@code fresh} marks the first cell of a
     * newly created page, whose texture content is still undefined and
     * must be cleared by the creator before any cell is sampled.
     */
    record Slot(int textureId, int x, int y, int pageSize, boolean fresh) {}

    private static final class Page {

        final int textureId;
        int nextIndex;

        Page(int textureId) {
            this.textureId = textureId;
        }
    }

    private final List<Page> pages = new ArrayList<>();
    private final int pageSize;
    private final int cellsPerSide;

    MobIconAtlas() {
        int maxTextureSize = GlStateManager._getInteger(GL11.GL_MAX_TEXTURE_SIZE);
        this.pageSize = Math.min(maxTextureSize, PREFERRED_PAGE_SIZE) / CELL * CELL;
        this.cellsPerSide = this.pageSize / CELL;
    }

    /**
     * Reserves the next free cell, creating a new page when the current
     * one is full. Returns null when the page texture cannot be created.
     */
    Slot allocate() {
        boolean fresh = false;
        Page page = pages.isEmpty() ? null : pages.get(pages.size() - 1);
        if (page == null || page.nextIndex >= cellsPerSide * cellsPerSide) {
            int textureId = createPageTexture();
            if (textureId == 0) {
                return null;
            }

            page = new Page(textureId);
            pages.add(page);
            fresh = true;
        }

        int index = page.nextIndex++;
        int x = index % cellsPerSide * CELL;
        int y = index / cellsPerSide * CELL;
        return new Slot(page.textureId, x, y, pageSize, fresh);
    }

    /** Deletes every page texture; existing Slots become invalid. */
    void clear() {
        for (Page page : pages) {
            GlStateManager._deleteTexture(page.textureId);
        }

        pages.clear();
    }

    private int createPageTexture() {
        int texture = GlStateManager._genTexture();
        if (texture == 0) {
            return 0;
        }

        GlStateManager._bindTexture(texture);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL_TEXTURE_MAX_LEVEL, 0);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GlStateManager._texImage2D(
                GL11.GL_TEXTURE_2D,
                0,
                GL11.GL_RGBA8,
                pageSize,
                pageSize,
                0,
                GL11.GL_RGBA,
                GL11.GL_UNSIGNED_BYTE,
                (IntBuffer) null);
        GlStateManager._bindTexture(0);
        return texture;
    }
}
