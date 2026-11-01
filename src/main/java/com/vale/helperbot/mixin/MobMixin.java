package com.vale.helperbot.mixin;

import com.vale.helperbot.bot.BotPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Creeper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Creepers never target the bot, so it can't lead them home or blow up terrain. */
@Mixin(Mob.class)
public abstract class MobMixin {
	@Inject(method = "setTarget", at = @At("HEAD"), cancellable = true)
	private void helperbot$ignoreBot(LivingEntity target, CallbackInfo ci) {
		if ((Object) this instanceof Creeper && target instanceof BotPlayer) {
			ci.cancel();
		}
	}
}
