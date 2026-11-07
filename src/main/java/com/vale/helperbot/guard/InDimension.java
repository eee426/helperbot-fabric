package com.vale.helperbot.guard;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** A copy of the world, not a Level itself, that can say which dimension it's of (see ProtectionZones). */
public interface InDimension {
	ResourceKey<Level> dimension();
}
