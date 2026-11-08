package com.vale.helperbot.mixin;

import net.minecraft.world.item.AxeItem;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/** The axe's table of logs it strips (see Interactions). */
@Mixin(AxeItem.class)
public interface AxeItemAccessor {
	@Accessor("STRIPPABLES")
	static Map<Block, Block> helperbot$strippables() {
		throw new AssertionError();
	}
}
