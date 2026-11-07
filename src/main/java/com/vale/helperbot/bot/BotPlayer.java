package com.vale.helperbot.bot;

import com.mojang.authlib.GameProfile;
import com.vale.helperbot.guard.Rules;
import com.vale.helperbot.path.Air;
import com.vale.helperbot.task.BotTask;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * The bot itself: a server-side player with no client. Its position is driven directly by
 * {@link MovementController}, which keeps every move within what a real player could do.
 */
public class BotPlayer extends ServerPlayer {
	/** Foods with nasty side effects the bot won't eat. */
	private static final Set<Item> BAD_FOOD = Set.of(Items.ROTTEN_FLESH, Items.SPIDER_EYE, Items.POISONOUS_POTATO,
			Items.PUFFERFISH, Items.CHORUS_FRUIT, Items.SUSPICIOUS_STEW, Items.CHICKEN);

	private final MovementController movement = new MovementController(this);
	private final Loot loot = new Loot(this);
	private BotTask task;
	/** On by default during the beta so the plan is always visible. */
	private boolean debugPath = true;

	public BotPlayer(MinecraftServer server, ServerLevel level, GameProfile profile) {
		super(server, level, profile, ClientInformation.createDefault());
	}

	/** Skip the client "world loaded" handshake that a real client would send. */
	public void markLoaded() {
		connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
	}

	private final Perf perf = new Perf();

	/** Where its time goes (see /bot perf). */
	public Perf perf() {
		return perf;
	}

	@Override
	public void tick() {
		long started = System.nanoTime();
		if (tickCount % 10 == 0) {
			connection.resetPosition();
			level().getChunkSource().move(this);
		}
		super.tick();
		// What ServerGamePacketListenerImpl.tickPlayer() does for a real client.
		xo = getX();
		yo = getY();
		zo = getZ();
		doTick();
		perf.add(Perf.Part.VANILLA, System.nanoTime() - started);
		try {
			if (isAlive()) {
				behave();
			}
		} finally {
			perf.endTick();
		}
	}

	/** Its own part of the tick: survival reflexes first, else the task, then moving. */
	private void behave() {
		long started = System.nanoTime();
		updateGhost();
		loot.observe();
		// Survival first: air, then fighting back. The task waits meanwhile.
		if (breathe()) {
			hand(Layer.BREATH);
			perf.add(Perf.Part.SURVIVAL, System.nanoTime() - started);
			return; // it moved itself this tick
		}
		if (combat()) {
			hand(Layer.COMBAT);
			movement.tick();
			perf.add(Perf.Part.SURVIVAL, System.nanoTime() - started);
			return;
		}
		// Eating takes its 1.6 s like for anyone. It keeps walking meanwhile, slowed down the
		// way vanilla slows a player who's eating; the task itself waits.
		if (isUsingItem() || tryEat()) {
			hand(Layer.EAT);
			movement.tick();
			perf.add(Perf.Part.SURVIVAL, System.nanoTime() - started);
			return;
		}
		hand(Layer.TASK);
		perf.add(Perf.Part.SURVIVAL, System.nanoTime() - started);
		started = System.nanoTime();
		if (tickCount % 40 == 0 && freeSlots() <= LOW_FREE_SLOTS) {
			tidyInventory();
		}
		if (task != null) {
			BotTask.Status status = task.tick(this);
			if (status != BotTask.Status.RUNNING) {
				lastTaskStatus = status;
				task.onEnd(this, status);
				task = null;
				movement.stop();
			}
		}
		perf.add(Perf.Part.TASK, System.nanoTime() - started);
		started = System.nanoTime();
		movement.tick();
		perf.add(Perf.Part.MOVE, System.nanoTime() - started);
	}

	@Override
	public void awardKillScore(net.minecraft.world.entity.Entity killed, DamageSource source) {
		super.awardKillScore(killed, source);
	}

	// ---- ghost in protected chunks ------------------------------------------------------

	private boolean ghost;

