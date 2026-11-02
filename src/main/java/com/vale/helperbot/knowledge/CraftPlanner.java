package com.vale.helperbot.knowledge;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.FuelValues;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Works out how to get N of an item from what the bot has: use it from the inventory, craft it,
 * smelt it, or gather it, recursively for every ingredient (and the crafting table, furnace and
 * fuel those need). Every option is costed in rough game ticks and the cheapest kept.
 *
 * <p>Plans against a virtual copy of the inventory, so leftovers of one step (4 planks from a log)
 * feed the next, and stations are made once and reused. The caller executes only the first action
 * and plans again from the real inventory, which absorbs any surprises.
 */
public final class CraftPlanner {
	public sealed interface Action permits Gather, Hunt, Craft, Smelt, Fill {
	}

	public record Gather(Item item, int count) implements Action {
	}

	/** Kill mobs that drop the item ({@code sources}: mob type -> drops per kill). */
	public record Hunt(Item item, int count, Map<EntityType<?>, Double> sources) implements Action {
	}

	public record Craft(RecipeIndex.Craft recipe, int times) implements Action {
	}

	public record Smelt(RecipeIndex.Smelt recipe, int count) implements Action {
	}

	/** Fill {@code count} empty buckets with water. */
	public record Fill(int count) implements Action {
	}

	/** Actions in order; empty if the inventory already has everything. Null actions = impossible. */
	public record Plan(List<Action> actions, Set<Component> problems, boolean timedOut) {
		public boolean possible() {
			return actions != null;
		}
	}

	// Rough costs in ticks.
	/** Fixed cost of a trip to a biome that has the source, when none is in the loaded chunks nearby. */
	private static final double GATHER_IN_BIOME = 2_000;
	/** Loaded chunks around the bot looked through for sources. */
	private static final int NEARBY_CHUNKS = 4;
	/** Sources looked at when estimating a trip (the nearest ones). */
	private static final int MAX_SITES = 64;
	/** Walking, blocks per tick. */
	private static final double WALK_SPEED = 0.2;
	/** Digging through one block in the way (mining it plus stepping on). */
	private static final double DIG_PER_BLOCK = 20;
	/** Mining the source block itself. */
	private static final double MINE_PER_BLOCK = 20;
	private static final double GATHER_PER_ITEM = 40;
	/** Going to water and filling buckets. */
	private static final double FILL_FIXED = 400;
	private static final double FILL_PER_ITEM = 5;
	/** Fixed cost of a hunting trip, by where the prey is (see {@link #huntCost}). */
	private static final double HUNT_NEARBY = 400;
	private static final double HUNT_IN_BIOME = 2_500;
	private static final double HUNT_WAIT_FOR_SPAWN = 3_500;
	/** Ticks per kill: chase, fight, pick up. */
	private static final double HUNT_PER_KILL = 150;
	private static final double CRAFT_PER_OP = 10;
	private static final double SMELT_FIXED = 40;
	/** Deep enough for e.g. diamond pickaxe <- diamond <- iron pickaxe <- iron <- stone pickaxe <- cobblestone <- wooden pickaxe <- planks <- log. */
	private static final int MAX_DEPTH = 16;
	/** Cap on explored (item, need) nodes per plan, so planning stays a few milliseconds. */
	private static final int SEARCH_BUDGET = 40_000;
	private int budget = SEARCH_BUDGET;
	/**
	 * Safety net: planning runs on the server thread, so it never takes longer than this. Normally
	 * a plan takes milliseconds; if this is ever hit, the best plan found so far is used (and logged).
	 */
	private static final long TIME_LIMIT_NANOS = 300_000_000L;
	private long deadline;
	private boolean timedOut;
	/**
	 * What the plan is for. Never taken from the bag as an ingredient, fuel or anything else inside
	 * its own plan: a white carpet already made is something to hand over, not material (it used to
	 * dye finished white carpets blue and then back to white, because both recipes accept them).
	 */
	private Item reserved;
	/** How many different recipes, and ingredient alternatives, to try per item. */
	private static final int MAX_RECIPES = 4;
	private static final int MAX_ALTERNATIVES = 3;
	/** Fuels to make if there's none: burn time is read from the game, these are just the candidates. */
	private static final List<Item> FUEL_CANDIDATES = List.of(Items.COAL, Items.CHARCOAL, Items.OAK_LOG,
			Items.SPRUCE_LOG, Items.BIRCH_LOG, Items.OAK_PLANKS);

