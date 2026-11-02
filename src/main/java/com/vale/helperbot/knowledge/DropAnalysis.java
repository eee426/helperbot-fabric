package com.vale.helperbot.knowledge;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.level.storage.loot.LootTable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Works out where an item comes from by simulating the real loot tables: which blocks drop it,
 * and with which tools. Nothing is hand-written per item, so it also covers other mods' blocks.
 */
public final class DropAnalysis {
	/** Random drops (e.g. flint from gravel) get a few tries before we decide a block doesn't drop it. */
	private static final int TRIALS = 4;

	/** A tool to try, and how to describe it to the player when they need to provide it. */
	private record ProbeTool(ItemStack stack, String description) {
	}

	/**
	 * For every item: the blocks that can drop it, and which probe tools work for each. Built once
	 * for all items in one pass over every block (see {@link #warmUp}), so asking about any item
	 * afterwards is a lookup. The planner asks about many items per plan.
	 */
	private static Map<Item, Map<Block, List<String>>> index;
	/** Average number of the item one break of the block gives, with the best probe tool. */
	private static Map<Item, Map<Block, Double>> rates;
	/**
	 * Items a block's loot table mentions but the quick sampling never saw drop (rare drops like
	 * apples from oak leaves at 0.5%). Sampled properly the first time someone asks about the item.
	 */
	private static Map<Item, Set<Block>> rareCandidates;
	private static Set<Item> rareSampled;
	/**
	 * Block-state properties a block's loot table checks (berries=true, age=7...): what drops
	 * depends on them, so every combination of their values is sampled, not just the default state.
	 */
	private static Map<Block, List<Property<?>>> lootProperties;
	/**
	 * For drops that the default state doesn't give (glow berries, ripe wheat): the states that
	 * do, with only {@link #lootProperties} set (see {@link #project}). No entry: any state.
	 */
	private static Map<Item, Map<Block, Set<BlockState>>> dropStates;
	/** Cap on sampled states per block. */
	private static final int MAX_VARIANTS = 64;
	/** Rolls per probe tool when looking for a rare drop. */
	private static final int RARE_TRIALS = 400;
	private static final Pattern ID = Pattern.compile("\"([a-z0-9_.\\-]+:[a-z0-9_./\\-]+)\"");

	private DropAnalysis() {
	}

	/** Forget cached results, e.g. after /reload changed loot tables. */
	public static void clearCache() {
		toolItems = new HashMap<>();
		dropAnswers.clear();
		index = null;
	}

	/**
	 * Expected number of {@code item} per broken block, for the best of {@code blocks} (1.0 if
	 * unknown). The planner divides by this: an apple needs ~200 leaves broken.
	 */
	public static double bestRate(Item item, Iterable<Block> blocks) {
		Map<Block, Double> byBlock = rates == null ? null : rates.get(item);
		if (byBlock == null) {
			return 1.0;
		}
		double best = 0;
		for (Block block : blocks) {
			best = Math.max(best, byBlock.getOrDefault(block, 0.0));
		}
		return best > 0 ? best : 1.0;
	}

	/** Build the index now (takes a moment), e.g. at server start instead of on the first request. */
	public static void warmUp(ServerLevel level) {
		buildIndex(level);
	}

	/**
	 * Blocks that drop {@code item} with at least one kind of tool, mapped to descriptions of
	 * the tools that work ("剪刀", "带精准采集的镐"...).
	 */
	public static Map<Block, List<String>> candidates(ServerLevel level, Entity breaker, Item item) {
		buildIndex(level);
		if (rareSampled.add(item)) {
			sampleRare(level, item);
		}
		return index.getOrDefault(item, Map.of());
	}