	/**
	 * In a protected chunk (a base) the bot is a ghost: to the server it counts as a spectator, so
	 * everything vanilla does for spectators applies (pressure plates, tripwires and sculk sensors
	 * don't notice it, mobs don't target it, nothing pushes it, it picks nothing up). It stays in
	 * survival mode though, so players' clients still see and draw it (a real spectator is hidden
	 * from them), and it still walks by its own collision rules (see MovementController): it
	 * doesn't go through walls, it only passes through without setting anything off. Only players
	 * decide where that is.
	 */
	private void updateGhost() {
		boolean inZone = isGhost();
		if (inZone == ghost) {
			return;
		}
		ghost = inZone; // only for the log: isGhost() is what counts
		log(inZone ? "进入保护区块：灵魂状态（只路过，不碰任何东西，不触发机关，不被怪盯上）" : "离开保护区块，恢复正常");
	}

	/**
	 * Any part of it in a protected chunk, worked out whenever asked: vanilla checks what it stands
	 * on (a pressure plate) before this class gets its turn in the tick, so a flag updated once a
	 * tick was a tick late, and the first plate past the edge of a protected chunk got pressed.
	 */
	public boolean isGhost() {
		var level = level();
		if (level == null) {
			return false;
		}
		net.minecraft.world.phys.AABB box = getBoundingBox();
		for (double x : new double[]{box.minX, box.maxX}) {
			for (double z : new double[]{box.minZ, box.maxZ}) {
				if (com.vale.helperbot.guard.ProtectionZones.isProtected(level, BlockPos.containing(x, box.minY, z))) {
					return true;
				}
			}
		}
		return false;
	}

	@Override
	public boolean isSpectator() {
		return isGhost() || super.isSpectator();
	}

	/**
	 * Invisible only from an invisibility potion. Vanilla also makes spectators invisible whenever
	 * this runs (on joining, and when effects change), so a bot summoned inside a protected chunk
	 * was invisible from then on, only its armour showing; one walking in wasn't (this didn't run).
	 */
	@Override
	protected void updateInvisibilityStatus() {
		if (isGhost() && !super.isSpectator()) {
			if (getActiveEffects().isEmpty()) {
				removeEffectParticles();
			}
			setInvisible(hasEffect(net.minecraft.world.effect.MobEffects.INVISIBILITY));
			return;
		}
		super.updateInvisibilityStatus();
	}

	/** Players see it even as a ghost (vanilla doesn't send spectators to non-spectators at all). */
	@Override
	public boolean broadcastToPlayer(ServerPlayer viewer) {
		return isGhost() || super.broadcastToPlayer(viewer);
	}

	/**
	 * With portals restricted, no portal takes it anywhere, however it got in (pushed, fell, a path
	 * mistake). Paths already keep out of portal blocks (see Guard#isNoStep).
	 */
	@Override
	public boolean canUsePortal(boolean allowPassengers) {
		return !com.vale.helperbot.guard.Restrictions.noPortals() && super.canUsePortal(allowPassengers);
	}

	/** A ghost hits nothing (a spectator's "attack" would be looking through the target's eyes). */
	@Override
	public void attack(net.minecraft.world.entity.Entity target) {
		if (!isGhost()) {
			super.attack(target);
		}
	}

	// ---- who's in charge ----------------------------------------------------------------

	/**
	 * Who drives the bot this tick, most urgent first. The reflexes (air, fighting back, eating,
	 * collecting drops) each decide for themselves when they're needed and when they're done; this
	 * only keeps track of who has control, says so in the log whenever it changes hands (so a
	 * tug-of-war shows up as such), and holds the rules between them: each one, once it has
	 * control, keeps it until it says it's done (see e.g. {@link Breath}, {@link #CALM_TICKS}),
	 * and a reflex that has just handed control back doesn't grab it again for the same thing
	 * (see {@link Loot}).
	 */
	public enum Layer {
		BREATH("换气"), COMBAT("打架"), EAT("吃东西"), TASK("任务");

		final String label;

		Layer(String label) {
			this.label = label;
		}
	}

	private Layer owner = Layer.TASK;
	/** Times control changed hands, for tests and diagnostics. */
	private int handovers;