	private final ServerLevel level;
	private final Entity breaker;
	private final Inventory realInventory;
	/** Items whose gathering already failed this task: don't plan to gather them again. */
	private final Set<Item> noGather;
	private final FuelValues fuel;
	private final Set<Component> problems = new LinkedHashSet<>();
	/** Source blocks found nearby, nearest first. */
	private final Map<Item, List<BlockPos>> sites = new HashMap<>();
	private final Map<Item, Set<Block>> sourceBlocks = new HashMap<>();
	private final Map<Item, Map<EntityType<?>, Double>> huntSources = new HashMap<>();
	/** Expected items per broken block (capped at 1: a block giving several still takes a break). */
	private final Map<Item, Double> gatherRate = new HashMap<>();

	public CraftPlanner(ServerLevel level, Entity breaker, Inventory realInventory, Set<Item> noGather) {
		this.level = level;
		this.breaker = breaker;
		this.realInventory = realInventory;
		this.noGather = noGather;
		this.fuel = level.fuelValues();
	}

	/** Snapshot of an inventory as item counts. */
	public static Map<Item, Integer> counts(Inventory inventory) {
		Map<Item, Integer> counts = new HashMap<>();
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (!stack.isEmpty()) {
				counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
			}
		}
		return counts;
	}

	/**
	 * Plan for {@code count} more of {@code item}. Whatever of it {@code inventory} holds is ignored:
	 * the caller counts what is already done, and it is never material for its own plan.
	 */
	public Plan plan(Item item, int count, Map<Item, Integer> inventory) {
		Map<Item, Integer> inv = new HashMap<>(inventory);
		inv.remove(item);
		reserved = item;
		deadline = System.nanoTime() + TIME_LIMIT_NANOS;
		State state = new State(inv);
		if (!acquire(state, item, count, 0, new HashSet<>())) {
			if (timedOut) {
				problems.add(Component.literal("规划超时（物品链太复杂）"));
			}
			return new Plan(null, problems, timedOut);
		}
		return new Plan(state.actions, problems, timedOut);
	}

	/** Take from the virtual bag, except the item being planned for (see {@link #reserved}). */
	private int take(State s, Item item, int n) {
		return item == reserved ? 0 : s.take(item, n);
	}

	// ---- search -------------------------------------------------------------------------

	private static final class State {
		final Map<Item, Integer> inv;
		final List<Action> actions;
		double cost;

		State(Map<Item, Integer> inv) {
			this(inv, new ArrayList<>(), 0);
		}

		State(Map<Item, Integer> inv, List<Action> actions, double cost) {
			this.inv = inv;
			this.actions = actions;
			this.cost = cost;
		}

		State copy() {
			return new State(new HashMap<>(inv), new ArrayList<>(actions), cost);
		}

		void become(State other) {
			inv.clear();
			inv.putAll(other.inv);
			actions.clear();
			actions.addAll(other.actions);
			cost = other.cost;
		}

		int have(Item item) {
			return inv.getOrDefault(item, 0);
		}

		int take(Item item, int n) {
			int used = Math.min(have(item), n);
			if (used > 0) {
				inv.merge(item, -used, Integer::sum);
			}
			return used;
		}

		void add(Item item, int n) {
			if (n > 0) {
				inv.merge(item, n, Integer::sum);
			}
		}
	}

	/** Make {@code n} of {@code item} available (and consumed) in {@code s}. False if impossible. */
	private boolean acquire(State s, Item item, int n, int depth, Set<Item> resolving) {
		n -= take(s, item, n);
		if (n <= 0) {
			return true;
		}
		if (depth > MAX_DEPTH || resolving.contains(item)) {
			return false; // too deep, or a loop (e.g. iron ingot <-> iron block)
		}
		if (--budget < 0) {
			return false; // recipe trees can branch a lot: stop exploring, keep what we have
		}
		if (System.nanoTime() > deadline) {
			timedOut = true;
			return false;
		}
		resolving.add(item);
		State best = null;

		if (gatherable(item)) {
			State t = s.copy();
			// The right tool first, if it takes one the bot doesn't have (make it, like any ingredient).
			if (ensureGatherTool(t, item, depth, resolving)) {
				double trip = gatherCost(item, n, false);
				if (!Double.isNaN(trip)) {
					t.cost += trip;
					t.actions.add(new Gather(item, n));
					best = t;
				}
			}
		}
		if (item == Items.WATER_BUCKET) {
			State t = s.copy();
			if (acquire(t, Items.BUCKET, n, depth + 1, resolving)) {
				t.cost += FILL_FIXED + FILL_PER_ITEM * n;
				t.actions.add(new Fill(n));
				if (best == null || t.cost < best.cost) {
					best = t;
				}
			}
		}
		Map<EntityType<?>, Double> prey = huntSources(item);
		if (prey.isEmpty() && huntProblem.containsKey(item)) {
			problems.add(huntProblem.get(item));
		}
		if (!prey.isEmpty()) {
			double perKill = prey.values().stream().mapToDouble(Double::doubleValue).max().orElse(1.0);
			State t = s.copy();
			t.cost += huntCost(prey) + HUNT_PER_KILL * n / Math.min(perKill, 1.0);
			t.actions.add(new Hunt(item, n, prey));
			if (best == null || t.cost < best.cost) {
				best = t;
			}
		}
		int tried = 0;
		for (RecipeIndex.Craft craft : RecipeIndex.craftsFor(level, item)) {
			if (tried++ >= MAX_RECIPES) {
				break;
			}
			State t = s.copy();
			if (craftInto(t, craft, n, depth, resolving, limit(best)) && (best == null || t.cost < best.cost)) {
				best = t;
			}
		}
		for (RecipeIndex.Smelt smelt : RecipeIndex.smeltsFor(level, item)) {
			State t = s.copy();
			if (smeltInto(t, smelt, n, depth, resolving, limit(best)) && (best == null || t.cost < best.cost)) {
				best = t;
			}
		}
		resolving.remove(item);
		if (best == null) {
			return false;
		}
		s.inv.clear();
		s.inv.putAll(best.inv);
		s.actions.clear();
		s.actions.addAll(best.actions);
		s.cost = best.cost;
		return true;
	}

	/**
	 * Branch and bound: a way that already costs more than the best one found can stop there.
	 * Costs only ever add up, so this never loses the cheapest plan, it just skips exploring the
	 * rest of ways that can't win.
	 */
	private static double limit(State best) {
		return best == null ? Double.MAX_VALUE : best.cost;
	}

	private boolean craftInto(State t, RecipeIndex.Craft craft, int n, int depth, Set<Item> resolving, double limit) {
		int times = (n + craft.resultCount() - 1) / craft.resultCount();
		if (craft.needsTable() && !keepStation(t, Items.CRAFTING_TABLE, depth, resolving)) {
			return false;
		}
		for (Map.Entry<Ingredient, Integer> e : group(craft.ingredients()).entrySet()) {
			if (t.cost >= limit || !acquireIngredient(t, e.getKey(), e.getValue() * times, depth, resolving)) {
				return false;
			}
		}
		t.cost += CRAFT_PER_OP * times;
		t.actions.add(new CraftPlanner.Craft(craft, times));
		t.add(craft.result(), times * craft.resultCount() - n); // leftovers stay in the bag
		return true;
	}

	private boolean smeltInto(State t, RecipeIndex.Smelt smelt, int n, int depth, Set<Item> resolving, double limit) {
		int inputs = (n + smelt.resultCount() - 1) / smelt.resultCount();
		if (!keepStation(t, Items.FURNACE, depth, resolving) || t.cost >= limit
				|| !acquireIngredient(t, smelt.input(), inputs, depth, resolving) || t.cost >= limit
				|| !acquireFuel(t, inputs * smelt.cookingTime(), depth, resolving)) {
			return false;
		}
		t.cost += SMELT_FIXED + (double) inputs * smelt.cookingTime();
		t.actions.add(new CraftPlanner.Smelt(smelt, inputs));
		t.add(smelt.result(), inputs * smelt.resultCount() - n);
		return true;
	}

	/** A crafting table or furnace: needed, but not used up. */
	private boolean keepStation(State t, Item station, int depth, Set<Item> resolving) {
		if (t.have(station) > 0) {
			return true;
		}
		if (!acquire(t, station, 1, depth + 1, resolving)) {
			problems.add(Component.literal("需要").append(name(station)).append("，但做不出来"));
			return false;
		}
		t.add(station, 1);
		return true;
	}

	/** Any mix of the accepted items; if more are needed, the cheapest way to get one kind. */
	private boolean acquireIngredient(State t, Ingredient ingredient, int n, int depth, Set<Item> resolving) {
		List<Item> options = RecipeIndex.options(ingredient);
		for (Item option : options) {
			n -= take(t, option, n);
			if (n <= 0) {
				return true;
			}
		}
		// Only a few alternatives get a full search, so try the likely-cheap ones: for "any wool but
		// pink", white wool from a sheep, not black wool that first needs dyeing (the recipe lists
		// white last).
		List<Item> ranked = new ArrayList<>(options);
		Map<Item, Double> quick = new HashMap<>();
		for (Item option : ranked) {
			quick.put(option, quickCost(option));
		}
		ranked.sort(Comparator.comparingDouble(quick::get));
		State best = null;
		int tried = 0;
		for (Item option : ranked) {
			if (tried++ >= MAX_ALTERNATIVES) {
				break;
			}
			State u = t.copy();
			if (acquire(u, option, n, depth + 1, resolving) && (best == null || u.cost < best.cost)) {
				best = u;
			}
		}
		if (best == null) {
			return false;
		}
		t.inv.clear();
		t.inv.putAll(best.inv);
		t.actions.clear();
		t.actions.addAll(best.actions);
		t.cost = best.cost;
		return true;
	}

	/** Enough fuel for {@code burnTicks} of smelting: from the bag first, else the cheapest fuel to get. */
	private boolean acquireFuel(State t, int burnTicks, int depth, Set<Item> resolving) {
		List<Item> owned = new ArrayList<>(t.inv.keySet());
		owned.sort(Comparator.comparingInt((Item i) -> fuel.burnDuration(new ItemStack(i))).reversed());
		for (Item item : owned) {
			int per = fuel.burnDuration(new ItemStack(item));
			if (per <= 0 || item == Items.LAVA_BUCKET) {
				continue; // a lava bucket would come back as an empty bucket: keep it simple
			}
			while (burnTicks > 0 && take(t, item, 1) == 1) {
				burnTicks -= per;
			}
			if (burnTicks <= 0) {
				return true;
			}
		}
		State best = null;
		for (Item candidate : FUEL_CANDIDATES) {
			int per = fuel.burnDuration(new ItemStack(candidate));
			if (per <= 0 || resolving.contains(candidate)) {
				continue;
			}
			State u = t.copy();
			if (acquire(u, candidate, (burnTicks + per - 1) / per, depth + 1, resolving)
					&& (best == null || u.cost < best.cost)) {
				best = u;
			}
		}
		if (best == null) {
			problems.add(Component.literal("熔炼需要燃料（煤、木炭、原木、木板等），但弄不到"));
			return false;
		}
		t.inv.clear();
		t.inv.putAll(best.inv);
		t.actions.clear();
		t.actions.addAll(best.actions);
		t.cost = best.cost;
		return true;
	}

	/** Some block drops it, and it may be gathered at all. */
	private boolean gatherable(Item item) {
		return gatherable.computeIfAbsent(item, i -> !noGather.contains(i) && Obtainability.refusal(level, i) == null
				&& !DropAnalysis.candidates(level, breaker, i).isEmpty());
	}

	private final Map<Item, Boolean> gatherable = new HashMap<>();

	/**
	 * Make sure the plan has something to mine {@code item} with: the bare hand, a tool already in
	 * the bag (or made earlier in the plan), or else the cheapest tool that works, made first like
	 * any other ingredient (an iron pickaxe before diamonds: it used to just say "can't" unless the
	 * bot happened to own one already). The tool is kept, not used up. False if none can be had.
	 */
	private boolean ensureGatherTool(State t, Item item, int depth, Set<Item> resolving) {
		Map<Block, List<String>> candidates = DropAnalysis.candidates(level, breaker, item);
		List<Item> tools = new ArrayList<>();
		for (Block block : candidates.keySet()) {
			if (DropAnalysis.handDrops(level, breaker, block, item)
					|| DropAnalysis.toolSlotFor(level, breaker, realInventory, block, item) != DropAnalysis.NONE) {
				return true;
			}
			for (Item tool : DropAnalysis.toolItemsFor(level, breaker, block, item)) {
				if (t.have(tool) > 0) {
					return true;
				}
				if (!tools.contains(tool)) {
					tools.add(tool);
				}
			}
		}
		if (tools.isEmpty()) {
			// Only with something that can't be made (silk touch...): the player has to give it.
			problems.add(Component.literal("采").append(name(item)).append("需要：" + DropAnalysis.describeOptions(candidates)));
			return false;
		}
		State best = null;
		int tried = 0;
		for (Item tool : tools) {
			if (tried++ > MAX_ALTERNATIVES) {
				break;
			}
			State u = t.copy();
			if (acquire(u, tool, 1, depth + 1, resolving)) {
				u.add(tool, 1); // a tool is kept, not used up
				if (best == null || u.cost < best.cost) {
					best = u;
				}
			}
		}
		if (best == null) {
			problems.add(Component.literal("采").append(name(item)).append("要先有").append(name(tools.get(0))).append("，但做不出来"));
			return false;
		}
		t.become(best);
		return true;
	}

	/**
	 * Ticks to gather {@code n}, from where its sources really are: walking from one to the next,
	 * digging down to the ones that are buried (every solid block above in the column), mining
	 * each. A tree next to the bot is cheap; chests at the bottom of a mineshaft are not, even
	 * though both are "nearby". (It used to be a flat cost for anything in the loaded chunks, and
	 * dug up four buried chests for ten minutes rather than chop a tree.) With no source nearby:
	 * a trip to a biome that makes it, or NaN if none does (structures only, player-placed).
	 */
	private double gatherCost(Item item, int n, boolean quiet) {
		double rate = Math.max(1e-3, gatherRate(item));
		int blocks = (int) Math.ceil(n / rate);
		List<BlockPos> found = sites(item);
		if (found.isEmpty()) {
			if (BiomeHints.biomesFor(level, sourceBlocks.get(item)).isEmpty()) {
				if (!quiet) problems.add(Component.literal("附近没有").append(name(item)).append("，也不知道该去哪找"));
				return Double.NaN;
			}
			return GATHER_IN_BIOME + GATHER_PER_ITEM * blocks;
		}
		int m = Math.min(blocks, found.size());
		double cost = route(item, found)[m];
		if (m < blocks) {
			// More than there are nearby: assume the rest are as far apart again, and a bit worse.
			cost += (blocks - m) * (cost / m) * 1.5;
		}
		return cost;
	}

	/**
	 * Cost of visiting the first k sources, for every k: worked out once per item and plan. (It used
	 * to be redone, columns of blocks counted again, every time an option was costed: thousands of
	 * times for a dyed carpet, which froze the server for half a minute.)
	 */
	private double[] route(Item item, List<BlockPos> found) {
		return routes.computeIfAbsent(item, i -> {
			double[] prefix = new double[found.size() + 1];
			BlockPos prev = breaker.blockPosition();
			for (int k = 0; k < found.size(); k++) {
				BlockPos p = found.get(k);
				prefix[k + 1] = prefix[k] + Math.sqrt(prev.distSqr(p)) / WALK_SPEED
						+ buried(p, sourceBlocks.get(item)) * DIG_PER_BLOCK + MINE_PER_BLOCK;
				prev = p;
			}
			return prefix;
		});
	}

	private final Map<Item, double[]> routes = new HashMap<>();

	/** Expected items per broken block (capped at 1: a block giving several still takes a break). */
	private double gatherRate(Item item) {
		sites(item);
		return gatherRate.getOrDefault(item, 1.0);
	}

	private List<BlockPos> sites(Item item) {
		return sites.computeIfAbsent(item, i -> {
			Set<Block> blocks = DropAnalysis.candidates(level, breaker, i).keySet();
			sourceBlocks.put(i, blocks);
			gatherRate.put(i, Math.min(1.0, DropAnalysis.bestRate(i, blocks)));
			return BlockScanner.find(level, breaker.blockPosition(), NEARBY_CHUNKS,
					state -> blocks.contains(state.getBlock()) && DropAnalysis.dropsFrom(i, state), p -> true, MAX_SITES);
		});
	}

	/** Solid blocks above it in its column, up to the surface (other source blocks, like the rest of a trunk, don't count). */
	private int buried(BlockPos p, Set<Block> sources) {
		int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ());
		int solid = 0;
		BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
		for (int y = p.getY() + 1; y < surface && solid < 80; y++) {
			q.set(p.getX(), y, p.getZ());
			BlockState state = level.getBlockState(q);
			if (!state.getCollisionShape(level, q).isEmpty() && !sources.contains(state.getBlock())) {
				solid++;
			}
		}
		return solid;
	}

	/** Rough cost of getting one, without a full search: for ranking alternatives. */
	private double quickCost(Item item) {
		Double known = quickCosts.get(item);
		if (known != null) {
			return known;
		}
		double best = Double.MAX_VALUE;
		double gather = gatherable(item) ? gatherCost(item, 1, true) : Double.NaN;
		if (!Double.isNaN(gather)) {
			best = gather;
		}
		Map<EntityType<?>, Double> prey = huntSources(item);
		if (!prey.isEmpty()) {
			best = Math.min(best, huntCost(prey));
		}
		if (best == Double.MAX_VALUE
				&& (!RecipeIndex.craftsFor(level, item).isEmpty() || !RecipeIndex.smeltsFor(level, item).isEmpty())) {
			best = QUICK_COST_MADE; // has to be made, from who knows what
		}
		quickCosts.put(item, best);
		return best;
	}

	private final Map<Item, Double> quickCosts = new HashMap<>();

	/** {@link #quickCost} of something that can only be crafted or smelted: after anything gatherable. */
	private static final double QUICK_COST_MADE = 10_000;

	/**
	 * Mobs the bot could hunt for this item here: they drop it, and are either around already or
	 * spawn somewhere in this dimension (no waiting for blazes in the overworld).
	 */
	private Map<EntityType<?>, Double> huntSources(Item item) {
		if (noGather.contains(item) || !(breaker instanceof Player killer)) {
			return Map.of();
		}
		return huntSources.computeIfAbsent(item, i -> {
			Map<EntityType<?>, Double> all = MobDrops.sources(level, killer, i);
			Map<EntityType<?>, Double> usable = new HashMap<>();
			for (Map.Entry<EntityType<?>, Double> e : all.entrySet()) {
				if (!MobDrops.spawnBiomes(level, e.getKey()).isEmpty() || nearbyPrey(Map.of(e.getKey(), 1.0))) {
					usable.put(e.getKey(), e.getValue());
				}
			}
			if (usable.isEmpty() && !all.isEmpty()) {
				// Phantoms, wither skeletons, guardians...: they need special conditions or structures.
				net.minecraft.network.chat.MutableComponent who = Component.empty();
				int n = 0;
				for (EntityType<?> type : all.keySet()) {
					if (n++ > 0) {
						who.append("、");
					}
					who.append(type.getDescription());
					if (n == 3) {
						break;
					}
				}
				huntProblem.put(i, Component.literal("掉").append(name(i)).append("的").append(who)
						.append("不会在这里的群系里自然刷新（只在特殊条件或特定结构里出现），附近也没有"));
			}
			return usable;
		});
	}

	/** Why an item can't be hunted here, shown only if it's actually considered. */
	private final Map<Item, Component> huntProblem = new HashMap<>();

	/** Fixed cost of a hunting trip: prey in sight, animals in a known biome, or waiting for monsters. */
	private double huntCost(Map<EntityType<?>, Double> prey) {
		if (nearbyPrey(prey)) {
			return HUNT_NEARBY;
		}
		boolean animals = prey.keySet().stream().anyMatch(t -> t.getCategory() != MobCategory.MONSTER);
		return animals ? HUNT_IN_BIOME : HUNT_WAIT_FOR_SPAWN;
	}

	private boolean nearbyPrey(Map<EntityType<?>, Double> prey) {
		if (nearbyTypes == null) {
			// One look around per plan (it used to search for mobs every time a hunt was costed).
			nearbyTypes = new HashSet<>();
			for (Mob m : level.getEntitiesOfClass(Mob.class, breaker.getBoundingBox().inflate(64),
					m -> m.isAlive() && MobDrops.mayHunt(m))) {
				nearbyTypes.add(m.getType());
			}
		}
		for (EntityType<?> type : prey.keySet()) {
			if (nearbyTypes.contains(type)) {
				return true;
			}
		}
		return false;
	}

	/** Kinds of huntable mobs within 64 blocks, looked up once per plan. */
	private Set<EntityType<?>> nearbyTypes;

	private static Map<Ingredient, Integer> group(List<Ingredient> ingredients) {
		Map<Ingredient, Integer> grouped = new LinkedHashMap<>();
		for (Ingredient ingredient : ingredients) {
			grouped.merge(ingredient, 1, Integer::sum);
		}
		return grouped;
	}

	public static Component name(Item item) {
		return new ItemStack(item).getHoverName();
	}
}
