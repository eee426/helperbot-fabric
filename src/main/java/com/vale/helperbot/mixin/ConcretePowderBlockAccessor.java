package com.vale.helperbot.mixin;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ConcretePowderBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** What a concrete powder turns into in water (see Interactions). */
@Mixin(ConcretePowderBlock.class)
public interface ConcretePowderBlockAccessor {
	@Accessor("concrete")
	Block helperbot$concrete();
}