	private void hand(Layer to) {
		if (to == owner) {
			return;
		}
		log("控制权：" + owner.label + " → " + to.label);
		owner = to;
		handovers++;
	}

	public Layer owner() {
		return owner;
	}

	public int handovers() {
		return handovers;
	}

	/** How it's getting air: a planned way up, or straight up. */
	private enum Breath {
		NONE, ROUTE, CLIMB
	}

	private Breath breath = Breath.NONE;
	private int breathTicks;
	private double bestEscape;
	/** The planner found no way up at all (see MovementController#digOut). */
	private boolean digOut;
	/** On the planned way up this long without getting any closer to air: just go straight up. */
	private static final int ROUTE_STALL_TICKS = 60;

	/**
	 * Top survival priority, independent of what the task or path is doing. The same rule the
	 * planner follows (see {@link Air}): the air budget, health it may spend included, must cover
	 * the way back up from here. When it barely does (or the planner found nowhere to go on the air
	 * left), it goes for air: up the quickest way the planner finds, swimming. If that gets nowhere
	 * it swims straight up; and if there's really no way out by swimming (something changed behind
	 * it: plans don't go where digging is the only way out), it digs what's overhead, the one time
	 * it digs with its head under water. Then a full breath, and back to what it was doing. Returns
	 * true while busy with that.
	 */
	private boolean breathe() {
		boolean underwater = movement.headUnderwater();
		if (breath == Breath.NONE) {
			if (!underwater) {
				return false;
			}
			int budget = Air.budget(this);
			if (budget >= Air.UNLIMITED) {
				return false;
			}
			double escape = movement.escapeTicks();
			boolean asked = movement.takeAirRequest();
			boolean low = !Air.enough(budget, escape, 0); // the planner's rule, without its slack
			if (!low && !asked) {
				return false;
			}
			breathTicks = 0;
			breath = Breath.ROUTE;
			bestEscape = escape;
			String plan = movement.planAirNote();
			log("该换气了（还能撑 " + budget / 20 + " 秒，上去要 "
					+ (Double.isInfinite(escape) ? "?" : String.valueOf((int) Math.ceil(escape / 20))) + " 秒；实际 " + budget + " 刻"
					+ (plan != null ? "；路线" + plan : "") + "）");
			movement.headForAir();
		}
		breathTicks++;
		if (underwater) {
			if (breath == Breath.ROUTE) {
				movement.tick();
				double escape = movement.escapeTicks();
				if (escape < bestEscape - 1) {
					bestEscape = escape;
					breathTicks = 0;
				}
				boolean noWay = movement.status() == MovementController.Status.FAILED;
				if (breathTicks > ROUTE_STALL_TICKS || noWay) {
					log(noWay ? "找不到上去的路，直接往上，需要就挖开" : "找路上不去，直接往上游");
					breath = Breath.CLIMB;
					digOut = noWay;
					movement.pause();
				}
			} else {
				movement.hands().digOut(digOut || Double.isInfinite(movement.escapeTicks()));
				movement.ascendStep();
			}
			return true;
		}
		// Head in air: a full breath first.
		if (getAirSupply() < getMaxAirSupply()) {
			if (breath == Breath.CLIMB) {
				movement.ascendStep(); // floats at the surface
			} else {
				movement.tick();
			}
			return true;
		}
		log("换好气了，继续");
		breath = Breath.NONE;
		digOut = false;
		movement.hands().digOut(false);
		movement.disengage();
		movement.resume();
		return false;
	}

	/** Ranged attackers (skeletons, witches, pillagers...) this close are chased down. */
	private static final double RANGED_ENGAGE_RANGE = 10;
	/** Stay on guard this long after the last threat before going back to the task. */
	private static final int CALM_TICKS = 20;
	/** Something that hit us this recently counts as a threat even if it isn't "targeting" us. */
	private static final int RECENT_HIT_TICKS = 60;
	/** Give up chasing a ranged attacker after this long (it's up a pillar, flying...)... */
	private static final int CHASE_GIVE_UP = 200;
	/** ...and leave it alone (unless it comes within reach) for this long. */
	private static final int IGNORE_TICKS = 300;
	private boolean inCombat;
	private int calmTicks;
	private java.util.UUID chasing;
	private int chaseTicks;
	private BlockPos chaseAim;
	private final java.util.Map<java.util.UUID, Integer> ignoredUntil = new java.util.HashMap<>();