	private static void buildIndex(ServerLevel level) {
		if (index != null) {
			return;
		}
		List<ProbeTool> probes = probeTools(level);
		Map<Item, Map<Block, List<String>>> built = new HashMap<>();
		Map<Item, Map<Block, Double>> builtRates = new HashMap<>();
		Map<Item, Set<Block>> rare = new HashMap<>();
		Map<Block, List<Property<?>>> props = new HashMap<>();
		Map<Item, Map<Block, Set<BlockState>>> states = new HashMap<>();
		RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, level.registryAccess());
		for (Block block : BuiltInRegistries.BLOCK) {
			BlockState defaultState = block.defaultBlockState();
			if (defaultState.isAir() || block.getLootTable().isEmpty() || defaultState.getDestroySpeed(level, BlockPos.ZERO) < 0) {
				continue;
			}
			String json = lootTableJson(level, block, ops);
			List<Property<?>> checked = checkedProperties(block, json);
			if (!checked.isEmpty()) {
				props.put(block, checked);
			}
			Map<Item, Set<BlockState>> droppedBy = new HashMap<>();
			for (BlockState state : variants(block, checked)) {
				for (ProbeTool probe : probes) {
					Map<Item, Integer> counts = rollDrops(level, state, probe.stack(), TRIALS);
					for (Map.Entry<Item, Integer> e : counts.entrySet()) {
						record(built, builtRates, e.getKey(), block, probe.description(), (double) e.getValue() / TRIALS);
						droppedBy.computeIfAbsent(e.getKey(), k -> new java.util.HashSet<>()).add(state);
					}
				}
			}
			droppedBy.forEach((item, from) -> {
				if (!from.contains(defaultState)) {
					states.computeIfAbsent(item, k -> new HashMap<>()).put(block, from);
				}
			});
			for (Item mentioned : mentionedItems(json)) {
				if (!droppedBy.containsKey(mentioned)) {
					rare.computeIfAbsent(mentioned, k -> new java.util.HashSet<>()).add(block);
				}
			}
		}
		rates = builtRates;
		rareCandidates = rare;
		rareSampled = new java.util.HashSet<>();
		lootProperties = props;
		dropStates = states;
		index = built;
	}

	/**
	 * Would breaking a block in this state drop {@code item}? False only when the drop depends on
	 * the state and this one doesn't give it (vines without berries, unripe wheat).
	 */
	public static boolean dropsFrom(Item item, BlockState state) {
		Map<Block, Set<BlockState>> byBlock = dropStates == null ? null : dropStates.get(item);
		Set<BlockState> ok = byBlock == null ? null : byBlock.get(state.getBlock());
		return ok == null || ok.contains(project(state));
	}

	/** A state of {@code block} that drops {@code item}, to test tools against. */
	private static BlockState sampleState(Item item, Block block) {
		Map<Block, Set<BlockState>> byBlock = dropStates == null ? null : dropStates.get(item);
		Set<BlockState> ok = byBlock == null ? null : byBlock.get(block);
		return ok == null || ok.isEmpty() ? block.defaultBlockState() : ok.iterator().next();
	}

	/** The default state with only the loot-relevant properties copied over from {@code state}. */
	private static BlockState project(BlockState state) {
		BlockState s = state.getBlock().defaultBlockState();
		for (Property<?> p : lootProperties.getOrDefault(state.getBlock(), List.of())) {
			s = copyValue(s, state, p);
		}
		return s;
	}

	private static <T extends Comparable<T>> BlockState copyValue(BlockState to, BlockState from, Property<T> p) {
		return to.setValue(p, from.getValue(p));
	}

	/** Every combination of the given properties' values (others left at default), capped. */
	private static List<BlockState> variants(Block block, List<Property<?>> props) {
		List<BlockState> out = List.of(block.defaultBlockState());
		for (Property<?> p : props) {
			List<BlockState> next = new ArrayList<>();
			for (BlockState s : out) {
				next.addAll(allValues(s, p));
			}
			if (next.size() > MAX_VARIANTS) {
				break;
			}
			out = next;
		}
		return out;
	}

	private static <T extends Comparable<T>> List<BlockState> allValues(BlockState s, Property<T> p) {
		return p.getPossibleValues().stream().map(v -> s.setValue(p, v)).toList();
	}

	private static final Pattern PROPERTIES = Pattern.compile("\"properties\":");
	private static final Pattern KEY = Pattern.compile("\"([a-z0-9_]+)\"");

	/** Properties of this block named in a "properties" object or list anywhere in its loot table. */
	private static List<Property<?>> checkedProperties(Block block, String json) {
		Set<Property<?>> found = new LinkedHashSet<>();
		Matcher m = PROPERTIES.matcher(json);
		while (m.find()) {
			int start = m.end();
			if (start >= json.length()) {
				break;
			}
			char open = json.charAt(start);
			char close = open == '{' ? '}' : open == '[' ? ']' : 0;
			if (close == 0) {
				continue;
			}
			// Find the matching bracket; for an object, only keys at its top level are names.
			int depth = 0, end = start;
			StringBuilder top = new StringBuilder();
			for (int i = start; i < json.length(); i++) {
				char c = json.charAt(i);
				if (c == '{' || c == '[') {
					depth++;
				} else if (c == '}' || c == ']') {
					depth--;
				}
				if (depth == 1 && c != open) {
					top.append(c);
				}
				if (depth == 0) {
					end = i;
					break;
				}
			}
			String body = top.toString();
			Matcher k = KEY.matcher(body);
			while (k.find()) {
				// In an object, a name is followed by ':'; in a list, every string is a name.
				boolean isName = open == '[' || (k.end() < body.length() && body.charAt(k.end()) == ':');
				Property<?> p = isName ? block.getStateDefinition().getProperty(k.group(1)) : null;
				if (p != null) {
					found.add(p);
				}
			}
			m.region(Math.max(end, start), json.length());
		}
		return new ArrayList<>(found);
	}

	private static void record(Map<Item, Map<Block, List<String>>> into, Map<Item, Map<Block, Double>> rateInto,
							   Item item, Block block, String tool, double rate) {
		into.computeIfAbsent(item, k -> new HashMap<>()).computeIfAbsent(block, k -> new ArrayList<>()).add(tool);
		rateInto.computeIfAbsent(item, k -> new HashMap<>()).merge(block, rate, Math::max);
	}

	/** Roll the blocks whose loot table mentions {@code item} many times, to catch rare drops. */
	private static void sampleRare(ServerLevel level, Item item) {
		Set<Block> blocks = rareCandidates.get(item);
		if (blocks == null) {
			return;
		}
		for (Block block : blocks) {
			Set<BlockState> from = new java.util.HashSet<>();
			for (BlockState state : variants(block, lootProperties.getOrDefault(block, List.of()))) {
				for (ProbeTool probe : probeTools(level)) {
					int got = rollDrops(level, state, probe.stack(), RARE_TRIALS).getOrDefault(item, 0);
					if (got > 0) {
						record(index, rates, item, block, probe.description(), (double) got / RARE_TRIALS);
						from.add(state);
					}
				}
			}
			if (!from.isEmpty() && !from.contains(block.defaultBlockState())) {
				dropStates.computeIfAbsent(item, k -> new HashMap<>()).put(block, from);
			}
		}
	}

	/** The block's loot table as JSON text, or "" if it can't be encoded (some modded tables). */
	private static String lootTableJson(ServerLevel level, Block block, RegistryOps<JsonElement> ops) {
		try {
			LootTable table = level.getServer().reloadableRegistries().getLootTable(block.getLootTable().orElseThrow());
			return LootTable.DIRECT_CODEC.encodeStart(ops, table).result().map(JsonElement::toString).orElse("");
		} catch (RuntimeException e) {
			return ""; // rely on sampling the default state alone
		}
	}

	/** Item ids written anywhere in the loot table (some may be in conditions, not drops). */
	private static Set<Item> mentionedItems(String json) {
		Set<Item> items = new java.util.HashSet<>();
		Matcher m = ID.matcher(json);
		while (m.find()) {
			Identifier id = Identifier.tryParse(m.group(1));
			if (id != null) {
				BuiltInRegistries.ITEM.getOptional(id).filter(i -> i != Items.AIR).ifPresent(items::add);
			}
		}
		return items;
	}

	/** How many of each item this block drops with this tool, summed over {@code trials} rolls. */
	private static Map<Item, Integer> rollDrops(ServerLevel level, BlockState state, ItemStack tool, int trials) {
		Map<Item, Integer> counts = new HashMap<>();
		// Blocks that need the right tool drop nothing without it (checked outside the loot table).
		if (state.requiresCorrectToolForDrops() && !tool.isCorrectToolForDrops(state)) {
			return counts;
		}
		try {
			for (int i = 0; i < trials; i++) {
				for (ItemStack drop : Block.getDrops(state, level, BlockPos.ZERO, null, null, tool.copy())) {
					// A count of 0 (e.g. stem seeds) is an empty stack that reads as AIR: skip it.
					if (!drop.isEmpty()) {
						counts.merge(drop.getItem(), drop.getCount(), Integer::sum);
					}
				}
			}
		} catch (RuntimeException e) {
			// Some (modded) loot tables need a block entity or context we can't fake here.
		}
		return counts;
	}

	/**
	 * Inventory slot (0-35) of a tool the player gave that makes {@code block} drop {@code item},
	 * {@link #HAND} if a bare hand does, or {@link #NONE}.
	 */
	public static int toolSlotFor(ServerLevel level, Entity breaker, Inventory inventory, Block block, Item item) {
		BlockState state = sampleState(item, block);
		// Rare drops need more rolls before "no" means no: ~3 expected hits, at least TRIALS.
		Map<Block, Double> byBlock = rates == null ? null : rates.get(item);
		double rate = byBlock == null ? 1.0 : byBlock.getOrDefault(block, 1.0);
		int trials = (int) Math.max(TRIALS, Math.min(RARE_TRIALS, Math.ceil(3 / rate)));
		// Of everything that gets the drop, the fastest: a shovel for clay, not the bare hand just
		// because the hand works too (it used to stop at the first that worked, the hand).
		int best = NONE;
		float bestSpeed = 0;
		if (drops(level, breaker, state, ItemStack.EMPTY, item, trials)) {
			best = HAND;
			bestSpeed = 1;
		}
		for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
			ItemStack stack = inventory.getItem(slot);
			// Plain items (blocks, food...) act like a bare hand, which was just tried.
			if (stack.isEmpty() || (!stack.isDamageableItem() && !stack.isEnchanted())
					|| (stack.isDamageableItem() && stack.getDamageValue() >= stack.getMaxDamage() - 1)) {
				continue;
			}
			float speed = stack.getDestroySpeed(state);
			if (speed > bestSpeed && drops(level, breaker, state, stack, item, trials)) {
				best = slot;
				bestSpeed = speed;
			}
		}
		return best;
	}

	private static Map<Item, Map<Block, List<Item>>> toolItems = new HashMap<>();
	private static List<Item> allTools;

	/**
	 * Plain (unenchanted) tool items that make {@code block} drop {@code item}, or an empty list if
	 * the bare hand does already or nothing plain does (silk touch only). For planning: tools are
	 * something to make first, like any ingredient.
	 */
	public static List<Item> toolItemsFor(ServerLevel level, Entity breaker, Block block, Item item) {
		return toolItems.computeIfAbsent(item, k -> new HashMap<>()).computeIfAbsent(block, b -> {
			BlockState state = sampleState(item, b);
			int trials = (int) Math.max(TRIALS, Math.min(RARE_TRIALS, Math.ceil(3 / rate(item, b))));
			List<Item> out = new ArrayList<>();
			if (drops(level, breaker, state, ItemStack.EMPTY, item, trials)) {
				return out;
			}
			if (allTools == null) {
				allTools = new ArrayList<>();
				for (Item i : BuiltInRegistries.ITEM) {
					if (new ItemStack(i).has(net.minecraft.core.component.DataComponents.TOOL)) {
						allTools.add(i);
					}
				}
			}
			for (Item tool : allTools) {
				if (drops(level, breaker, state, new ItemStack(tool), item, trials)) {
					out.add(tool);
				}
			}
			return out;
		});
	}

	/** Does the bare hand get the drop? */
	public static boolean handDrops(ServerLevel level, Entity breaker, Block block, Item item) {
		BlockState state = sampleState(item, block);
		int trials = (int) Math.max(TRIALS, Math.min(RARE_TRIALS, Math.ceil(3 / rate(item, block))));
		return drops(level, breaker, state, ItemStack.EMPTY, item, trials);
	}

	public static final int HAND = -1;
	public static final int NONE = -2;

	/**
	 * Would breaking this block, in this state, with exactly {@code held} in hand, drop {@code item}?
	 * Checked right before mining, so whatever made the hand differ from the plan (a full hotbar,
	 * the player rearranging the bag...) is caught before the block is wasted.
	 */
	public static boolean wouldDrop(ServerLevel level, Entity breaker, BlockState state, ItemStack held, Item item) {
		Map<Block, Double> byBlock = rates == null ? null : rates.get(item);
		double rate = byBlock == null ? 1.0 : byBlock.getOrDefault(state.getBlock(), 1.0);
		int trials = (int) Math.max(TRIALS, Math.min(RARE_TRIALS, Math.ceil(3 / rate)));
		// Plain items (blocks, food...) break like a bare hand, and are what the hand often holds.
		ItemStack tool = !held.isDamageableItem() && !held.isEnchanted() ? ItemStack.EMPTY : held;
		return drops(level, breaker, state, tool, item, trials);
	}

	/** Expected number per break of this block (best tool), 1.0 if unknown. */
	public static double rate(Item item, Block block) {
		Map<Block, Double> byBlock = rates == null ? null : rates.get(item);
		return byBlock == null ? 1.0 : byBlock.getOrDefault(block, 1.0);
	}

	/** A tool as far as drops go: its kind and enchantments (wear doesn't change what drops). */
	private record DropKey(BlockState state, Item tool, Object enchantments, Item item) {
	}

	/**
	 * Answers of {@link #drops}, kept for good: the same block and tool always have the same loot
	 * table. Asked afresh each time, a chance drop could come out differently: flint is 10% from
	 * gravel, and 30 rolls miss it about once in 25, so the bot switched between the shovel and its
	 * bare hand from one gravel block to the next.
	 */
	private static final Map<DropKey, Boolean> dropAnswers = new HashMap<>();

	/** Rolls needed so that a drop with this chance is missed less than once in a million. */
	private static int sureTrials(double rate) {
		if (rate >= 1) {
			return TRIALS;
		}
		return (int) Math.min(SURE_MAX_TRIALS, Math.max(TRIALS, Math.ceil(Math.log(1e-6) / Math.log(1 - rate))));
	}

	private static final int SURE_MAX_TRIALS = 2_000;

	/** Would breaking {@code state} with {@code tool} (as a survival player) drop {@code item}? */
	private static boolean drops(ServerLevel level, Entity breaker, BlockState state, ItemStack tool, Item item, int trials) {
		// Blocks that need the right tool drop nothing without it (checked outside the loot table).
		if (state.requiresCorrectToolForDrops() && !tool.isCorrectToolForDrops(state)) {
			return false;
		}
		DropKey key = tool.isEmpty() ? new DropKey(state, null, null, item)
				: new DropKey(state, tool.getItem(), tool.get(net.minecraft.core.component.DataComponents.ENCHANTMENTS), item);
		Boolean known = dropAnswers.get(key);
		if (known != null) {
			return known;
		}
		boolean answer = roll(level, breaker, state, tool, item, Math.max(trials, sureTrials(rate(item, state.getBlock()))));
		dropAnswers.put(key, answer);
		return answer;
	}

	private static boolean roll(ServerLevel level, Entity breaker, BlockState state, ItemStack tool, Item item, int trials) {
		BlockPos pos = breaker.blockPosition();
		try {
			for (int i = 0; i < trials; i++) {
				for (ItemStack drop : Block.getDrops(state, level, pos, null, breaker, tool.copy())) {
					// Loot can roll a count of 0 (e.g. stem seeds): that's an empty stack, whose
					// item reads as AIR, so it must not count as dropping anything.
					if (!drop.isEmpty() && drop.is(item)) {
						return true;
					}
				}
			}
		} catch (RuntimeException e) {
			// Some (modded) loot tables need a block entity or context we can't fake here.
			return false;
		}
		return false;
	}

	/** A spread of tools covering every vanilla loot condition: hand, shears, each tool type, silk touch. */
	private static List<ProbeTool> probeTools(ServerLevel level) {
		Holder<Enchantment> silk = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
				.getOrThrow(Enchantments.SILK_TOUCH);
		List<ProbeTool> probes = new ArrayList<>();
		probes.add(new ProbeTool(ItemStack.EMPTY, "空手"));
		probes.add(new ProbeTool(new ItemStack(Items.SHEARS), "剪刀"));
		Object[][] tools = {
				{Items.NETHERITE_PICKAXE, "镐"}, {Items.NETHERITE_AXE, "斧"},
				{Items.NETHERITE_SHOVEL, "锹"}, {Items.NETHERITE_HOE, "锄"}, {Items.NETHERITE_SWORD, "剑"}};
		for (Object[] t : tools) {
			probes.add(new ProbeTool(new ItemStack((Item) t[0]), (String) t[1]));
		}
		for (Object[] t : tools) {
			ItemStack stack = new ItemStack((Item) t[0]);
			stack.enchant(silk, 1);
			probes.add(new ProbeTool(stack, "带精准采集的" + t[1]));
		}
		return probes;
	}

	/** Short human description of what the player could give, e.g. "剪刀 或 带精准采集的镐". */
	public static String describeOptions(Map<Block, List<String>> candidates) {
		Set<String> options = new LinkedHashSet<>();
		for (List<String> tools : candidates.values()) {
			options.addAll(tools);
		}
		options.remove("空手");
		return String.join(" 或 ", options);
	}

	/** Block that the item is the block form of, if any (so it can be listed first). */
	public static Block blockFormOf(Item item) {
		Block block = Block.byItem(item);
		return block == Blocks.AIR ? null : block;
	}
}
