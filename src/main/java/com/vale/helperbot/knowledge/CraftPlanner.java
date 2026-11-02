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
	/** Ticks per kill of prey already around: find, chase, fight, pick up. */
	private static final double HUNT_PER_KILL = 150;
	/**
	 * Ticks per kill once the prey around is used up: animals, a walk to the next herd; monsters,
	 * waiting for them to spawn (only in the dark, a few at a time). A rare drop takes many kills,
	 * and those go at this rate: 3 iron ingots from zombies (1 in 30) is ~90 zombies, nights of
	 * it, not 90 quick kills (it used to plan that over mining iron ore).
	 */
	private static final double HUNT_PER_KILL_ANIMAL_AWAY = 400;
	private static final double HUNT_PER_KILL_SPAWNING = 900;
	private static final double CRAFT_PER_OP = 10;
	private static final double SMELT_FIXED = 40;
	/** Deep enough for e.g. diamond pickaxe <- diamond <- iron pickaxe <- iron <- stone pickaxe <- cobblestone <- wooden pickaxe <- planks <- log. */
	private static final int MAX_DEPTH = 16;
	/** Cap on explored (item, need) nodes per plan, so planning stays a few milliseconds. */
	private static final int SEARCH_BUDGET = 40_000;
	private int budget = SEARCH_BUDGET;
	/**
	 * Safety net only: planning runs on the server thread, so it never takes longer than this.
	 * Normally a plan takes tens of milliseconds (a diamond pickaxe from an empty bag: ~70 ms, the
	 * very first plan after start a few hundred while caches fill). If this is ever hit, the best
	 * plan found so far is used and it's logged: that's a bug to look into, not a normal case.
	 */
	private static final long TIME_LIMIT_NANOS = 2_000_000_000L;
	private long timeLimit = TIME_LIMIT_NANOS;
	private long deadline;
	/** Where the time went (nanoseconds), for {@link #stats()}. */
	private long tScan, tRoute, tDrops, tHunt, tBiome;
	private final Map<Item, Long> scanTimes = new HashMap<>();

	/** For measuring: plan without the time limit. */
	public CraftPlanner unlimited() {
		timeLimit = Long.MAX_VALUE / 4;
		return this;
	}

	/** Search nodes, and time spent looking at the world and at loot tables, of the last plan. */
	public String stats() {
		String slowest = scanTimes.entrySet().stream().sorted(Map.Entry.<Item, Long>comparingByValue().reversed()).limit(4)
				.map(e -> e.getKey() + "=" + e.getValue() / 1_000_000).toList().toString();
		return "节点 " + (SEARCH_BUDGET - budget) + "，扫描资源 " + scanTimes.size() + " 种 " + tScan / 1_000_000 + " ms " + slowest + "，估路线 " + tRoute / 1_000_000
				+ " ms，掉落判断 " + tDrops / 1_000_000 + " ms，猎物 " + tHunt / 1_000_000 + " ms，群系 " + tBiome / 1_000_000 + " ms";
	}
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

	/** Snapshot of an inventory as item counts. Used-up tools don't count: they won't be used. */
	public static Map<Item, Integer> counts(Inventory inventory) {
		Map<Item, Integer> counts = new HashMap<>();
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (!stack.isEmpty() && !com.vale.helperbot.path.BreakTimes.usedUp(stack)) {
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
		deadline = System.nanoTime() + timeLimit;
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
			State t = s.copy();
			t.cost += huntCost(prey, n);
			t.actions.add(new Hunt(item, n, prey));
			if (best == null || t.cost < best.cost) {
				best = t;
			}
		}
		// Recipes, cheapest-looking first (see estimate), and only those that could compete: a way
		// estimated at more than SLACK times the cheapest isn't searched at all. Exploring every
		// recipe of every ingredient used up the whole search budget even for a stone pickaxe.
		List<Object> ways = new ArrayList<>();
		Map<Object, Double> wayCost = new HashMap<>();
		int tried = 0;
		for (RecipeIndex.Craft craft : RecipeIndex.craftsFor(level, item)) {
			if (tried++ >= MAX_RECIPES) {
				break;
			}
			ways.add(craft);
			wayCost.put(craft, craftEstimate(craft, depth) * n);
		}
		for (RecipeIndex.Smelt smelt : RecipeIndex.smeltsFor(level, item)) {
			ways.add(smelt);
			wayCost.put(smelt, smeltEstimate(smelt, depth) * n);
		}
		ways.sort(Comparator.comparingDouble(wayCost::get));
		double cheapest = best == null ? Double.POSITIVE_INFINITY : best.cost - s.cost;
		for (Object way : ways) {
			cheapest = Math.min(cheapest, wayCost.get(way));
		}
		for (Object way : ways) {
			if (best != null && wayCost.get(way) > SLACK * cheapest) {
				break; // sorted: the rest look even worse
			}
			State t = s.copy();
			boolean ok = way instanceof RecipeIndex.Craft craft
					? craftInto(t, craft, n, depth, resolving, limit(best))
					: smeltInto(t, (RecipeIndex.Smelt) way, n, depth, resolving, limit(best));
			if (ok && (best == null || t.cost < best.cost)) {
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
		List<Item> ranked = promising(options, depth);
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
		long started = System.nanoTime();
		Map<Block, List<String>> candidates = DropAnalysis.candidates(level, breaker, item);
		Map<Block, List<Item>> toolsFor = new LinkedHashMap<>();
		boolean ready = false;
		for (Block block : candidates.keySet()) {
			if (DropAnalysis.handDrops(level, breaker, block, item)
					|| DropAnalysis.toolSlotFor(level, breaker, realInventory, block, item) != DropAnalysis.NONE) {
				ready = true;
				break;
			}
			toolsFor.put(block, DropAnalysis.toolItemsFor(level, breaker, block, item));
		}
		tDrops += System.nanoTime() - started;
		if (ready) {
			return true;
		}
		List<Item> tools = new ArrayList<>();
		for (List<Item> forBlock : toolsFor.values()) {
			for (Item tool : forBlock) {
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
		for (Item tool : promising(tools, depth)) {
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
		List<BlockPos> found = sites(item);
		if (found.isEmpty()) {
			long started = System.nanoTime();
			boolean nowhere = BiomeHints.biomesFor(level, sourceBlocks.get(item)).isEmpty();
			tBiome += System.nanoTime() - started;
			if (nowhere) {
				if (!quiet) problems.add(Component.literal("附近没有").append(name(item)).append("，也不知道该去哪找"));
				return Double.NaN;
			}
			int blocks = (int) Math.ceil(n / Math.max(1e-3, gatherRate(item)));
			return GATHER_IN_BIOME + GATHER_PER_ITEM * blocks;
		}
		Route r = route(item, found);
		int all = found.size();
		for (int k = 1; k <= all; k++) {
			if (r.yield[k] >= n) {
				return r.cost[k];
			}
		}
		// More than the nearby ones give: the rest at the same cost per item again, and a bit worse.
		return r.cost[all] + (n - r.yield[all]) * (r.cost[all] / Math.max(1e-3, r.yield[all])) * 1.5;
	}

	/** Visiting the first k sources, nearest first: what that costs, and how many of the item it gives. */
	private record Route(double[] cost, double[] yield) {
	}

	/**
	 * {@link Route} for every k, worked out once per item and plan. (It used to be redone, columns
	 * of blocks counted again, every time an option was costed: thousands of times for a dyed
	 * carpet, which froze the server for half a minute.) Each source counts what its own block gives:
	 * sticks are 1 per dead bush but 1 in 50 leaves (it used to count every source at the best
	 * rate, so two leaves looked like two sticks, and it went for leaves instead of making sticks).
	 */
	private Route route(Item item, List<BlockPos> found) {
		return routes.computeIfAbsent(item, i -> {
			long started = System.nanoTime();
			double[] cost = new double[found.size() + 1];
			double[] yield = new double[found.size() + 1];
			BlockPos prev = breaker.blockPosition();
			for (int k = 0; k < found.size(); k++) {
				BlockPos p = found.get(k);
				double leg = Math.sqrt(prev.distSqr(p)) / WALK_SPEED
						+ buried(p, sourceBlocks.get(item)) * DIG_PER_BLOCK + MINE_PER_BLOCK;
				// Experimental: sources in or under water count extra, as in the path planner.
				boolean wet = com.vale.helperbot.path.LocalPathfinder.isWater(level, p)
						|| com.vale.helperbot.path.LocalPathfinder.isWater(level, p.above());
				cost[k + 1] = cost[k] + (wet ? leg * com.vale.helperbot.path.LocalPathfinder.WATER_COST_FACTOR : leg);
				yield[k + 1] = yield[k] + DropAnalysis.rate(item, level.getBlockState(p).getBlock());
				prev = p;
			}
			tRoute += System.nanoTime() - started;
			return new Route(cost, yield);
		});
	}

	private final Map<Item, Route> routes = new HashMap<>();

	/** Expected items per broken block (capped at 1: a block giving several still takes a break). */
	private double gatherRate(Item item) {
		sites(item);
		return gatherRate.getOrDefault(item, 1.0);
	}

	private List<BlockPos> sites(Item item) {
		return sites.computeIfAbsent(item, i -> {
			long started = System.nanoTime();
			Set<Block> blocks = DropAnalysis.candidates(level, breaker, i).keySet();
			sourceBlocks.put(i, blocks);
			gatherRate.put(i, Math.min(1.0, DropAnalysis.bestRate(i, blocks)));
			List<BlockPos> found = BlockScanner.find(level, breaker.blockPosition(), NEARBY_CHUNKS,
					state -> blocks.contains(state.getBlock()) && DropAnalysis.dropsFrom(i, state),
					// The same test the gathering step uses: protected chunks and (strict) rules count.
					p -> com.vale.helperbot.guard.Guard.canHarvest(level, p, level.getBlockState(p)), MAX_SITES);
			long took = System.nanoTime() - started;
			tScan += took;
			scanTimes.put(i, took);
			return found;
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

	// ---- estimates ----------------------------------------------------------------------

	/**
	 * Ways estimated at more than this many times the cheapest one aren't searched. Estimates leave
	 * out what the bag already holds and stations shared between steps, hence the room.
	 */
	private static final double SLACK = 2.0;

	/**
	 * Estimated cost of one {@code item} from nothing: the cheapest of gathering it (with the tool
	 * it needs), hunting it, or making it from the estimated cost of its ingredients, worked out
	 * through the whole recipe graph once per plan. The full search uses it to try the likely-cheap
	 * ways first and skip hopeless ones. (It used to rank everything that has to be made as one
	 * flat "expensive" number, so it couldn't tell sticks-from-planks from sticks-from-leaves, and
	 * searched every recipe of every ingredient until the search budget ran out.)
	 */
	private double estimate(Item item, int depth) {
		if (!estimates.containsKey(item)) {
			estimateFrom(item);
		}
		return estimates.get(item);
	}

	private final Map<Item, Double> estimates = new HashMap<>();
	/**
	 * Fills in {@link #estimates} for {@code root} and everything it could be made from: collect
	 * that part of the recipe graph, then lower the estimates until nothing changes (recipes go in
	 * circles, e.g. wool dyed from wool of another colour, so a single recursive pass would get
	 * some of them wrong).
	 */
	private void estimateFrom(Item root) {
		List<Item> items = new ArrayList<>();
		java.util.ArrayDeque<Item> queue = new java.util.ArrayDeque<>();
		queue.add(root);
		for (Item extra : List.of(Items.CRAFTING_TABLE, Items.FURNACE)) {
			queue.add(extra);
		}
		while (!queue.isEmpty()) {
			Item item = queue.poll();
			if (estimates.containsKey(item)) {
				continue;
			}
			estimates.put(item, Double.POSITIVE_INFINITY);
			items.add(item);
			if (item == Items.WATER_BUCKET) {
				queue.add(Items.BUCKET);
			}
			for (RecipeIndex.Craft craft : recipes(item)) {
				for (Ingredient ingredient : craft.ingredients()) {
					queue.addAll(RecipeIndex.options(ingredient));
				}
			}
			for (RecipeIndex.Smelt smelt : RecipeIndex.smeltsFor(level, item)) {
				queue.addAll(RecipeIndex.options(smelt.input()));
			}
			if (gatherable(item)) {
				queue.addAll(gatherTools(item));
			}
		}
		for (int round = 0; round < 64; round++) {
			boolean changed = false;
			for (Item item : items) {
				double v = localEstimate(item);
				if (v < estimates.get(item) - 1e-6) {
					estimates.put(item, v);
					changed = true;
				}
			}
			if (!changed) {
				break;
			}
		}
	}

	/** One item's estimate from the current estimates of what it's made from. */
	private double localEstimate(Item item) {
		double best = Double.POSITIVE_INFINITY;
		if (gatherable(item)) {
			double gather = gatherBase.computeIfAbsent(item, i -> {
				double g = gatherCost(i, 1, true);
				return Double.isNaN(g) ? Double.POSITIVE_INFINITY : g;
			});
			List<Item> tools = gatherTools(item);
			double tool = tools.isEmpty() && tools != NO_PLAIN_TOOL ? 0 : Double.POSITIVE_INFINITY;
			for (Item t : tools) {
				tool = Math.min(tool, estimates.getOrDefault(t, Double.POSITIVE_INFINITY));
			}
			best = gather + tool;
		}
		if (item == Items.WATER_BUCKET) {
			best = Math.min(best, estimates.getOrDefault(Items.BUCKET, Double.POSITIVE_INFINITY) + FILL_FIXED + FILL_PER_ITEM);
		}
		Map<EntityType<?>, Double> prey = huntSources(item);
		if (!prey.isEmpty()) {
			best = Math.min(best, huntCost(prey, 1));
		}
		for (RecipeIndex.Craft craft : recipes(item)) {
			best = Math.min(best, craftEstimate(craft, 0));
		}
		for (RecipeIndex.Smelt smelt : RecipeIndex.smeltsFor(level, item)) {
			best = Math.min(best, smeltEstimate(smelt, 0));
		}
		return best;
	}

	private final Map<Item, Double> gatherBase = new HashMap<>();

	/** The recipes the search would try for this item. */
	private List<RecipeIndex.Craft> recipes(Item item) {
		List<RecipeIndex.Craft> all = RecipeIndex.craftsFor(level, item);
		return all.size() > MAX_RECIPES ? all.subList(0, MAX_RECIPES) : all;
	}

	/**
	 * Tools to make before gathering it; empty if the hand or something in the bag will do;
	 * {@link #NO_PLAIN_TOOL} if only something that can't be made does (silk touch: the iron ore
	 * block itself). That last one used to come back empty too, so the estimate read it as "by
	 * hand": smelting iron ore looked cheapest, failed in the full search, and pushed the real way
	 * (raw iron) out of range, leaving hunting zombies for ingots.
	 */
	private List<Item> gatherTools(Item item) {
		return gatherTools.computeIfAbsent(item, i -> {
			long started = System.nanoTime();
			List<Item> tools = new ArrayList<>();
			boolean ready = false;
			for (Block block : DropAnalysis.candidates(level, breaker, i).keySet()) {
				if (DropAnalysis.handDrops(level, breaker, block, i)
						|| DropAnalysis.toolSlotFor(level, breaker, realInventory, block, i) != DropAnalysis.NONE) {
					ready = true;
					tools.clear();
					break;
				}
				for (Item tool : DropAnalysis.toolItemsFor(level, breaker, block, i)) {
					if (!tools.contains(tool)) {
						tools.add(tool);
					}
				}
			}
			tDrops += System.nanoTime() - started;
			return !ready && tools.isEmpty() ? NO_PLAIN_TOOL : tools;
		});
	}

	/** See {@link #gatherTools}: compared by identity. */
	private static final List<Item> NO_PLAIN_TOOL = java.util.Collections.unmodifiableList(new ArrayList<>());

	private final Map<Item, List<Item>> gatherTools = new HashMap<>();

	/** Per item made, from the current estimates of the ingredients. */
	private double craftEstimate(RecipeIndex.Craft craft, int depth) {
		double sum = CRAFT_PER_OP;
		for (Map.Entry<Ingredient, Integer> e : group(craft.ingredients()).entrySet()) {
			sum += e.getValue() * cheapestOption(e.getKey(), depth);
		}
		return sum / craft.resultCount();
	}

	/** Per item made (fuel left out: small next to the rest). */
	private double smeltEstimate(RecipeIndex.Smelt smelt, int depth) {
		return (cheapestOption(smelt.input(), depth) + SMELT_FIXED + smelt.cookingTime()) / smelt.resultCount();
	}

	private double cheapestOption(Ingredient ingredient, int depth) {
		double best = Double.POSITIVE_INFINITY;
		for (Item option : RecipeIndex.options(ingredient)) {
			best = Math.min(best, estimate(option, depth + 1));
		}
		return best;
	}

	/**
	 * Of these alternatives, the ones worth a full search, cheapest-looking first: within
	 * {@link #SLACK} of the cheapest estimate (all of them, in the given order, if none can be
	 * estimated).
	 */
	private List<Item> promising(List<Item> options, int depth) {
		Map<Item, Double> est = new HashMap<>();
		double cheapest = Double.POSITIVE_INFINITY;
		for (Item option : options) {
			double e = estimate(option, depth + 1);
			est.put(option, e);
			cheapest = Math.min(cheapest, e);
		}
		if (Double.isInfinite(cheapest)) {
			return options;
		}
		List<Item> ranked = new ArrayList<>();
		for (Item option : options) {
			if (est.get(option) <= SLACK * cheapest) {
				ranked.add(option);
			}
		}
		ranked.sort(Comparator.comparingDouble(est::get));
		return ranked;
	}

	/**
	 * Mobs the bot could hunt for this item here: they drop it, and are either around already or
	 * spawn somewhere in this dimension (no waiting for blazes in the overworld).
	 */
	private Map<EntityType<?>, Double> huntSources(Item item) {
		if (noGather.contains(item) || !(breaker instanceof Player killer)) {
			return Map.of();
		}
		Map<EntityType<?>, Double> known = huntSources.get(item);
		if (known != null) {
			return known;
		}
		long started = System.nanoTime();
		Map<EntityType<?>, Double> found = findHuntSources(item, killer);
		huntSources.put(item, found);
		tHunt += System.nanoTime() - started;
		return found;
	}

	private Map<EntityType<?>, Double> findHuntSources(Item item, Player killer) {
		Map<EntityType<?>, Double> all = MobDrops.sources(level, killer, item);
		Map<EntityType<?>, Double> usable = new HashMap<>();
		for (Map.Entry<EntityType<?>, Double> e : all.entrySet()) {
			if (!MobDrops.spawnBiomes(level, e.getKey()).isEmpty() || nearbyPrey(Map.of(e.getKey(), 1.0)) > 0) {
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
			huntProblem.put(item, Component.literal("掉").append(name(item)).append("的").append(who)
					.append("不会在这里的群系里自然刷新（只在特殊条件或特定结构里出现），附近也没有"));
		}
		return usable;
	}

	/** Why an item can't be hunted here, shown only if it's actually considered. */
	private final Map<Item, Component> huntProblem = new HashMap<>();

	/**
	 * Ticks to hunt {@code n} of something: the trip (prey in sight, animals in a known biome, or
	 * waiting for monsters), then the kills it takes at its drop rate, the ones around first.
	 */
	private double huntCost(Map<EntityType<?>, Double> prey, double n) {
		double perKill = Math.min(1.0, prey.values().stream().mapToDouble(Double::doubleValue).max().orElse(1.0));
		double kills = n / Math.max(1e-3, perKill);
		int around = nearbyPrey(prey);
		boolean animals = prey.keySet().stream().anyMatch(t -> t.getCategory() != MobCategory.MONSTER);
		double trip = around > 0 ? HUNT_NEARBY : animals ? HUNT_IN_BIOME : HUNT_WAIT_FOR_SPAWN;
		double near = Math.min(kills, around);
		return trip + near * HUNT_PER_KILL + (kills - near) * (animals ? HUNT_PER_KILL_ANIMAL_AWAY : HUNT_PER_KILL_SPAWNING);
	}

	/** How many of this prey are within 64 blocks. */
	private int nearbyPrey(Map<EntityType<?>, Double> prey) {
		if (nearbyTypes == null) {
			// One look around per plan (it used to search for mobs every time a hunt was costed).
			nearbyTypes = new HashMap<>();
			for (Mob m : level.getEntitiesOfClass(Mob.class, breaker.getBoundingBox().inflate(64),
					m -> m.isAlive() && MobDrops.mayHunt(m))) {
				nearbyTypes.merge(m.getType(), 1, Integer::sum);
			}
		}
		int n = 0;
		for (EntityType<?> type : prey.keySet()) {
			n += nearbyTypes.getOrDefault(type, 0);
		}
		return n;
	}

	/** Huntable mobs within 64 blocks, by kind, looked up once per plan. */
	private Map<EntityType<?>, Integer> nearbyTypes;

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
