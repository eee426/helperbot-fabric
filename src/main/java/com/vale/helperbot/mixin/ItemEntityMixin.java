package com.vale.helperbot.mixin;

import com.vale.helperbot.bot.BotPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The bot never takes anything for itself: it only picks up what it wants (task items, a limited
 * stock of building blocks). What it does pick up is recorded, so tidying the bag later only ever
 * throws away things it collected itself, never things a player gave it.
 */
@Mixin(ItemEntity.class)
public abstract class ItemEntityMixin {
	@Unique
	private Item helperbot$item;
	@Unique
	private int helperbot$countBefore;

	@Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
	private void helperbot$onlyWanted(Player player, CallbackInfo ci) {
		if (!(player instanceof BotPlayer bot)) {
			return;
		}
		ItemEntity self = (ItemEntity) (Object) this;
		if (!bot.wantsPickup(self.getItem())) {
			ci.cancel();
			return;
		}
		helperbot$item = self.getItem().getItem();
		helperbot$countBefore = self.getItem().getCount();
	}

	@Inject(method = "playerTouch", at = @At("RETURN"))
	private void helperbot$recordPickup(Player player, CallbackInfo ci) {
		if (player instanceof BotPlayer bot && helperbot$item != null) {
			ItemEntity self = (ItemEntity) (Object) this;
			int after = self.isRemoved() ? 0 : self.getItem().getCount();
			if (after < helperbot$countBefore) {
				bot.notePickedUp(helperbot$item, helperbot$countBefore - after);
			}
			helperbot$item = null;
		}
	}
}
