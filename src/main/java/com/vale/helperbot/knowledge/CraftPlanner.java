package com.vale.helperbot.knowledge;

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
	public sealed interface Action permits Gather, Hunt, Craft, Smelt {
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

	/** Actions in order; empty if the inventory already has everything. Null actions = impossible. */
	public record Plan(List<Action> actions, Set<Component> problems) {
		public boolean possible() {
			return actions != null;
		}
	}

	// Rough costs in ticks.
	/** Fixed cost of a gathering trip, by how findable the source is (see {@link #gatherCost}). */
	private static final double GATHER_NEARBY = 300;
	private static final double GATHER_IN_BIOME = 2_000;
	/** Loaded chunks around the bot checked for "is it nearby". */
	private static final int NEARBY_CHUNKS = 4;
	private static final double GATHER_PER_ITEM = 40;
	/** Fixed cost of a hunting trip, by where the prey is (see {@link #huntCost}). */
	private static final double HUNT_NEARBY = 400;
	private static final double HUNT_IN_BIOME = 2_500;
	private static final double HUNT_WAIT_FOR_SPAWN = 3_500;
	/** Ticks per kill: chase, fight, pick up. */
	private static final double HUNT_PER_KILL = 150;
	private static final double CRAFT_PER_OP = 10;
	private static final double SMELT_FIXED = 40;
	private static final int MAX_DEPTH = 8;
	/** Cap on explored (item, need) nodes per plan, so planning stays a few milliseconds. */
	private static final int SEARCH_BUDGET = 20_000;
	private int budget = SEARCH_BUDGET;
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
	private final Map<Item, Double> gatherCost = new HashMap<>();
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

	public Plan plan(Item item, int count, Map<Item, Integer> inventory) {
		State state = new State(new HashMap<>(inventory));
		if (!acquire(state, item, count, 0, new HashSet<>())) {
			return new Plan(null, problems);
		}
		return new Plan(state.actions, problems);
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
		n -= s.take(item, n);
		if (n <= 0) {
			return true;
		}
		if (depth > MAX_DEPTH || resolving.contains(item)) {
			return false; // too deep, or a loop (e.g. iron ingot <-> iron block)
		}
		if (--budget < 0) {
			return false; // recipe trees can branch a lot: stop exploring, keep what we have
		}
		resolving.add(item);
		State best = null;

		double trip = gatherCost(item);
		if (!Double.isNaN(trip)) {
			State t = s.copy();
			// Each item may take several blocks: e.g. ~200 oak leaves per apple.
			t.cost += trip + GATHER_PER_ITEM * n / gatherRate.getOrDefault(item, 1.0);
			t.actions.add(new Gather(item, n));
			best = t;
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
			if (craftInto(t, craft, n, depth, resolving) && (best == null || t.cost < best.cost)) {
				best = t;
			}
		}
		for (RecipeIndex.Smelt smelt : RecipeIndex.smeltsFor(level, item)) {
			State t = s.copy();
			if (smeltInto(t, smelt, n, depth, resolving) && (best == null || t.cost < best.cost)) {
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

	private boolean craftInto(State t, RecipeIndex.Craft craft, int n, int depth, Set<Item> resolving) {
		int times = (n + craft.resultCount() - 1) / craft.resultCount();
		if (craft.needsTable() && !keepStation(t, Items.CRAFTING_TABLE, depth, resolving)) {
			return false;
		}
		for (Map.Entry<Ingredient, Integer> e : group(craft.ingredients()).entrySet()) {
			if (!acquireIngredient(t, e.getKey(), e.getValue() * times, depth, resolving)) {
				return false;
			}
		}
		t.cost += CRAFT_PER_OP * times;
		t.actions.add(new CraftPlanner.Craft(craft, times));
		t.add(craft.result(), times * craft.resultCount() - n); // leftovers stay in the bag
		return true;
	}

	private boolean smeltInto(State t, RecipeIndex.Smelt smelt, int n, int depth, Set<Item> resolving) {
		int inputs = (n + smelt.resultCount() - 1) / smelt.resultCount();
		if (!keepStation(t, Items.FURNACE, depth, resolving)
				|| !acquireIngredient(t, smelt.input(), inputs, depth, resolving)
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
			n -= t.take(option, n);
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
			while (burnTicks > 0 && t.take(item, 1) == 1) {
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

	/**
	 * Fixed cost of a trip to gather this item with the tools the bot has, or NaN if it can't.
	 * Depends on how findable its source blocks are: already in loaded chunks nearby, generated by
	 * some biome (go there), or neither, e.g. a door that only exists in villages (blind search,
	 * a last resort). Without this, "go find a door" looked as cheap as "go chop a tree".
	 */
	private double gatherCost(Item item) {
		double cost = quietGatherCost(item);
		Component problem = gatherProblem.get(item);
		if (Double.isNaN(cost) && problem != null) {
			problems.add(problem);
		}
		return cost;
	}

	/** Rough cost of getting one, without a full search: for ranking alternatives. */
	private double quickCost(Item item) {
		double best = Double.MAX_VALUE;
		double gather = quietGatherCost(item);
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
		return best;
	}

	/** Why gathering an item is impossible, shown only if it's actually considered (not just ranked). */
	private final Map<Item, Component> gatherProblem = new HashMap<>();
	/** {@link #quickCost} of something that can only be crafted or smelted: after anything gatherable. */
	private static final double QUICK_COST_MADE = 10_000;

	private double quietGatherCost(Item item) {
		if (noGather.contains(item) || Obtainability.refusal(level, item) != null) {
			return Double.NaN;
		}
		return gatherCost.computeIfAbsent(item, i -> {
			Map<Block, List<String>> candidates = DropAnalysis.candidates(level, breaker, i);
			Set<Block> usable = new HashSet<>();
			for (Block block : candidates.keySet()) {
				if (DropAnalysis.toolSlotFor(level, breaker, realInventory, block, i) != DropAnalysis.NONE) {
					usable.add(block);
				}
			}
			gatherRate.put(i, Math.min(1.0, DropAnalysis.bestRate(i, usable)));
			if (usable.isEmpty()) {
				if (!candidates.isEmpty()) {
					gatherProblem.put(i, Component.literal("采").append(name(i)).append("需要：" + DropAnalysis.describeOptions(candidates)));
				}
				return Double.NaN;
			}
			if (!BlockScanner.find(level, breaker.blockPosition(), NEARBY_CHUNKS,
					state -> usable.contains(state.getBlock()) && DropAnalysis.dropsFrom(i, state), p -> true, 1).isEmpty()) {
				return GATHER_NEARBY;
			}
			if (BiomeHints.biomesFor(level, usable).isEmpty()) {
				// Not nearby and no biome makes it (only in structures, or placed by players):
				// blind wandering almost never finds it, so don't plan on it.
				gatherProblem.put(i, Component.literal("附近没有").append(name(i)).append("，也不知道该去哪找"));
				return Double.NaN;
			}
			return GATHER_IN_BIOME;
		});
	}

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
		return !level.getEntitiesOfClass(Mob.class, breaker.getBoundingBox().inflate(64),
				m -> prey.containsKey(m.getType()) && m.isAlive() && MobDrops.mayHunt(m)).isEmpty();
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
