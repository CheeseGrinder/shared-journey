package fr.cheesegrinder.sharedjourney.api.event;

import net.minecraft.world.level.block.Block;

import net.neoforged.bus.api.Event;
import net.neoforged.fml.event.IModBusEvent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Posted on the MOD bus at server startup so that other mods can force the
 * map color of their blocks, bypassing the automatic texture-derived
 * palette (useful when a texture's average color reads wrong on the map).
 *
 * <p>Position in the resolution chain (first hit wins): server config
 * {@code engine.blockColorOverrides} (the admin always has the last word)
 * → <b>this event's registrations</b> → bundled vanilla palette → runtime
 * texture extraction → {@code MapColor} fallback.
 *
 * <p>Colors are 0xRRGGBB (no alpha). Registrations apply to chunks rendered
 * from that point on: already-rendered regions are NOT repainted (an admin
 * can force one with {@code /sj regen}).
 */
public class BlockColorRegisterEvent extends Event implements IModBusEvent {

    private final Map<Block, Integer> colors = new LinkedHashMap<>();

    /** Forces the map color of a block (0xRRGGBB, alpha bits ignored). */
    public void register(Block block, int rgb) {
        colors.put(block, rgb & 0xFFFFFF);
    }

    public Map<Block, Integer> getColors() {
        return colors;
    }
}