	/**
	 * Fight back, putting its own safety before the task. Threats are mobs (hostile or not, e.g. a
	 * polar bear guarding a cub) that target it or just hit it, and that it can see:
	 * <ul>
	 * <li>ranged attackers within {@link #RANGED_ENGAGE_RANGE} come first, since they keep shooting:
	 * it goes after them, to a spot it can hit from;</li>
	 * <li>melee mobs only once actually within reach (it reaches further than they do, so it still
	 * strikes first); before that it just keeps working.</li>
	 * </ul>
	 * The task is paused meanwhile, the weapon is taken out once and kept in hand (swapping items
	 * resets the attack charge in vanilla), and it only swings at full charge. Afterwards it goes
	 * back to what it was doing. Tamed pets are never attacked. Returns true while in combat.
	 */
	private boolean combat() {
		if (tickCount % IGNORE_TICKS == 0) {
			ignoredUntil.values().removeIf(until -> until < tickCount);
		}
		Mob threat = pickThreat();
		if (threat == null) {
			if (inCombat && --calmTicks <= 0) {
				inCombat = false;
				chasing = null;
				log("周围安全了，继续任务");
				movement.disengage();
				movement.resume();
			}
			return inCombat;
		}
		String name = threat.getType().getDescription().getString();
		if (!inCombat) {
			inCombat = true;
			log((isRanged(threat) ? "被远程怪 " : "遭到 ") + name + " 攻击，暂停任务先打它");
			movement.pause();
			holdWeapon();
		}
		calmTicks = CALM_TICKS;
		movement.hands().lookAt(threat.getBoundingBox().getCenter());
		if (isWithinEntityInteractionRange(threat, 0) && hasLineOfSight(threat)) {
			movement.pause();
			chaseTicks = 0;
			// Getting here may have meant digging or placing blocks (pickaxe, dirt in hand).
			holdWeapon();
			if (getAttackStrengthScale(0.5f) >= 0.9f) {
				attack(threat);
				swing(InteractionHand.MAIN_HAND);
			}
			return true;
		}
		// Out of reach: only ranged attackers get picked like that. Go get it.
		if (!threat.getUUID().equals(chasing)) {
			chasing = threat.getUUID();
			chaseTicks = 0;
			chaseAim = null;
		}
		if (++chaseTicks > CHASE_GIVE_UP
				|| (movement.isEngaged() && movement.status() == MovementController.Status.FAILED)) {
			ignoredUntil.put(threat.getUUID(), tickCount + IGNORE_TICKS);
			log("够不着 " + name + "，先不管它");
			chasing = null;
			movement.pause();
			return true;
		}
		BlockPos at = threat.blockPosition();
		// Paused (it was within reach a moment ago, then the mob stepped back): set off again,
		// even if the mob has barely moved. Otherwise it stood there staring, "walking" on paper.
		if (chaseAim == null || chaseAim.distSqr(at) > 4 || !movement.isEngaged() || movement.isPaused()
				|| movement.status() != MovementController.Status.MOVING) {
			movement.engage(Sight.attackSpot(this, threat), at);
			chaseAim = at;
		}
		return true;
	}

	/** Ranged attackers within range first (they keep shooting), else melee mobs within reach. */
	private Mob pickThreat() {
		if (isGhost()) {
			return null; // nothing targets a ghost, and it hits nothing (a spectator's attack is "look through")
		}
		List<Mob> threats = level().getEntitiesOfClass(Mob.class, getBoundingBox().inflate(RANGED_ENGAGE_RANGE),
				m -> m.isAlive() && (m.getTarget() == this
						|| (m == getLastHurtByMob() && tickCount - getLastHurtByMobTimestamp() < RECENT_HIT_TICKS))
						&& !(m instanceof TamableAnimal pet && pet.isTame())
						// Like a player, it can't hit what it can't see (e.g. a mob behind the wall).
						&& hasLineOfSight(m));
		Mob best = null;
		for (Mob m : threats) {
			boolean inReach = isWithinEntityInteractionRange(m, 0);
			boolean ignored = ignoredUntil.getOrDefault(m.getUUID(), 0) > tickCount;
			boolean ranged = isRanged(m) && distanceTo(m) <= RANGED_ENGAGE_RANGE && !ignored;
			if (!(inReach || ranged)) {
				continue;
			}
			if (best == null || betterThreat(m, best)) {
				best = m;
			}
		}
		return best;
	}

