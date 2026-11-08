package com.vale.helperbot.knowledge;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import com.vale.helperbot.path.Air;
import com.vale.helperbot.path.BreakTimes;
import com.vale.helperbot.path.Escape;
import com.vale.helperbot.path.LocalPathfinder;
import com.vale.helperbot.path.MineSpot;
import com.vale.helperbot.path.Mode;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.ItemTags;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
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
import net.minecraft.world.level.levelgen.structure.Structure;

import java.util.ArrayList;
import java.util.Collection;
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
	public record Plan(List<Action> actions, Set<Component> problems, boolean timedOut, double ticks) {
		public boolean possible() {
			return actions != null;
		}
	}

	// All costs are real game ticks: what walking, mining, cooking and fighting take is worked out
	// from the game (see #ticks, #breakTicks, #killTicks, #stationUse). What can't be known before
	// going is said in blocks, and turned into ticks at the bot's real speed.
	/** With nothing in the loaded chunks: about how far off the next place that has it is. */
	private static final int TRIP_BLOCKS = 500;
	/** Loaded chunks around the bot looked through for sources. */
	private static final int NEARBY_CHUNKS = 4;
	/** Sources looked at when estimating a trip (the nearest ones). */
	private static final int MAX_SITES = 64;
	/** From one source to the next, where they are, when it doesn't know them yet. */
	private static final int BETWEEN_SOURCES_BLOCKS = 8;
	/** Going to water, when it isn't known where any is. */
	private static final int WATER_TRIP_BLOCKS = 100;
	/** Filling one bucket, once there (see FetchWaterTask). */
	private static final double FILL_PER_ITEM = 5;
	/** A drop flying into the bag, from the block or the mob. */
	private static final double PICKUP_TICKS = 10;
	/** One craft (see CraftStep: every 5 ticks). */
	private static final double CRAFT_PER_OP = 5;
	/** Putting a station down. */
	private static final double PLACE_TICKS = 5;
	/** Running after what it hit, per kill. */
	private static final int CHASE_BLOCKS = 12;
	/** Sources this close are one vein. */
	private static final int VEIN_BLOCKS = 4;
	/** To the next herd, once the animals around are used up. */
	private static final int NEXT_HERD_BLOCKS = 100;
	/**
	 * A monster turning up, once the ones around are used up: not known in advance, the game spawns
	 * them by chance, a few at a time, in the dark. A rare drop takes many kills, and those go at
	 * this rate: 3 iron ingots from zombies is ~120 zombies, nights of it, not 120 quick kills.
	 */
	private static final double SPAWN_WAIT_PER_KILL = 900;
	/** Monsters spawn in the open from about this time of day to about the next. */
	private static final int DARK_FROM = 13_000, DARK_TO = 23_000;
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

	private final Mode mode;
	private final ServerLevel level;
	private final Entity breaker;
	private final Inventory realInventory;
	/** Items whose gathering already failed this task: don't plan to gather them again. */
	private final Set<Item> noGather;
	/** Items whose hunting already failed this task: hunting them is out, gathering isn't. */
	private final Set<Item> noHunt;
	private final FuelValues fuel;
	private final Set<Component> problems = new LinkedHashSet<>();
	/** Source blocks found nearby, nearest first. */
	private final Map<Item, List<BlockPos>> sites = new HashMap<>();
	private final Map<Item, Set<Block>> sourceBlocks = new HashMap<>();
	private final Map<Item, Map<EntityType<?>, Double>> huntSources = new HashMap<>();
	/** Expected items per broken block. */
	private final Map<Item, Double> gatherRate = new HashMap<>();

	public CraftPlanner(ServerLevel level, Entity breaker, Inventory realInventory, Set<Item> noGather) {
		this(level, breaker, realInventory, noGather, Set.of(), Mode.DEFAULT);
	}

	public CraftPlanner(ServerLevel level, Entity breaker, Inventory realInventory, Set<Item> noGather, Mode mode) {
		this(level, breaker, realInventory, noGather, Set.of(), mode);
	}

	public CraftPlanner(ServerLevel level, Entity breaker, Inventory realInventory, Set<Item> noGather, Set<Item> noHunt, Mode mode) {
		this.mode = mode;
		this.level = level;
		this.breaker = breaker;
		this.realInventory = realInventory;
		this.noGather = noGather;
		this.noHunt = noHunt;
		this.fuel = level.fuelValues();
	}

	/** Walking {@code blocks}, at the speed it goes. */
	private double ticks(double blocks) {
		return blocks / mode.speed();
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
			return new Plan(null, problems, timedOut, Double.NaN);
		}
		return new Plan(state.actions, problems, timedOut, state.cost);
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

		/** Better than {@code other} (which may be null). */
		boolean beats(State other) {
			return other == null || cost < other.cost;
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
		if (resolving.contains(item) || deadEnd(item, resolving)) {
			return false; // a loop (e.g. iron ingot <-> iron block), or known not to work
		}
		if (depth > MAX_DEPTH || --budget < 0) {
			cutShort++;
			return false; // too deep, or the search has had its share: keep what we have
		}
		if (System.nanoTime() > deadline) {
			timedOut = true;
			cutShort++;
			return false;
		}
		int cutsBefore = cutShort;
		resolving.add(item);
		State best = null;

		if (gatherable(item)) {
			State t = s.copy();
			// The right tool first, if it takes one the bot doesn't have (make it, like any ingredient).
			if (ensureGatherTool(t, item, depth, resolving)) {
				double trip = gatherCost(item, n, !RecipeIndex.craftsFor(level, item).isEmpty());
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
				t.cost += ticks(WATER_TRIP_BLOCKS) + FILL_PER_ITEM * n;
				t.actions.add(new Fill(n));
				if (t.beats(best)) {
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
			if (t.beats(best)) {
				best = t;
			}
		}
		// Recipes, cheapest-looking first (see estimate). The ones that can't compete aren't
		// searched: what a way takes at the very least (see #floor) already more than what's in hand.
		List<Object> ways = new ArrayList<>();
		Map<Object, Double> wayCost = new HashMap<>();
		int tried = 0;
		for (RecipeIndex.Craft craft : RecipeIndex.craftsFor(level, item)) {
			if (tried++ >= MAX_RECIPES) {
				break;
			}
			if (swapsWithin(craft)) {
				continue;
			}
			ways.add(craft);
			wayCost.put(craft, craftWay(s, craft, n));
		}
		for (RecipeIndex.Smelt smelt : RecipeIndex.smeltsFor(level, item)) {
			ways.add(smelt);
			wayCost.put(smelt, smeltWay(s, smelt, n));
		}
		ways.sort(Comparator.comparingDouble(wayCost::get));
		for (Object way : ways) {
			// Never ruled out by what it only looks like: the estimate is an average, and an average
			// above twice the best once skipped smelting gold, the way that worked.
			if (best != null && wayFloor(s, way, n) >= best.cost - s.cost) {
				continue;
			}
			State t = s.copy();
			boolean ok = way instanceof RecipeIndex.Craft craft
					? craftInto(t, craft, n, depth, resolving, limit(s, best))
					: smeltInto(t, (RecipeIndex.Smelt) way, n, depth, resolving, limit(s, best));
			if (ok && t.beats(best)) {
				best = t;
			}
		}
		resolving.remove(item);
		if (best == null) {
			if (ways.isEmpty() && prey.isEmpty() && !huntProblem.containsKey(item) && !gatherable(item)
					&& Obtainability.refusal(level, item) == null) {
				problems.add(Component.literal("不知道怎么弄到").append(name(item)).append("：挖、打、做都得不到"));
			}
			if (cutShort == cutsBefore) {
				// Only a real dead end: one that was cut short (too deep, out of budget) may work elsewhere.
				deadEnds.computeIfAbsent(item, i -> new ArrayList<>()).add(new HashSet<>(resolving));
			}
			return false;
		}
		s.become(best);
		return true;
	}

	/**
	 * It didn't work with fewer things off limits than now: it won't now either. With estimates
	 * only sorting, every alternative of a failing item gets its try, and without this the same
	 * dead ends were searched over and over until the search budget was gone.
	 */
	private boolean deadEnd(Item item, Set<Item> resolving) {
		List<Set<Item>> known = deadEnds.get(item);
		if (known != null) {
			for (Set<Item> offLimits : known) {
				if (resolving.containsAll(offLimits)) {
					return true;
				}
			}
		}
		return false;
	}

	private final Map<Item, List<Set<Item>>> deadEnds = new HashMap<>();
	/** Searches stopped for depth, budget or time so far: a failure under one of those proves nothing. */
	private int cutShort;

	/**
	 * Branch and bound: a way that already costs more than the best one found can stop there.
	 * Costs only ever add up, so this never loses the cheapest plan, it just skips exploring the
	 * rest of ways that can't win.
	 */
	private static double limit(State from, State best) {
		return best == null ? Double.MAX_VALUE : best.cost;
	}


	private boolean craftInto(State t, RecipeIndex.Craft craft, int n, int depth, Set<Item> resolving, double limit) {
		int times = (n + craft.resultCount() - 1) / craft.resultCount();
		if (craft.needsTable() && !keepStation(t, Items.CRAFTING_TABLE, depth, resolving)) {
			return false;
		}
		for (Map.Entry<Ingredient, Integer> e : group(craft.ingredients()).entrySet()) {
			if (t.cost >= limit) {
				return false;
			}
			if (!acquireIngredient(t, e.getKey(), e.getValue() * times, depth, resolving)) {
				return false;
			}
		}
		t.cost += CRAFT_PER_OP * times + (craft.needsTable() ? stationUse(Items.CRAFTING_TABLE) : 0);
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
		t.cost += stationUse(Items.FURNACE) + (double) inputs * smelt.cookingTime();
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
		// white last). The rest are for when those turn out not to work.
		State best = null;
		int tried = 0;
		Set<Item> family = options.size() > 1 ? Set.copyOf(options) : null;
		if (family != null) {
			choosing.push(family);
		}
		try {
			for (Item option : byEstimate(options)) {
				if (enough(t, best, tried, option, n)) {
					break;
				}
				tried++;
				State u = t.copy();
				if (acquire(u, option, n, depth + 1, resolving) && u.beats(best)) {
					best = u;
				}
			}
		} finally {
			if (family != null) {
				choosing.pop();
			}
		}
		if (best == null) {
			return false;
		}
		t.become(best);
		return true;
	}

	/**
	 * Families of things it's choosing one of right now (any wool, any bed, any planks): while it
	 * is, none of them is made out of another one of them. Blue wool for "any wool" is made from
	 * white wool and blue dye, not dyed from red wool, itself dyed from green...: that went round all
	 * sixteen colours until the search budget was gone, and a white bed couldn't be planned. A
	 * family of one isn't one: iron ingots from an iron block are fine.
	 */
	private final java.util.ArrayDeque<Set<Item>> choosing = new java.util.ArrayDeque<>();

	/** This recipe makes one of a family being chosen from out of another one of it. */
	private boolean swapsWithin(RecipeIndex.Craft craft) {
		for (Set<Item> family : choosing) {
			if (!family.contains(craft.result())) {
				continue;
			}
			for (Ingredient ingredient : craft.ingredients()) {
				int shared = 0;
				for (Item option : RecipeIndex.options(ingredient)) {
					if (family.contains(option) && ++shared >= 2) {
						return true;
					}
				}
			}
		}
		return false;
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
					&& u.beats(best)) {
				best = u;
			}
		}
		if (best == null) {
			problems.add(Component.literal("熔炼需要燃料（煤、木炭、原木、木板等），但弄不到"));
			return false;
		}
		t.become(best);
		return true;
	}

	/** Some block drops it, and it may be gathered at all. */
	private boolean gatherable(Item item) {
		return gatherable.computeIfAbsent(item, i -> {
			String restricted = com.vale.helperbot.guard.Restrictions.refusal(i);
			if (restricted != null) {
				problems.add(Component.literal(restricted)); // say so, so it isn't taken for "can't find any"
				return false;
			}
			return !noGather.contains(i) && Obtainability.refusal(level, i) == null
					&& !DropAnalysis.candidates(level, breaker, i).isEmpty();
		});
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
		for (Block block : found(item, candidates.keySet())) {
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
		for (Item tool : byEstimate(tools)) {
			if (enough(t, best, tried, tool, 1)) {
				break;
			}
			tried++;
			State u = t.copy();
			if (acquire(u, tool, 1, depth + 1, resolving)) {
				u.add(tool, 1); // a tool is kept, not used up
				if (u.beats(best)) {
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
		Route r = found.isEmpty() ? null : route(item, found);
		if (r == null || r.cost.length == 1) {
			long started = System.nanoTime();
			boolean nowhere = BiomeHints.biomesFor(level, sourceBlocks.get(item)).isEmpty();
			tBiome += System.nanoTime() - started;
			// What a structure is built with: only what nothing else gives. Gold blocks are made from
			// ingots, not taken off ruined portals two at a time.
			Set<Holder<Structure>> built = nowhere && onlyInStructures(item) ? StructureIndex.holding(level, sourceBlocks.get(item)) : Set.of();
			if (nowhere && built.isEmpty()) {
				if (!quiet) problems.add(Component.literal("附近没有").append(name(item)).append("，也不知道该去哪找"));
				return Double.NaN;
			}
			return ticks(nowhere ? StructureIndex.typicalDistance(level, built) : TRIP_BLOCKS) + Math.ceil(n / Math.max(1e-3, gatherRate(item))) * perSource(item);
		}
		int all = r.cost.length - 1;
		for (int k = 1; k <= all; k++) {
			if (r.yield[k] >= n) {
				return r.cost[k];
			}
		}
		// More than the nearby ones give: the rest at the same cost per item, somewhere else if
		// that's all there is around here.
		double rest = (n - r.yield[all]) * (r.cost[all] / Math.max(1e-3, r.yield[all]));
		return r.cost[all] + rest + (found.size() < MAX_SITES ? ticks(TRIP_BLOCKS) : 0);
	}

	/**
	 * One source of it, somewhere it doesn't know yet: the way from the last one, mining it. The
	 * way between them is by how many a chunk of the richest biome has (one in 256 columns: 16
	 * blocks apart, the whole surface: next to each other).
	 */
	private double perSource(Item item) {
		double mine = Double.POSITIVE_INFINITY;
		BlockPos here = breaker.blockPosition();
		for (Block block : sourceBlocks.get(item)) {
			mine = Math.min(mine, breakTicks(item, here, block.defaultBlockState()));
		}
		double perChunk = BiomeHints.richest(level, sourceBlocks.get(item));
		double apart = perChunk > 0 ? Math.max(1, Math.sqrt(256 / perChunk)) : BETWEEN_SOURCES_BLOCKS;
		return ticks(apart) + (Double.isInfinite(mine) ? 0 : mine) + PICKUP_TICKS;
	}

	/**
	 * Ticks to mine this block once, with the tool it would use for the job: the one in the bag, or
	 * the first that gets {@code item} if the bag has none (one the plan makes first).
	 */
	private double breakTicks(Item item, BlockPos p, BlockState state) {
		int slot = BreakTimes.bestToolSlot(realInventory, state);
		ItemStack tool = slot < 0 ? ItemStack.EMPTY : realInventory.getItem(slot);
		if (state.requiresCorrectToolForDrops() && !tool.isCorrectToolForDrops(state)) {
			List<Item> tools = gatherTools(item);
			if (!tools.isEmpty()) {
				tool = new ItemStack(tools.get(0));
			}
		}
		int ticks = BreakTimes.estimateTicksWith(tool, level, p, state);
		return ticks == Integer.MAX_VALUE ? Double.POSITIVE_INFINITY : ticks + LocalPathfinder.BREAK_OVERHEAD;
	}

	/**
	 * A station used once: putting it down, and picking it up again if it can (without the right
	 * tool it stays where it is).
	 */
	private double stationUse(Item station) {
		BlockState state = Block.byItem(station).defaultBlockState();
		int slot = BreakTimes.bestToolSlot(realInventory, state);
		ItemStack tool = slot < 0 ? ItemStack.EMPTY : realInventory.getItem(slot);
		if (state.requiresCorrectToolForDrops() && !tool.isCorrectToolForDrops(state)) {
			return PLACE_TICKS;
		}
		return PLACE_TICKS + BreakTimes.estimateTicksWith(tool, level, breaker.blockPosition(), state) + PICKUP_TICKS;
	}

	/** Visiting the first k sources, cheapest first: what that costs, and how many of the item it gives. */
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
			// What each costs once there. Under water that's much more than the walk: it goes last
			// even if it's nearest, and if it can't be done on a breath it isn't a source at all.
			Air.Profile air = Air.profile((LivingEntity) breaker, true, 0, breaker instanceof Player p ? Air.sprintBlocks(p) : 0);
			Escape escape = new Escape(level, realInventory, air).fromAbove();
			Map<BlockPos, Double> work = new HashMap<>();
			Map<BlockPos, Double> digDown = new HashMap<>();
			for (BlockPos p : found) {
				double w = mineCost(item, p, found.size(), air, escape);
				if (!Double.isInfinite(w)) {
					work.put(p, w);
					digDown.put(p, Math.min(w, buried(item, p)));
				}
			}
			BlockPos start = breaker.blockPosition();
			List<BlockPos> order = new ArrayList<>(work.keySet());
			order.sort(Comparator.comparingDouble(p -> ticks(Math.sqrt(start.distSqr(p))) + work.get(p)));
			double[] cost = new double[order.size() + 1];
			double[] yield = new double[order.size() + 1];
			BlockPos prev = start;
			for (int k = 0; k < order.size(); k++) {
				BlockPos p = order.get(k);
				// A vein is dug down to once: from the block next to it, there's no way down to dig again.
				double vein = k > 0 && prev.distSqr(p) <= VEIN_BLOCKS * VEIN_BLOCKS ? digDown.getOrDefault(p, 0.0) : 0;
				cost[k + 1] = cost[k] + ticks(Math.sqrt(prev.distSqr(p))) + work.get(p) - vein + PICKUP_TICKS;
				yield[k + 1] = yield[k] + DropAnalysis.rate(item, level.getBlockState(p).getBlock());
				prev = p;
			}
			tRoute += System.nanoTime() - started;
			return new Route(cost, yield);
		});
	}

	/** Ticks to mine one source, once there; under water its share of all {@code blocks} (see Air#diving). */
	private double mineCost(Item item, BlockPos p, int blocks, Air.Profile air, Escape escape) {
		if (!com.vale.helperbot.guard.Guard.ofTheSea(level.getBlockState(p)) && breaker instanceof Player player
				&& !MineSpot.headOut(level, p, MineSpot.reach(player), player.getEyeHeight())) {
			return Double.POSITIVE_INFINITY; // it won't do that (see Guard#ofTheSea)
		}
		double work = buried(item, p) + breakTicks(item, p, level.getBlockState(p));
		if (Double.isInfinite(work)) {
			return Double.POSITIVE_INFINITY;
		}
		BlockPos feet = LocalPathfinder.isWater(level, p) ? p : p.above();
		if (!LocalPathfinder.isWater(level, feet) || Air.breathable(level, feet)) {
			return work;
		}
		// (Out of reach, by the estimate? A way round it is the search's to find: see MineCost#cost.)
		double up = escape.ticks(feet);
		return Air.diving(air, blocks, Air.workUnderWater(work, Air.digPenalty(level, feet)), Double.isInfinite(up) ? 0 : up) / blocks;
	}

	private final Map<Item, Route> routes = new HashMap<>();

	/** Expected items per broken block. */
	private double gatherRate(Item item) {
		sites(item);
		return gatherRate.getOrDefault(item, 1.0);
	}

	private List<BlockPos> sites(Item item) {
		return sites.computeIfAbsent(item, i -> {
			long started = System.nanoTime();
			Set<Block> blocks = DropAnalysis.candidates(level, breaker, i).keySet();
			sourceBlocks.put(i, blocks);
			gatherRate.put(i, DropAnalysis.bestRate(i, blocks));
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

	/**
	 * Ticks to dig down to it from the surface: every solid block above it in its column, what each
	 * really takes, and the step down (other source blocks, like the rest of a trunk, don't count).
	 */
	private double buried(Item item, BlockPos p) {
		Set<Block> sources = sourceBlocks.get(item);
		int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ());
		int solid = 0;
		double ticks = 0;
		BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
		for (int y = p.getY() + 1; y < surface && solid < 80; y++) {
			q.set(p.getX(), y, p.getZ());
			BlockState state = level.getBlockState(q);
			if (!state.getCollisionShape(level, q).isEmpty() && !sources.contains(state.getBlock())) {
				solid++;
				double dig = breakTicks(item, q, state);
				ticks += (Double.isInfinite(dig) ? 0 : dig) + ticks(1);
			}
		}
		return ticks;
	}

	// ---- estimates ----------------------------------------------------------------------


	/**
	 * Estimated cost of {@code item} from nothing, in two parts: what each one takes, and what's
	 * needed once however many it is (a tool, a station, the trip there). Spread over what comes out
	 * of a recipe, only the first part divides: nine raw iron from one block still take a whole
	 * pickaxe, not a ninth of one. (As one number, iron looked cheaper than the stone pickaxe it
	 * takes, and the planner went for an iron pickaxe to mine iron, and then for zombies.)
	 */
	private record Est(double unit, double once) {
		static final Est NONE = new Est(Double.POSITIVE_INFINITY, 0);

		double of(double n) {
			return unit * n + once;
		}

		double total() {
			return of(1);
		}
	}

	/**
	 * The cheapest of gathering it (with the tool it needs), hunting it, or making it from the
	 * estimates of its ingredients, worked out through the whole recipe graph once per plan. The
	 * full search uses it to try the likely-cheap ways first and skip hopeless ones. (It used to rank
	 * everything that has to be made as one flat "expensive" number, so it couldn't tell
	 * sticks-from-planks from sticks-from-leaves, and searched every recipe of every ingredient until
	 * the search budget ran out.)
	 */
	private Est estimate(Item item) {
		if (!estimates.containsKey(item)) {
			estimateFrom(item);
		}
		return estimates.get(item);
	}

	private final Map<Item, Est> estimates = new HashMap<>();
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
		queue.add(Items.CRAFTING_TABLE);
		queue.add(Items.FURNACE);
		queue.addAll(FUEL_CANDIDATES);
		while (!queue.isEmpty()) {
			Item item = queue.poll();
			if (estimates.containsKey(item)) {
				continue;
			}
			estimates.put(item, Est.NONE);
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
				Est v = localEstimate(item);
				if (v.total() < estimates.get(item).total() - 1e-6) {
					estimates.put(item, v);
					changed = true;
				}
			}
			if (!changed) {
				break;
			}
		}
	}

	private static Est cheaper(Est a, Est b) {
		return b.total() < a.total() ? b : a;
	}

	/** One item's estimate from the current estimates of what it's made from. */
	private Est localEstimate(Item item) {
		Est best = Est.NONE;
		if (gatherable(item)) {
			Est gather = gatherBase.computeIfAbsent(item, i -> split(gatherCost(i, 1, true), gatherCost(i, SPLIT_AT, true)));
			List<Item> tools = gatherTools(item);
			double tool = tools.isEmpty() && tools != NO_PLAIN_TOOL ? 0 : Double.POSITIVE_INFINITY;
			for (Item t : tools) {
				tool = Math.min(tool, estimates.getOrDefault(t, Est.NONE).total());
			}
			best = new Est(gather.unit(), gather.once() + tool);
		}
		if (item == Items.WATER_BUCKET) {
			Est bucket = estimates.getOrDefault(Items.BUCKET, Est.NONE);
			best = cheaper(best, new Est(bucket.unit() + FILL_PER_ITEM, bucket.once() + ticks(WATER_TRIP_BLOCKS)));
		}
		Map<EntityType<?>, Double> prey = huntSources(item);
		if (!prey.isEmpty()) {
			best = cheaper(best, split(huntCost(prey, 1), huntCost(prey, SPLIT_AT)));
		}
		for (RecipeIndex.Craft craft : recipes(item)) {
			best = cheaper(best, craftEstimate(craft));
		}
		for (RecipeIndex.Smelt smelt : RecipeIndex.smeltsFor(level, item)) {
			best = cheaper(best, smeltEstimate(smelt));
		}
		return best;
	}

	/** How many are costed to tell what one more takes from what it takes to start. */
	private static final int SPLIT_AT = 17;

	/** {@link Est} from the cost of one and of {@link #SPLIT_AT}. */
	private static Est split(double one, double many) {
		if (Double.isNaN(one) || Double.isNaN(many)) {
			return Est.NONE;
		}
		// Never more than the first one took: many can cost far more each (what's near runs out),
		// and an estimate that starts below nothing would rule out everything else.
		double unit = Math.clamp((many - one) / (SPLIT_AT - 1), 0, one);
		return new Est(unit, one - unit);
	}

	private final Map<Item, Est> gatherBase = new HashMap<>();

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
			for (Block block : found(i, DropAnalysis.candidates(level, breaker, i).keySet())) {
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

	/**
	 * Of the blocks that drop it, those it would really find: around it, or made by the world
	 * (biomes, see BiomeHints). All of them if none is. Redstone wire gives redstone to a bare hand,
	 * but nobody's laid any in the wild: going by it, redstone looked like it took no pickaxe, and
	 * the ore it found then couldn't be mined.
	 */
	private Collection<Block> found(Item item, Collection<Block> candidates) {
		Set<Block> there = new HashSet<>();
		for (BlockPos p : sites(item)) {
			there.add(level.getBlockState(p).getBlock());
		}
		for (Block block : candidates) {
			if (!BiomeHints.perChunk(level, List.of(block)).isEmpty()) {
				there.add(block);
			}
		}
		there.retainAll(candidates);
		return there.isEmpty() ? candidates : there;
	}

	/** See {@link #gatherTools}: compared by identity. */
	private static final List<Item> NO_PLAIN_TOOL = java.util.Collections.unmodifiableList(new ArrayList<>());

	private final Map<Item, List<Item>> gatherTools = new HashMap<>();

	/** A station to have: nothing extra if it's the bag's already. */
	private double stationEstimate(Item station) {
		return estimates.getOrDefault(station, Est.NONE).total() + stationUse(station);
	}

	/** Per item made, from the current estimates of the ingredients. */
	private Est craftEstimate(RecipeIndex.Craft craft) {
		double unit = CRAFT_PER_OP, once = craft.needsTable() ? stationEstimate(Items.CRAFTING_TABLE) : 0;
		for (Map.Entry<Ingredient, Integer> e : group(craft.ingredients()).entrySet()) {
			Est option = cheapestOption(e.getKey());
			unit += e.getValue() * option.unit();
			once += option.once();
		}
		return new Est(unit / craft.resultCount(), once);
	}

	/** Per item made. */
	private Est smeltEstimate(RecipeIndex.Smelt smelt) {
		Est input = cheapestOption(smelt.input());
		double unit = input.unit() + smelt.cookingTime() * (1 + fuelPerTick());
		return new Est(unit / smelt.resultCount(), input.once() + stationEstimate(Items.FURNACE));
	}

	/** What a tick of burning takes to get, by the cheapest fuel. */
	private double fuelPerTick() {
		double best = Double.POSITIVE_INFINITY;
		for (Item candidate : FUEL_CANDIDATES) {
			int burn = fuel.burnDuration(new ItemStack(candidate));
			if (burn > 0) {
				best = Math.min(best, estimates.getOrDefault(candidate, Est.NONE).unit() / burn);
			}
		}
		return Double.isInfinite(best) ? 0 : best;
	}

	private Est cheapestOption(Ingredient ingredient) {
		Est best = Est.NONE;
		for (Item option : RecipeIndex.options(ingredient)) {
			best = cheaper(best, estimate(option));
		}
		return best;
	}

	/**
	 * What this recipe would take for {@code n} from this bag: the estimates leave out what's in
	 * it, so four strings looked like four spiders, and it went for a sheep instead of making wool.
	 */
	private double craftWay(State s, RecipeIndex.Craft craft, int n) {
		int times = (n + craft.resultCount() - 1) / craft.resultCount();
		double cost = CRAFT_PER_OP * times;
		if (craft.needsTable()) {
			cost += stationUse(Items.CRAFTING_TABLE) + (s.have(Items.CRAFTING_TABLE) > 0 ? 0 : estimate(Items.CRAFTING_TABLE).total());
		}
		for (Map.Entry<Ingredient, Integer> e : group(craft.ingredients()).entrySet()) {
			cost += stillToGet(s, e.getKey(), e.getValue() * times);
		}
		return cost;
	}

	private double smeltWay(State s, RecipeIndex.Smelt smelt, int n) {
		int inputs = (n + smelt.resultCount() - 1) / smelt.resultCount();
		return stationUse(Items.FURNACE) + (s.have(Items.FURNACE) > 0 ? 0 : estimate(Items.FURNACE).total())
				+ inputs * (double) smelt.cookingTime() + stillToGet(s, smelt.input(), inputs);
	}

	private double stillToGet(State s, Ingredient ingredient, int n) {
		for (Item option : RecipeIndex.options(ingredient)) {
			if (option != reserved) {
				n -= s.have(option);
			}
		}
		return n <= 0 ? 0 : cheapestOption(ingredient).of(n);
	}

	/** Cheapest-looking first (in the given order if none can be estimated). */
	private List<Item> byEstimate(List<Item> options) {
		List<Item> ranked = new ArrayList<>(options);
		ranked.sort(Comparator.comparingDouble(o -> estimate(o).total()));
		return ranked;
	}

	/**
	 * Enough alternatives tried, with {@code next} up: when what it takes at the very least is
	 * already more than the one in hand, or the search has given each kind its share. As long as
	 * none has worked, the next is tried, however it looks.
	 */
	private boolean enough(State from, State best, int tried, Item next, int n) {
		if (best == null) {
			return false;
		}
		return tried >= MAX_ALTERNATIVES || floor(next, n) >= best.cost - from.cost;
	}

	// ---- floors -----------------------------------------------------------------------------

	/**
	 * Ticks that getting {@code n} of {@code item} takes at the very least, by the plan's own costs:
	 * what can't be had for less in any way, with nothing in the bag. Gathering and hunting are
	 * what they cost from here; making it is the crafts, the cooking time and the stations it puts
	 * down, plus the floors of the ingredients. Tools, stations made and fuel count for nothing:
	 * the bag may have them. A loop, or a recipe tree too deep, counts for nothing too. So a floor
	 * is never more than what the plan would come to, and a way whose floor is more than what's in
	 * hand can be left alone without losing the cheapest plan.
	 */
	private double floor(Item item, double n) {
		if (n <= 0) {
			return 0;
		}
		Map<Integer, Double> known = floors.computeIfAbsent(item, i -> new HashMap<>());
		int count = (int) Math.ceil(n);
		Double cached = known.get(count);
		if (cached != null) {
			return cached;
		}
		if (flooring.size() > MAX_DEPTH || !flooring.add(item)) {
			return 0;
		}
		double best = Double.POSITIVE_INFINITY;
		if (gatherable(item)) {
			double g = gatherCost(item, count, true);
			if (!Double.isNaN(g)) {
				best = g;
			}
		}
		if (item == Items.WATER_BUCKET) {
			best = Math.min(best, ticks(WATER_TRIP_BLOCKS) + FILL_PER_ITEM * count + floor(Items.BUCKET, count));
		}
		Map<EntityType<?>, Double> prey = huntSources(item);
		if (!prey.isEmpty()) {
			best = Math.min(best, huntCost(prey, count));
		}
		for (RecipeIndex.Craft craft : recipes(item)) {
			int times = (count + craft.resultCount() - 1) / craft.resultCount();
			double c = CRAFT_PER_OP * times + (craft.needsTable() ? stationUse(Items.CRAFTING_TABLE) : 0);
			for (Map.Entry<Ingredient, Integer> e : group(craft.ingredients()).entrySet()) {
				c += optionFloor(e.getKey(), e.getValue() * times);
			}
			best = Math.min(best, c);
		}
		for (RecipeIndex.Smelt smelt : RecipeIndex.smeltsFor(level, item)) {
			int inputs = (count + smelt.resultCount() - 1) / smelt.resultCount();
			best = Math.min(best, stationUse(Items.FURNACE) + (double) inputs * smelt.cookingTime() + optionFloor(smelt.input(), inputs));
		}
		flooring.remove(item);
		known.put(count, best);
		return best;
	}

	private final Map<Item, Map<Integer, Double>> floors = new HashMap<>();
	/** Items whose floor is being worked out: one met again is a loop (see #floor). */
	private final Set<Item> flooring = new HashSet<>();

	private double optionFloor(Ingredient ingredient, double n) {
		double best = Double.POSITIVE_INFINITY;
		for (Item option : RecipeIndex.options(ingredient)) {
			best = Math.min(best, floor(option, n));
		}
		return best;
	}

	/** The floor of a way, for what this bag doesn't hold already. */
	private double wayFloor(State s, Object way, int n) {
		if (way instanceof RecipeIndex.Craft craft) {
			int times = (n + craft.resultCount() - 1) / craft.resultCount();
			double c = CRAFT_PER_OP * times + (craft.needsTable() ? stationUse(Items.CRAFTING_TABLE) : 0);
			for (Map.Entry<Ingredient, Integer> e : group(craft.ingredients()).entrySet()) {
				c += optionFloor(e.getKey(), missing(s, e.getKey(), e.getValue() * times));
			}
			return c;
		}
		RecipeIndex.Smelt smelt = (RecipeIndex.Smelt) way;
		int inputs = (n + smelt.resultCount() - 1) / smelt.resultCount();
		return stationUse(Items.FURNACE) + (double) inputs * smelt.cookingTime() + optionFloor(smelt.input(), missing(s, smelt.input(), inputs));
	}

	/** How many of these still to get, after what the bag holds. */
	private int missing(State s, Ingredient ingredient, int n) {
		for (Item option : RecipeIndex.options(ingredient)) {
			if (option != reserved) {
				n -= s.have(option);
			}
		}
		return Math.max(0, n);
	}

	/**
	 * Mobs the bot could hunt for this item here: they drop it, and are either around already or
	 * spawn somewhere in this dimension (no waiting for blazes in the overworld).
	 */
	private Map<EntityType<?>, Double> huntSources(Item item) {
		if (noHunt.contains(item) || !(breaker instanceof Player killer)) {
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
			if (!MobDrops.spawnBiomes(level, e.getKey()).isEmpty() || nearbyPrey(Map.of(e.getKey(), 1.0)) > 0
					|| !StructureIndex.spawning(level, e.getKey()).isEmpty()) {
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
	 * waiting for it to get dark for monsters), then the kills it takes at its drop rate, the ones
	 * around first.
	 */
	private double huntCost(Map<EntityType<?>, Double> prey, double n) {
		double perKill = prey.values().stream().mapToDouble(Double::doubleValue).max().orElse(1.0);
		double kills = n / Math.max(1e-3, perKill);
		int around = nearbyPrey(prey);
		boolean animals = prey.keySet().stream().anyMatch(t -> t.getCategory() != MobCategory.MONSTER);
		double trip = around > 0 ? toNearestPrey(prey) : structureOnly(prey) ? ticks(structureDistance(prey))
				: animals ? ticks(TRIP_BLOCKS) : ticksToDark();
		double kill = killTicks(prey) + ticks(CHASE_BLOCKS) + PICKUP_TICKS;
		double near = Math.min(kills, around);
		return trip + near * kill + (kills - near) * (kill + waitPerKill(prey));
	}

	/**
	 * Ticks to the next one, once those around are used up: an average animal is the walk to the
	 * next herd, an average monster the wait for one to spawn. By how common each is among its kind
	 * (spawn weights, see MobDrops#commonness), and by what it drops: a witch turns up for every
	 * eleven of an average monster, so waiting for one takes eleven times as long. (They were all one,
	 * and witches, dropping a bit of everything, came up in plan after plan.)
	 */
	private double waitPerKill(Map<EntityType<?>, Double> prey) {
		double best = Double.POSITIVE_INFINITY;
		for (EntityType<?> type : prey.keySet()) {
			double base = type.getCategory() == MobCategory.MONSTER ? SPAWN_WAIT_PER_KILL : ticks(NEXT_HERD_BLOCKS);
			double common = MobDrops.spawnBiomes(level, type).isEmpty() ? 1 : MobDrops.commonness(level, type);
			best = Math.min(best, base / Math.max(1e-3, common));
		}
		return Double.isInfinite(best) ? SPAWN_WAIT_PER_KILL : best;
	}

	/** None of it spawns out in the world: it's in structures or nowhere. */
	private boolean structureOnly(Map<EntityType<?>, Double> prey) {
		return prey.keySet().stream().allMatch(t -> MobDrops.spawnBiomes(level, t).isEmpty());
	}

	private int structureDistance(Map<EntityType<?>, Double> prey) {
		Set<Holder<Structure>> where = new java.util.HashSet<>();
		prey.keySet().forEach(t -> where.addAll(StructureIndex.spawning(level, t)));
		return StructureIndex.typicalDistance(level, where);
	}

	/**
	 * Nothing out in the world gives it: no biome grows it, no mob drops it, and no recipe makes it
	 * from things that are. The one case where a structure is where to go. A recipe that only leads
	 * back to itself doesn't count: wheat is made from hay bales and hay bales from wheat, and with
	 * "made by a recipe" alone, villages were never looked at for either.
	 */
	private boolean onlyInStructures(Item item) {
		if (worldly == null) {
			worldly = fromTheWorld();
		}
		return !worldly.contains(item);
	}

	/** Everything that comes from out in the world (see #onlyInStructures), worked out once a plan. */
	private Set<Item> worldly;

	/**
	 * What a biome grows or a mob drops, then whatever a recipe makes from those, over and over
	 * until nothing more is added. (Asking item by item went round the dye recipes, which lead
	 * into each other, for ever.)
	 */
	private Set<Item> fromTheWorld() {
		Set<Item> yes = new HashSet<>();
		List<Item> rest = new ArrayList<>();
		for (Item item : net.minecraft.core.registries.BuiltInRegistries.ITEM) {
			Set<Block> blocks = sourceBlocks.computeIfAbsent(item, i -> DropAnalysis.candidates(level, breaker, i).keySet());
			// A mob that spawns out in the world drops it (quietly: not every item's hunt is a problem).
			boolean dropped = breaker instanceof Player killer && MobDrops.sources(level, killer, item).keySet().stream()
					.anyMatch(t -> !MobDrops.spawnBiomes(level, t).isEmpty() || !StructureIndex.spawning(level, t).isEmpty());
			if (!BiomeHints.biomesFor(level, blocks).isEmpty() || dropped) {
				yes.add(item);
			} else {
				rest.add(item);
			}
		}
		for (boolean more = true; more; ) {
			more = false;
			for (java.util.Iterator<Item> it = rest.iterator(); it.hasNext(); ) {
				Item item = it.next();
				boolean made = RecipeIndex.craftsFor(level, item).stream().anyMatch(craft -> craft.ingredients().stream()
								.allMatch(ing -> RecipeIndex.options(ing).stream().anyMatch(yes::contains)))
						|| RecipeIndex.smeltsFor(level, item).stream().anyMatch(smelt -> RecipeIndex.options(smelt.input()).stream().anyMatch(yes::contains));
				if (made) {
					yes.add(item);
					it.remove();
					more = true;
				}
			}
		}
		return yes;
	}

	/** Ticks until monsters spawn in the open: none under cover, or at night. */
	private double ticksToDark() {
		if (!level.canSeeSky(breaker.blockPosition())) {
			return 0;
		}
		long time = level.getDayTime() % 24_000;
		return time >= DARK_FROM && time < DARK_TO ? 0 : Math.floorMod(DARK_FROM - time, 24_000L);
	}

	/**
	 * Ticks of fighting per kill: the blows its health takes, each at the weapon's full charge, the
	 * way it hits. Of the prey, the quickest one.
	 */
	private double killTicks(Map<EntityType<?>, Double> prey) {
		double[] weapon = weapon();
		double best = Double.POSITIVE_INFINITY;
		for (EntityType<?> type : prey.keySet()) {
			@SuppressWarnings("unchecked")
			EntityType<? extends LivingEntity> living = (EntityType<? extends LivingEntity>) type;
			if (DefaultAttributes.hasSupplier(type)) {
				double health = DefaultAttributes.getSupplier(living).getValue(Attributes.MAX_HEALTH);
				best = Math.min(best, Math.ceil(health / weapon[0]) * weapon[1]);
			}
		}
		return Double.isInfinite(best) ? 0 : best;
	}

	/** {damage, ticks between full blows} of the best sword or axe in the bag, or the bare hand. */
	private double[] weapon() {
		if (weapon == null) {
			LivingEntity who = (LivingEntity) breaker;
			double baseDamage = who.getAttributeBaseValue(Attributes.ATTACK_DAMAGE);
			double baseSpeed = who.getAttributeBaseValue(Attributes.ATTACK_SPEED);
			weapon = new double[]{baseDamage, 20 / baseSpeed};
			for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
				ItemStack stack = realInventory.getItem(slot);
				if (!stack.is(ItemTags.SWORDS) && !stack.is(ItemTags.AXES)) {
					continue;
				}
				double[] add = new double[2];
				stack.forEachModifier(EquipmentSlot.MAINHAND, (attribute, modifier) -> {
					if (attribute.equals(Attributes.ATTACK_DAMAGE)) {
						add[0] += modifier.amount();
					} else if (attribute.equals(Attributes.ATTACK_SPEED)) {
						add[1] += modifier.amount();
					}
				});
				double damage = baseDamage + add[0], delay = 20 / Math.max(0.1, baseSpeed + add[1]);
				if (damage / delay > weapon[0] / weapon[1]) {
					weapon = new double[]{damage, delay};
				}
			}
		}
		return weapon;
	}

	private double[] weapon;

	/** How many of this prey are within 64 blocks. */
	private int nearbyPrey(Map<EntityType<?>, Double> prey) {
		if (nearbyTypes == null) {
			// One look around per plan (it used to search for mobs every time a hunt was costed).
			nearbyTypes = new HashMap<>();
			nearbyAt = new HashMap<>();
			for (Mob m : level.getEntitiesOfClass(Mob.class, breaker.getBoundingBox().inflate(64),
					m -> m.isAlive() && MobDrops.mayHunt(m))) {
				nearbyTypes.merge(m.getType(), 1, Integer::sum);
				nearbyAt.computeIfAbsent(m.getType(), k -> new ArrayList<>()).add(m.blockPosition());
			}
		}
		int n = 0;
		for (EntityType<?> type : prey.keySet()) {
			n += nearbyTypes.getOrDefault(type, 0);
		}
		return n;
	}

	/** Huntable mobs within 64 blocks, by kind, looked up once per plan; and where they are. */
	private Map<EntityType<?>, Integer> nearbyTypes;
	private Map<EntityType<?>, List<BlockPos>> nearbyAt;

	/**
	 * Ticks to the nearest of these around: the walk, and the digging down to it if it's under
	 * the ground (every solid block above it, as for a block to mine: see #buried). A spider in a
	 * mineshaft forty blocks down was a walk of 32 blocks, while the cobwebs next to it were costed
	 * with all the digging: it went for spiders it couldn't get to.
	 */
	private double toNearestPrey(Map<EntityType<?>, Double> prey) {
		nearbyPrey(prey);
		BlockPos from = breaker.blockPosition();
		double best = Double.POSITIVE_INFINITY;
		for (EntityType<?> type : prey.keySet()) {
			for (BlockPos p : nearbyAt.getOrDefault(type, List.of())) {
				best = Math.min(best, ticks(Math.sqrt(from.distSqr(p))) + digDownTo(p));
			}
		}
		return Double.isInfinite(best) ? ticks(32) : best;
	}

	/** Ticks to dig down to {@code p} from the surface, with what's in the bag (0 if it's out in the open). */
	private double digDownTo(BlockPos p) {
		int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ());
		double ticks = 0;
		int solid = 0;
		BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
		for (int y = p.getY() + 1; y < surface && solid < 80; y++) {
			q.set(p.getX(), y, p.getZ());
			BlockState state = level.getBlockState(q);
			if (!state.getCollisionShape(level, q).isEmpty()) {
				solid++;
				int dig = BreakTimes.estimateTicks(realInventory, level, q, state);
				ticks += (dig == Integer.MAX_VALUE ? 0 : dig + LocalPathfinder.BREAK_OVERHEAD) + ticks(1);
			}
		}
		return ticks;
	}

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
