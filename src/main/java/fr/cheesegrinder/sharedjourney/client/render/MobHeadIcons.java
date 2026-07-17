package fr.cheesegrinder.sharedjourney.client.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.model.AgeableListModel;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HeadedModel;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Flat mob "head" icons for the radar (minimap + fullscreen map),
 * rendered from the entity's own model and texture: any modded mob works
 * without integration (no hardcoded sprite set).
 *
 * <p>v3 (2026-07-15) moved to a render-to-texture pipeline: each icon is
 * rendered ONCE off-screen into a {@link MobIconAtlas} cell (supersampled,
 * outlined, depth-tested — see {@link MobIconCreator}), cached by
 * (entity type, texture), and every frame after that costs one textured
 * quad. The v2 approach re-rendered the geometry 9 times per entity per
 * frame (8 outline passes + 1), with a scissor and a batch flush per
 * icon. Icon creation is budgeted (at most one per
 * {@link #MIN_CREATION_INTERVAL_NANOS}): entities without an icon yet
 * fall back to their colored dot for a frame or two.
 *
 * <p>Geometry design notes (validated in-game on v2; the first attempt,
 * 2026-07-11, rendered the live entity via the inventory-screen path and
 * cropped a fixed share of the bounding box — unreadable):
 * <ul>
 *   <li>The model's head {@link ModelPart} is located via
 *       {@link HumanoidModel} (head + hat overlay),
 *       {@link HeadedModel}, {@link AgeableListModel#headParts()}
 *       (reflection, the method is protected),
 *       {@link HierarchicalModel#getAnyDescendantWithName} — retried on
 *       every part name containing "head" (wither: "center_head") —
 *       and finally a reflective scan of ModelPart fields named "head"
 *       (llama-style models that extend EntityModel directly).</li>
 *   <li>The icon frames the union of the LARGE cubes in the FRONT
 *       third of the part: vanilla "head" parts often carry the neck in
 *       their own cubes (horse, camel...), so framing on the whole part
 *       showed neck; framing on the front-most big boxes isolates the
 *       face. Everything still renders; what the frame excludes lands
 *       outside the atlas cell and is simply not stored.</li>
 *   <li>Mobs with no identifiable head, or a tiny one (parrot, fish),
 *       draw the whole model instead; shapes much deeper than wide
 *       (fish) are shown in profile rather than face-on.</li>
 *   <li>The shared model is frozen in a neutral pose ({@code setupAnim}
 *       with all-zero parameters) before the capture — no bobbing.</li>
 * </ul>
 *
 * <p>The (type, texture) cache key covers texture-driven variants
 * (horse coats, cat breeds, angry wolf...). Overlay layers (sheep wool,
 * villager profession, armor) are not rendered — same fidelity as v2.
 * When the entity renderers are rebuilt (resource reload), the first
 * model mismatch resets the whole cache and atlas.
 *
 * <p>When anything cannot be resolved or rendered, {@link #draw} returns
 * false and the caller keeps its colored dot.
 */
public final class MobHeadIcons {

    /** Icon square size, in screen pixels (radar call sites). */
    public static final float ICON_SIZE = 10f;

    /** Creation budget: at most one off-screen render per interval. */
    private static final long MIN_CREATION_INTERVAL_NANOS = 15_000_000L;

    /** Head span (block units) under which the whole model reads better. */
    private static final float TINY_HEAD = 0.26f;
    /** Depth share (from the front) whose cubes may drive the frame. */
    private static final float FRONT_SHARE = 0.35f;
    /** Cubes under this share of the biggest face don't widen the frame. */
    private static final float SIGNIFICANT_AREA = 0.25f;
    /** Width/depth ratio under which a mob is drawn in profile (fish). */
    private static final float SIDE_VIEW_RATIO = 0.45f;

    /**
     * What to render and how to frame it for one entity type.
     * {@code parts} null = resolution failed (dot fallback); empty =
     * draw the whole model. The model reference detects renderer swaps
     * (resource reload) and triggers a full reset.
     */
    record Geometry(
            EntityModel<?> model, List<ModelPart> parts, boolean sideView, float cx, float cy, float cz, float span) {}

    private record IconKey(EntityType<?> type, ResourceLocation texture) {}

    /** Per-type geometry; shared by every texture variant of the type. */
    private static final Map<EntityType<?>, Geometry> GEOMETRY = new HashMap<>();
    /**
     * Atlas slot per (type, texture); an empty value = FAILED (dot).
     * Optional values instead of nulls: draw() runs per radar entity per
     * frame, and distinguishing "failed" from "not tried yet" with nulls
     * required a second containsKey lookup on every call.
     */
    private static final Map<IconKey, Optional<MobIconAtlas.Slot>> ICONS = new HashMap<>();

    private static MobIconCreator creator;
    private static long lastCreationNanos;

    private MobHeadIcons() {}

    /**
     * Draws the head icon of {@code e} centered on (x, y) in the current
     * pose, {@code size} units square. Returns false when no icon can be
     * rendered for this entity type (or none exists yet this frame): the
     * caller draws its dot instead.
     */
    public static boolean draw(GuiGraphics gg, Entity e, float x, float y, float size) {
        Minecraft mc = Minecraft.getInstance();
        var renderer = mc.getEntityRenderDispatcher().getRenderer(e);
        if (!(renderer instanceof LivingEntityRenderer<?, ?> living) || !(e instanceof LivingEntity)) {
            return false;
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        ResourceLocation texture = ((LivingEntityRenderer) living).getTextureLocation((LivingEntity) e);
        if (texture == null) {
            return false;
        }

        EntityModel<?> model = living.getModel();
        Geometry geometry = GEOMETRY.get(e.getType());
        if (geometry != null && geometry.model() != model) {
            // Renderers were rebuilt (resource reload): every cached icon
            // was captured from stale models and textures.
            resetAll();
            geometry = null;
        }

        IconKey key = new IconKey(e.getType(), texture);
        Optional<MobIconAtlas.Slot> slot = ICONS.get(key);
        if (slot == null) {
            long now = System.nanoTime();
            if (now - lastCreationNanos < MIN_CREATION_INTERVAL_NANOS) {
                // Budget spent: dot this frame, icon on a later one.
                return false;
            }

            if (geometry == null) {
                geometry = build(e, model);
                GEOMETRY.put(e.getType(), geometry);
            }

            slot = Optional.ofNullable(createIcon(gg, e, texture, geometry));
            ICONS.put(key, slot);
        }
        if (slot.isEmpty()) {
            return false;
        }

        drawIcon(gg, slot.get(), x, y, size);
        return true;
    }

    // ------------------------------------------------------------------
    // Icon creation & drawing

    /** Off-screen render into a fresh atlas cell (null = failed). */
    private static MobIconAtlas.Slot createIcon(GuiGraphics gg, Entity e, ResourceLocation texture, Geometry geometry) {
        if (geometry.parts() == null) {
            return null;
        }

        lastCreationNanos = System.nanoTime();
        if (creator == null) {
            creator = new MobIconCreator();
        }

        neutralPose(geometry.model(), e);
        return creator.create(gg, e, texture, geometry);
    }

    /** One textured quad sampling the icon's atlas cell. */
    private static void drawIcon(GuiGraphics gg, MobIconAtlas.Slot slot, float x, float y, float size) {
        // Immediate draw (an atlas page has no ResourceLocation for the
        // batched GUI render types): the pending batch keeps its order.
        gg.flush();
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, slot.textureId());
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);

        float half = size / 2f;
        float u0 = slot.x() / (float) slot.pageSize();
        float u1 = (slot.x() + MobIconAtlas.CELL) / (float) slot.pageSize();
        // The icon's top is stored at the HIGH v of its cell (GL
        // framebuffers are bottom-up), hence the flip.
        float vTop = (slot.y() + MobIconAtlas.CELL) / (float) slot.pageSize();
        float vBottom = slot.y() / (float) slot.pageSize();
        Matrix4f mat = gg.pose().last().pose();
        BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        buf.addVertex(mat, x - half, y - half, 0f).setUv(u0, vTop);
        buf.addVertex(mat, x - half, y + half, 0f).setUv(u0, vBottom);
        buf.addVertex(mat, x + half, y + half, 0f).setUv(u1, vBottom);
        buf.addVertex(mat, x + half, y - half, 0f).setUv(u1, vTop);
        BufferUploader.drawWithShader(buf.buildOrThrow());
    }

    /** Drops every cached icon, geometry and atlas page. */
    private static void resetAll() {
        GEOMETRY.clear();
        ICONS.clear();
        if (creator != null) {
            creator.reset();
        }
    }

    // ------------------------------------------------------------------
    // Geometry resolution

    /** Resolves what to draw and how to frame it for one entity type. */
    private static Geometry build(Entity e, EntityModel<?> model) {
        try {
            neutralPose(model, e);
            List<ModelPart> headParts = resolveHeadParts(model);
            if (headParts != null) {
                float[] frame = frameBounds(collectCubes(headParts, false));
                if (frame != null && spanOf(frame) >= TINY_HEAD) {
                    return geometry(model, headParts, false, frame);
                }
            }

            // No usable head: whole model, in profile when the shape is
            // much deeper than wide (a face-on fish is a sliver).
            List<ModelPart> all = wholeModelParts(model);
            float[] overall = unionBounds(collectCubes(all, false));
            boolean side = false;
            if (overall != null) {
                side = (overall[3] - overall[0]) < SIDE_VIEW_RATIO * (overall[5] - overall[2]);
            }
            float[] frame = frameBounds(collectCubes(all, side));
            if (frame == null) {
                // Unknown model layout: estimate from the collision box.
                // Model space puts y = 0 at 1.5 blocks above the feet.
                float w = e.getBbWidth() / 2f;
                float h = e.getBbHeight();
                frame = new float[] {-w, 1.5f - h, -w, w, 1.5f, w};
            }
            if (spanOf(frame) < 0.01f) {
                return new Geometry(model, null, false, 0f, 0f, 0f, 0f);
            }
            return geometry(model, List.of(), side, frame);
        } catch (Exception ex) {
            return new Geometry(model, null, false, 0f, 0f, 0f, 0f);
        }
    }

    private static Geometry geometry(EntityModel<?> model, List<ModelPart> parts, boolean side, float[] f) {
        return new Geometry(model, parts, side, (f[0] + f[3]) / 2f, (f[1] + f[4]) / 2f, (f[2] + f[5]) / 2f, spanOf(f));
    }

    private static float spanOf(float[] f) {
        return Math.max(f[3] - f[0], f[4] - f[1]);
    }

    /**
     * The model's head part(s), or null when no head can be identified
     * (the caller then draws the whole model). Strategies are tried in
     * order and individually fail-soft.
     */
    private static List<ModelPart> resolveHeadParts(EntityModel<?> model) {
        if (model instanceof HumanoidModel<?> humanoid) {
            // Head plus the hat overlay (outer skin layer of zombies,
            // skeletons...) — HeadedModel alone would drop the hat.
            return List.of(humanoid.head, humanoid.hat);
        }
        if (model instanceof HeadedModel headed) {
            return List.of(headed.getHead());
        }
        if (model instanceof AgeableListModel<?> ageable) {
            try {
                List<ModelPart> parts = new ArrayList<>();
                reflectParts(ageable, "headParts").forEach(parts::add);
                if (!parts.isEmpty()) {
                    return parts;
                }
            } catch (Exception ignored) {
                // Next strategy.
            }
        }
        if (model instanceof HierarchicalModel<?> hierarchical) {
            List<ModelPart> parts = hierarchicalHead(hierarchical);
            if (parts != null) {
                return parts;
            }
        }
        return fieldScanHead(model);
    }

    /**
     * "head" descendant of a hierarchical model, retrying every part
     * name that contains "head" (wither: "center_head") — lexicographic
     * order, so multi-headed models resolve deterministically.
     */
    private static List<ModelPart> hierarchicalHead(HierarchicalModel<?> model) {
        var direct = model.getAnyDescendantWithName("head");
        if (direct.isPresent()) {
            return List.of(direct.get());
        }
        TreeSet<String> names = new TreeSet<>();
        model.root().visit(new PoseStack(), (pose, path, index, cube) -> {
            for (String segment : path.split("/")) {
                if (!segment.isEmpty()) {
                    names.add(segment);
                }
            }
        });
        for (String name : names) {
            if (!name.toLowerCase(Locale.ROOT).contains("head")) {
                continue;
            }
            var part = model.getAnyDescendantWithName(name);
            if (part.isPresent()) {
                return List.of(part.get());
            }
        }
        return null;
    }

    /**
     * Last resort for models outside the three vanilla hierarchies
     * (llama...): first ModelPart field whose name contains "head",
     * exact "head" preferred.
     */
    private static List<ModelPart> fieldScanHead(EntityModel<?> model) {
        ModelPart contains = null;
        for (Class<?> c = model.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType() != ModelPart.class
                        || !f.getName().toLowerCase(Locale.ROOT).contains("head")) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    ModelPart part = (ModelPart) f.get(model);
                    if (part == null) {
                        continue;
                    }
                    if (f.getName().equalsIgnoreCase("head")) {
                        return List.of(part);
                    }
                    if (contains == null) {
                        contains = part;
                    }
                } catch (Exception ignored) {
                    // Inaccessible field: keep scanning.
                }
            }
        }
        return contains == null ? null : List.of(contains);
    }

    /** headParts()/bodyParts() are protected on AgeableListModel. */
    @SuppressWarnings("unchecked")
    private static Iterable<ModelPart> reflectParts(AgeableListModel<?> model, String name) throws Exception {
        Method m = AgeableListModel.class.getDeclaredMethod(name);
        m.setAccessible(true);
        return (Iterable<ModelPart>) m.invoke(model);
    }

    /** Parts approximating the whole model, for bounds and framing. */
    private static List<ModelPart> wholeModelParts(EntityModel<?> model) {
        if (model instanceof HierarchicalModel<?> hierarchical) {
            return List.of(hierarchical.root());
        }
        if (model instanceof AgeableListModel<?> ageable) {
            try {
                List<ModelPart> parts = new ArrayList<>();
                reflectParts(ageable, "headParts").forEach(parts::add);
                reflectParts(ageable, "bodyParts").forEach(parts::add);
                return parts;
            } catch (Exception ignored) {
                // Fall through to the field scan.
            }
        }
        // Any other model: every ModelPart field (nested duplicates are
        // harmless for a bounds union).
        List<ModelPart> parts = new ArrayList<>();
        for (Class<?> c = model.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType() != ModelPart.class) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    ModelPart part = (ModelPart) f.get(model);
                    if (part != null) {
                        parts.add(part);
                    }
                } catch (Exception ignored) {
                    // Keep scanning.
                }
            }
        }
        return parts;
    }

    // ------------------------------------------------------------------
    // Bounds

    /**
     * All cube bounds of the given part trees, in block units, in the
     * same space the creator draws them (pivots and rotations applied;
     * {@code side} pre-rotates 90° for profile views).
     */
    private static List<float[]> collectCubes(List<ModelPart> parts, boolean side) {
        List<float[]> out = new ArrayList<>();
        PoseStack ps = new PoseStack();
        if (side) {
            ps.mulPose(Axis.YP.rotationDegrees(90f));
        }
        Vector3f v = new Vector3f();
        for (ModelPart part : parts) {
            part.visit(ps, (pose, path, index, cube) -> {
                float[] b = {
                    Float.MAX_VALUE,
                    Float.MAX_VALUE,
                    Float.MAX_VALUE,
                    -Float.MAX_VALUE,
                    -Float.MAX_VALUE,
                    -Float.MAX_VALUE
                };
                for (int corner = 0; corner < 8; corner++) {
                    v.set(
                            ((corner & 1) == 0 ? cube.minX : cube.maxX) / 16f,
                            ((corner & 2) == 0 ? cube.minY : cube.maxY) / 16f,
                            ((corner & 4) == 0 ? cube.minZ : cube.maxZ) / 16f);
                    pose.pose().transformPosition(v);
                    b[0] = Math.min(b[0], v.x);
                    b[1] = Math.min(b[1], v.y);
                    b[2] = Math.min(b[2], v.z);
                    b[3] = Math.max(b[3], v.x);
                    b[4] = Math.max(b[4], v.y);
                    b[5] = Math.max(b[5], v.z);
                }
                out.add(b);
            });
        }
        return out;
    }

    /**
     * Icon frame: union of the significant cubes of the front third.
     * Vanilla "head" parts often hold the neck in their own cubes
     * (horse, camel) — the face boxes sit at the front. Small boxes
     * (nose, horns, antennae) and flat ones (wings) don't widen the
     * frame; whatever overflows lands outside the atlas cell.
     */
    private static float[] frameBounds(List<float[]> cubes) {
        if (cubes.isEmpty()) {
            return null;
        }
        float minZ = Float.MAX_VALUE;
        float maxZ = -Float.MAX_VALUE;
        for (float[] c : cubes) {
            minZ = Math.min(minZ, c[2]);
            maxZ = Math.max(maxZ, c[5]);
        }
        float frontLimit = minZ + (maxZ - minZ) * FRONT_SHARE;
        float bestArea = 0f;
        for (float[] c : cubes) {
            if (c[2] <= frontLimit) {
                bestArea = Math.max(bestArea, faceArea(c));
            }
        }
        if (bestArea <= 0f) {
            // Only flat geometry up front: frame everything.
            return unionBounds(cubes);
        }
        float[] out = null;
        for (float[] c : cubes) {
            if (c[2] > frontLimit || faceArea(c) < bestArea * SIGNIFICANT_AREA) {
                continue;
            }
            out = out == null ? c.clone() : merge(out, c);
        }
        return out;
    }

    private static float faceArea(float[] c) {
        return (c[3] - c[0]) * (c[4] - c[1]);
    }

    private static float[] unionBounds(List<float[]> cubes) {
        float[] out = null;
        for (float[] c : cubes) {
            out = out == null ? c.clone() : merge(out, c);
        }
        return out;
    }

    private static float[] merge(float[] a, float[] b) {
        for (int i = 0; i < 3; i++) {
            a[i] = Math.min(a[i], b[i]);
            a[i + 3] = Math.max(a[i + 3], b[i + 3]);
        }
        return a;
    }

    // ------------------------------------------------------------------
    // Pose

    /**
     * Freezes the shared model in its animation-free reference pose.
     * The instance is shared with world rendering, so this runs right
     * before the off-screen capture.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void neutralPose(EntityModel model, Entity e) {
        model.attackTime = 0f;
        model.riding = false;
        model.young = false;
        model.prepareMobModel(e, 0f, 0f, 1f);
        model.setupAnim(e, 0f, 0f, 0f, 0f, 0f);
    }
}