	private boolean betterThreat(Mob a, Mob b) {
		if (isRanged(a) != isRanged(b)) {
			return isRanged(a);
		}
		return distanceToSqr(a) < distanceToSqr(b);
	}

	private static boolean isRanged(Mob mob) {
		return mob instanceof net.minecraft.world.entity.monster.RangedAttackMob
				|| mob instanceof net.minecraft.world.entity.monster.CrossbowAttackMob;
	}

	/**
	 * Hold the best weapon the player gave it (sword, else axe), if any. Does nothing if one of
	 * that kind is already in hand: every swap resets the attack charge in vanilla.
	 */
	public void holdWeapon() {
		int weapon = bestWeaponSlot();
		if (weapon < 0) {
			return;
		}
		ItemStack held = getMainHandItem();
		boolean swordBest = getInventory().getItem(weapon).is(ItemTags.SWORDS);
		if (held.is(ItemTags.SWORDS) || (!swordBest && held.is(ItemTags.AXES))) {
			return;
		}
		movement.hands().selectSlot(weapon);
	}

	private int bestWeaponSlot() {
		Inventory inv = getInventory();
		int axe = -1;
		for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
			ItemStack stack = inv.getItem(slot);
			if (stack.is(ItemTags.SWORDS)) {
				return slot;
			}
			if (axe < 0 && stack.is(ItemTags.AXES)) {
				axe = slot;
			}
		}
		return axe;
	}

	/** Eats food the player gave it when hungry, or when hurt and able to regenerate. */
	private boolean tryEat() {
		int food = getFoodData().getFoodLevel();
		int missing = 20 - food;
		// Keep enough to sprint (> 6); when hurt, keep 18+ so health regenerates.
		boolean wantFood = food <= 14 || (getHealth() < getMaxHealth() && food < 18);
		if (!wantFood || !movement.atRest()) {
			return false;
		}
		// Don't waste food: pick the biggest that fits in the bar. If none fits but it's
		// really hungry (can't sprint), eat the smallest.
		Inventory inv = getInventory();
		// What it's collecting for someone isn't its lunch (it once ate the sweet berries it was
		// sent for). Only when starving, with health draining, is that fair game.
		boolean starving = food == 0;
		int bestSlot = -1, bestValue = -1, smallestSlot = -1, smallestValue = Integer.MAX_VALUE;
		for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
			ItemStack stack = inv.getItem(slot);
			var props = stack.get(DataComponents.FOOD);
			if (props == null || BAD_FOOD.contains(stack.getItem())
					|| (!starving && task != null && task.wantsPickup(stack))) {
				continue;
			}
			int value = props.nutrition();
			if (value <= missing && value > bestValue) {
				bestSlot = slot;
				bestValue = value;
			}
			if (value < smallestValue) {
				smallestSlot = slot;
				smallestValue = value;
			}
		}
		int slot = bestSlot >= 0 ? bestSlot : food <= 6 ? smallestSlot : -1;
		if (slot < 0) {
			return false;
		}
		movement.hands().selectSlot(slot);
		gameMode.useItem(this, level(), getMainHandItem(), InteractionHand.MAIN_HAND);
		if (isUsingItem()) {
			log("吃 " + getMainHandItem().getHoverName().getString() + "（饥饿值 " + food + "）");
		}
		return isUsingItem();
	}

	/** Vanilla physics is replaced by {@link MovementController}. */
	@Override
	public void travel(Vec3 input) {
	}

	@Override
	public void die(DamageSource cause) {
		super.die(cause);
		BotTask current = task;
		task = null;
		movement.stop();
		if (current != null) {
			current.onEnd(this, BotTask.Status.FAILED);
		}
		level().getServer().execute(() -> {
			resetAfterDeath();
			BotManager.remove(level().getServer(), "机器人死亡，已移除。用 /bot spawn 重新召唤（死亡不掉落时背包会保留）。");
		});
	}

	/**
	 * What vanilla resets when a player respawns: full health, full food and some saturation, no
	 * effects, air, fire. (It used to only refill health, and came back starving.)
	 */
	public void resetAfterDeath() {
		setHealth(getMaxHealth());
		getFoodData().setFoodLevel(20);
		getFoodData().setSaturation(5.0f);
		removeAllEffects();
		setAirSupply(getMaxAirSupply());
		clearFire();
		resetFallDistance();
	}

	/**
	 * Can it get health back right now: food at 18 or more (vanilla regenerates then), or
	 * something to eat to get there. Waiting to heal without either just wastes time.
	 */
	public boolean canRegenerate() {
		if (getFoodData().getFoodLevel() >= 18) {
			return true;
		}
		Inventory inv = getInventory();
		for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
			ItemStack stack = inv.getItem(slot);
			if (stack.get(DataComponents.FOOD) != null && !BAD_FOOD.contains(stack.getItem())
					&& (task == null || !task.wantsPickup(stack))) {
				return true;
			}
		}
		return false;
	}

	public void startTask(BotTask newTask) {
		if (task != null) {
			task.onEnd(this, BotTask.Status.CANCELLED);
		}
		movement.stop();
		task = newTask;
	}

	public void cancelTask() {
		if (task != null) {
			task.onEnd(this, BotTask.Status.CANCELLED);
			task = null;
		}
		movement.stop();
	}

	public BotTask getTask() {
		return task;
	}

	/** How the last task that ended by itself ended (for the test scenarios). */
	private BotTask.Status lastTaskStatus;

	public BotTask.Status lastTaskStatus() {
		return lastTaskStatus;
	}

	public MovementController movement() {
		return movement;
	}

	public Loot loot() {
		return loot;
	}

	public boolean isDebugPath() {
		return debugPath;
	}

	public void setDebugPath(boolean debugPath) {
		this.debugPath = debugPath;
	}

	/** Building blocks kept for bridges and pillars; more than this aren't picked up, and get tidied away. */
	private static final int BUILDING_BLOCK_STOCK = 128;
	/** Tidy the bag when this few free slots are left. */
	private static final int LOW_FREE_SLOTS = 2;
	/** What the bot picked up itself (only this may be thrown away when tidying). */
	private final java.util.Map<Item, Integer> pickedUp = new java.util.HashMap<>();

	/**
	 * What the current task collects, plus (open rules) a stock of building blocks for bridges and
	 * pillars. Saplings, flowers, seeds and the like are left on the ground, so the bag doesn't
	 * fill up with stuff nobody asked for.
	 */
	public boolean wantsPickup(ItemStack stack) {
		if (taskWants(stack)) {
			return true;
		}
		if (!Rules.open() || !com.vale.helperbot.path.BuildingBlocks.isBuildingBlock(stack)) {
			return false;
		}
		// Restock below the low mark, up to the full stock, with a gap in between: right at a single
		// threshold, placing one block made it want the next one again, and it dug up the block it
		// had just put down to fetch a dropped one, and so on forever.
		int have = com.vale.helperbot.path.BuildingBlocks.count(getInventory());
		if (have < BUILDING_BLOCK_LOW) {
			restocking = true;
		} else if (have >= BUILDING_BLOCK_STOCK) {
			restocking = false;
		}
		// Only while there's room, or it would pick up again what it just threw away to make room.
		return restocking && freeSlots() > LOW_FREE_SLOTS + 1;
	}

	/** Things the current task needs: only these are worth going out of the way for (see task/DropFetch). */
	public boolean taskWants(ItemStack stack) {
		return task != null && task.wantsPickup(stack);
	}

	/** Start picking up building blocks again below this. */
	private static final int BUILDING_BLOCK_LOW = 64;
	private boolean restocking = true;

	public void notePickedUp(Item item, int count) {
		pickedUp.merge(item, count, Integer::sum);
		loot.onPickup(item);
	}

	/**
	 * Make room: throw away building blocks it picked up itself beyond its stock, then anything
	 * else it picked up itself that the current task doesn't need. Never throws away what a player
	 * gave it. Returns true if at least one slot is free afterwards.
	 */
	public boolean tidyInventory() {
		Inventory inv = getInventory();
		if (inv.getFreeSlot() >= 0 && freeSlots() > LOW_FREE_SLOTS) {
			return true;
		}
		int dropped = 0;
		for (int pass = 0; pass < 2 && freeSlots() <= LOW_FREE_SLOTS; pass++) {
			for (int slot = Inventory.INVENTORY_SIZE - 1; slot >= 0 && freeSlots() <= LOW_FREE_SLOTS; slot--) {
				ItemStack stack = inv.getItem(slot);
				int mine = pickedUp.getOrDefault(stack.getItem(), 0);
				if (stack.isEmpty() || mine <= 0 || slot == inv.getSelectedSlot()) {
					continue;
				}
				boolean junk = pass == 0
						? com.vale.helperbot.path.BuildingBlocks.isBuildingBlock(stack)
						&& com.vale.helperbot.path.BuildingBlocks.count(inv) - stack.getCount() >= BUILDING_BLOCK_STOCK / 2
						: task == null || !task.wantsPickup(stack);
				if (!junk) {
					continue;
				}
				int n = Math.min(mine, stack.getCount());
				ItemStack thrown = stack.split(n);
				pickedUp.merge(thrown.getItem(), -n, Integer::sum);
				dropped += n;
				drop(thrown, false);
			}
		}
		if (dropped > 0) {
			log("背包快满了，扔掉自己捡的 " + dropped + " 个没用的东西");
		}
		return inv.getFreeSlot() >= 0;
	}

	private int freeSlots() {
		int free = 0;
		for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
			if (getInventory().getItem(slot).isEmpty()) {
				free++;
			}
		}
		return free;
	}

	private static final int LOG_SIZE = 40;
	private final java.util.ArrayDeque<String> log = new java.util.ArrayDeque<>();

	/**
	 * Records a decision (re-plan and why, step failures, obstacles learned, attacks...) for
	 * {@code /bot log}, so "it just stopped" can be traced to a reason.
	 */
	public void log(String event) {
		if (log.size() >= LOG_SIZE) {
			log.removeFirst();
		}
		log.addLast(String.format("[%.1fs] ", tickCount / 20.0) + event);
		// Also into the server log (latest.log): the in-memory one is gone once the bot dies or is
		// removed, which is exactly when a rare bug needs looking into.
		com.vale.helperbot.HelperBot.LOGGER.info("[{}] {}", getGameProfile().name(), event);
		if (logTap != null) {
			logTap.accept(event);
		}
	}

	/** Test scenarios listen in on the log (see com.vale.helperbot.test). */
	public static java.util.function.Consumer<String> logTap;

	public java.util.List<String> recentLog(int n) {
		java.util.List<String> all = new java.util.ArrayList<>(log);
		return all.subList(Math.max(0, all.size() - n), all.size());
	}

	/**
	 * That player as they are now, or null if they're offline. Tasks are given a player object, but
	 * dying or logging back in makes a new one, and the old one stays behind, frozen where it was.
	 */
	public ServerPlayer current(ServerPlayer player) {
		ServerPlayer listed = level().getServer().getPlayerList().getPlayer(player.getUUID());
		return listed != null ? listed : player.hasDisconnected() ? null : player;
	}

	public void say(ServerPlayer to, String message) {
		say(to, Component.literal(message));
	}

	/** Components let item and block names show in the player's own language. */
	public void say(ServerPlayer to, Component message) {
		ServerPlayer player = to == null ? null : current(to);
		if (player != null) {
			player.sendSystemMessage(Component.literal("[" + getGameProfile().name() + "] ").append(message));
		}
		if (logTap != null) {
			logTap.accept("说：" + message.getString());
		}
	}
}
