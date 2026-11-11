package com.vale.helperbot.knowledge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.vale.helperbot.HelperBot;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

/**
 * How likely a structure is to have a block: what StructureIndex says a structure can have, it may
 * have only in some of the ways the game puts it together (nether wart in one bastion layout of
 * four; a fortress without its stalk room). Worked out by having the game put each structure of each
 * dimension together {@link #SAMPLES} times, in memory, at random places (nothing is placed, no chunk
 * loaded: the same step as when it decides where a structure's pieces go), and counting the pieces'
 * blocks. Like the drops: what the game does, not a guess. Mods' structures count the same way.
 *
 * <p>Built once, in the background, at the first start of a world, and kept in its {@code data}
 * folder with what it was built from (game version, seed, the structures and templates there are):
 * read back at the next start if all of that still holds, built again if not, or if the file is
 * gone or broken. Written to a temporary file first and moved over the old one, so a file is either
 * whole or not there. Until it's ready the bot takes no orders (see BotCommands), so nothing is
 * planned half-informed.
 */
public final class StructureOdds {
	/** Times each structure is put together. Enough to tell one in four from nine in ten. */
	public static final int SAMPLES = 100;
	/** A structure that takes longer than this stops at what it has (at least {@link #MIN_SAMPLES}). */
	private static final long STRUCTURE_BUDGET_NANOS = 5_000_000_000L;
	private static final int MIN_SAMPLES = 20;
	/** Where the samples are taken: random chunks this far off the centre, either way. */
	private static final int SPREAD_CHUNKS = 625;
	/** Bump when what's written changes. */
	private static final int FORMAT = 1;
	private static final String FILE = "helperbot-structure-odds.json";
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** Samples that came out, and of those how many had each block. */
	private record Counts(int valid, Map<Block, Integer> blocks) {
	}

	/** Dimension, then structure: null until ready. */
	private static volatile Map<ResourceKey<Level>, Map<ResourceKey<Structure>, Counts>> table;
	private static volatile int done;
	private static volatile int total;
	private static Thread worker;

	private StructureOdds() {
	}

	/** The table is there (read or built): the bot may take orders. */
	public static boolean ready() {
		return table != null && !heldBack;
	}

	/** For a scene: act as if it weren't ready yet (see BotCommands#notReady). */
	private static volatile boolean heldBack;

	public static void holdBackForTest(boolean hold) {
		heldBack = hold;
	}

	/** How far building it has got, for the player. */
	public static String progress() {
		return ready() ? "结构表已准备好" : "结构表 " + done + "/" + Math.max(total, 1);
	}

	// ---- what it says --------------------------------------------------------------------

	/**
	 * The chance one of this structure has any of these blocks (by the most common of them): 1 if
	 * nothing is known yet or the game couldn't put it together (then "can have" is all there is to
	 * go by, as before). What it can have but no sample had counts as a long shot, not as none.
	 */
	public static double share(ServerLevel level, Holder<Structure> structure, Collection<Block> blocks) {
		Map<ResourceKey<Level>, Map<ResourceKey<Structure>, Counts>> t = table;
		if (t == null) {
			return 1;
		}
		Counts c = structure.unwrapKey().map(k -> t.getOrDefault(level.dimension(), Map.of()).get(k)).orElse(null);
		if (c == null || c.valid() == 0) {
			return 1;
		}
		int most = 0;
		for (Block b : blocks) {
			most = Math.max(most, c.blocks().getOrDefault(b, 0));
		}
		return Math.max(most, 0.5) / c.valid();
	}

	/**
	 * About how far it is to one that has it, in blocks: the nearest of these by how the game spreads
	 * them (see StructureIndex#typicalDistance), over the chance one has it, the best of them. A
	 * bastion with nether wart one time in four is four bastions off.
	 */
	public static int typicalTrip(ServerLevel level, Collection<Holder<Structure>> structures, Collection<Block> blocks) {
		double best = Double.POSITIVE_INFINITY;
		for (Holder<Structure> s : structures) {
			best = Math.min(best, StructureIndex.typicalDistance(level, List.of(s)) / share(level, s, blocks));
		}
		return Double.isInfinite(best) ? StructureIndex.typicalDistance(level, structures) : (int) Math.min(best, Integer.MAX_VALUE);
	}

	/**
	 * Of these, the one to go to: the nearest of each kind (not one of {@code seen}), the best by
	 * distance over the chance it has it. Null if none is found.
	 */
	public static BlockPos nearestWorthIt(ServerLevel level, Collection<Holder<Structure>> structures, Collection<Block> blocks,
										  BlockPos from, Collection<BlockPos> seen) {
		BlockPos best = null;
		double bestScore = Double.POSITIVE_INFINITY;
		for (Holder<Structure> s : structures) {
			BlockPos at = StructureIndex.nearest(level, List.of(s), from, seen);
			if (at == null) {
				continue;
			}
			double dx = at.getX() - from.getX(), dz = at.getZ() - from.getZ();
			double score = Math.sqrt(dx * dx + dz * dz) / share(level, s, blocks);
			if (score < bestScore) {
				bestScore = score;
				best = at;
			}
		}
		return best;
	}

	// ---- building it ---------------------------------------------------------------------

	/** At server start, after StructureIndex: read the table, or start building it in the background. */
	public static void start(MinecraftServer server) {
		stop();
		table = null;
		done = 0;
		List<ServerLevel> levels = new ArrayList<>();
		server.getAllLevels().forEach(levels::add);
		Map<ServerLevel, List<Holder<Structure>>> jobs = new HashMap<>();
		int count = 0;
		for (ServerLevel level : levels) {
			List<Holder<Structure>> list = new ArrayList<>(StructureIndex.present(level));
			list.removeIf(h -> h.unwrapKey().isEmpty());
			jobs.put(level, list);
			count += list.size();
		}
		total = count;
		String fingerprint = fingerprint(server, jobs);
		Path path = file(server);
		Map<ResourceKey<Level>, Map<ResourceKey<Structure>, Counts>> read = read(path, fingerprint, jobs);
		if (read != null) {
			done = total;
			table = read;
			HelperBot.LOGGER.info("HelperBot structure odds: read from {} ({} structures)", path.getFileName(), total);
			return;
		}
		Set<Identifier> templateIds = new HashSet<>();
		server.getStructureManager().listTemplates().forEach(templateIds::add);
		worker = new Thread(() -> build(server, jobs, templateIds, fingerprint, path), "HelperBot structure odds");
		worker.setDaemon(true);
		worker.setPriority(Thread.MIN_PRIORITY);
		worker.start();
		HelperBot.LOGGER.info("HelperBot structure odds: building for {} structures in the background", total);
	}

	/** Server stopping: drop a build that's under way (it's built again next time). */
	public static void stop() {
		if (worker != null) {
			worker.interrupt();
			worker = null;
		}
	}

	private static void build(MinecraftServer server, Map<ServerLevel, List<Holder<Structure>>> jobs, Set<Identifier> templateIds,
							  String fingerprint, Path path) {
		long start = System.nanoTime();
		Map<ResourceKey<Level>, Map<ResourceKey<Structure>, Counts>> built = new HashMap<>();
		Map<Identifier, Set<Block>> cache = new HashMap<>();
		for (Map.Entry<ServerLevel, List<Holder<Structure>>> e : jobs.entrySet()) {
			ServerLevel level = e.getKey();
			StructurePieceSerializationContext context = StructurePieceSerializationContext.fromLevel(level);
			Map<ResourceKey<Structure>, Counts> forLevel = new HashMap<>();
			for (Holder<Structure> structure : e.getValue()) {
				if (Thread.currentThread().isInterrupted()) {
					return;
				}
				forLevel.put(structure.unwrapKey().orElseThrow(), sample(level, structure, context, templateIds, cache));
				done++;
			}
			built.put(level.dimension(), forLevel);
		}
		HelperBot.LOGGER.info("HelperBot structure odds: {} structures built in {} ms", total, (System.nanoTime() - start) / 1_000_000);
		table = built;
		write(path, fingerprint, built);
	}

	/** Put it together SAMPLES times at random places, and count what its pieces put down. */
	private static Counts sample(ServerLevel level, Holder<Structure> structure, StructurePieceSerializationContext context,
								 Set<Identifier> templateIds, Map<Identifier, Set<Block>> cache) {
		var generator = level.getChunkSource().getGenerator();
		Random random = new Random(level.getSeed() ^ structure.unwrapKey().orElseThrow().identifier().hashCode());
		Map<Block, Integer> blocks = new HashMap<>();
		int valid = 0;
		long started = System.nanoTime();
		for (int i = 0; i < SAMPLES; i++) {
			if (valid >= MIN_SAMPLES && System.nanoTime() - started > STRUCTURE_BUDGET_NANOS) {
				break;
			}
			ChunkPos at = new ChunkPos(random.nextInt(2 * SPREAD_CHUNKS) - SPREAD_CHUNKS, random.nextInt(2 * SPREAD_CHUNKS) - SPREAD_CHUNKS);
			try {
				StructureStart start = structure.value().generate(structure, level.dimension(), level.registryAccess(), generator,
						generator.getBiomeSource(), level.getChunkSource().randomState(), level.getServer().getStructureManager(),
						level.getSeed(), at, 0, level, b -> true);
				if (!start.isValid()) {
					continue;
				}
				Set<Block> here = new HashSet<>();
				for (StructurePiece piece : start.getPieces()) {
					here.addAll(StructureIndex.pieceBlocks(piece, context, templateIds, cache));
				}
				valid++;
				here.forEach(b -> blocks.merge(b, 1, Integer::sum));
			} catch (RuntimeException ex) {
				// one that won't come together here (a modded structure's own checks, say): the next place
			}
		}
		return new Counts(valid, blocks);
	}

	// ---- keeping it ----------------------------------------------------------------------

	private static Path file(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(FILE);
	}

	/** What the table is built from: if any of it changes (a mod, a data pack, another version), it's built again. */
	private static String fingerprint(MinecraftServer server, Map<ServerLevel, List<Holder<Structure>>> jobs) {
		StringBuilder text = new StringBuilder();
		text.append(FORMAT).append('|').append(SAMPLES).append('|').append(SharedConstants.getCurrentVersion().name())
				.append('|').append(server.overworld().getSeed());
		Set<String> structures = new TreeSet<>();
		jobs.forEach((level, list) -> list.forEach(h -> structures.add(level.dimension().identifier() + "/" + h.unwrapKey().orElseThrow().identifier())));
		text.append('|').append(String.join(",", structures));
		Set<String> templates = new TreeSet<>();
		server.getStructureManager().listTemplates().forEach(id -> templates.add(id.toString()));
		text.append('|').append(String.join(",", templates));
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			return Integer.toHexString(text.toString().hashCode());
		}
	}

	/**
	 * The table from the file, if it's there, whole, and built from what there is now (the
	 * fingerprint, and every structure there is in it with sensible counts); null otherwise.
	 */
	static Map<ResourceKey<Level>, Map<ResourceKey<Structure>, Counts>> read(Path path, String fingerprint,
																			 Map<ServerLevel, List<Holder<Structure>>> jobs) {
		if (!Files.isRegularFile(path)) {
			return null;
		}
		try {
			JsonObject root = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), JsonObject.class);
			if (root == null || root.get("format") == null || root.get("format").getAsInt() != FORMAT
					|| root.get("fingerprint") == null || !fingerprint.equals(root.get("fingerprint").getAsString())) {
				HelperBot.LOGGER.info("HelperBot structure odds: {} is from another world, version or set of mods; building again", path.getFileName());
				return null;
			}
			JsonObject dims = root.getAsJsonObject("dimensions");
			Map<ResourceKey<Level>, Map<ResourceKey<Structure>, Counts>> out = new HashMap<>();
			for (Map.Entry<ServerLevel, List<Holder<Structure>>> e : jobs.entrySet()) {
				JsonObject dim = dims.getAsJsonObject(e.getKey().dimension().identifier().toString());
				Map<ResourceKey<Structure>, Counts> forLevel = new HashMap<>();
				for (Holder<Structure> h : e.getValue()) {
					ResourceKey<Structure> key = h.unwrapKey().orElseThrow();
					JsonObject one = dim.getAsJsonObject(key.identifier().toString());
					int valid = one.get("valid").getAsInt();
					if (valid < 0 || valid > SAMPLES) {
						return broken(path, "样本数不对");
					}
					Map<Block, Integer> blocks = new HashMap<>();
					for (Map.Entry<String, com.google.gson.JsonElement> b : one.getAsJsonObject("blocks").entrySet()) {
						int n = b.getValue().getAsInt();
						Identifier id = Identifier.tryParse(b.getKey());
						if (n < 0 || n > valid || id == null) {
							return broken(path, "计数不对");
						}
						BuiltInRegistries.BLOCK.getOptional(id).ifPresent(block -> blocks.put(block, n));
					}
					forLevel.put(key, new Counts(valid, blocks));
				}
				out.put(e.getKey().dimension(), forLevel);
			}
			return out;
		} catch (IOException | RuntimeException e) {
			return broken(path, e.toString());
		}
	}

	private static <T> T broken(Path path, String why) {
		HelperBot.LOGGER.warn("HelperBot structure odds: {} can't be used ({}); building again", path.getFileName(), why);
		return null;
	}

	/** Whole or not at all: to a temporary file, then moved over the old one. A failure only costs building it again next start. */
	static void write(Path path, String fingerprint, Map<ResourceKey<Level>, Map<ResourceKey<Structure>, Counts>> built) {
		JsonObject root = new JsonObject();
		root.addProperty("format", FORMAT);
		root.addProperty("fingerprint", fingerprint);
		root.addProperty("samples", SAMPLES);
		root.addProperty("version", SharedConstants.getCurrentVersion().name());
		JsonObject dims = new JsonObject();
		built.forEach((dim, structures) -> {
			JsonObject d = new JsonObject();
			structures.forEach((key, counts) -> {
				JsonObject one = new JsonObject();
				one.addProperty("valid", counts.valid());
				JsonObject blocks = new JsonObject();
				counts.blocks().forEach((block, n) -> blocks.addProperty(BuiltInRegistries.BLOCK.getKey(block).toString(), n));
				one.add("blocks", blocks);
				d.add(key.identifier().toString(), one);
			});
			dims.add(dim.identifier().toString(), d);
		});
		root.add("dimensions", dims);
		Path tmp = path.resolveSibling(FILE + ".tmp");
		try {
			Files.createDirectories(path.getParent());
			Files.writeString(tmp, GSON.toJson(root), StandardCharsets.UTF_8);
			try {
				Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
			}
			HelperBot.LOGGER.info("HelperBot structure odds: kept in {}", path);
		} catch (IOException | RuntimeException e) {
			HelperBot.LOGGER.warn("HelperBot structure odds: couldn't keep it in {} ({}); it's used from memory and built again next start", path, e.toString());
			try {
				Files.deleteIfExists(tmp);
			} catch (IOException ignored) {
				// nothing more to do
			}
		}
	}

	// ---- for the scenes --------------------------------------------------------------------

	/** The file it's kept in (see the class comment). */
	public static Path fileFor(MinecraftServer server) {
		return file(server);
	}

	/** Write the table there now (a scene checking it comes back the same), true if it's there afterwards. */
	public static boolean writeNow(MinecraftServer server) {
		Map<ResourceKey<Level>, Map<ResourceKey<Structure>, Counts>> t = table;
		if (t == null) {
			return false;
		}
		write(file(server), fingerprint(server, jobsNow(server)), t);
		return Files.isRegularFile(file(server));
	}

	/** Would the file be taken as it is now (whole, from this world)? */
	public static boolean fileReadable(MinecraftServer server) {
		Map<ServerLevel, List<Holder<Structure>>> jobs = jobsNow(server);
		return read(file(server), fingerprint(server, jobs), jobs) != null;
	}

	private static Map<ServerLevel, List<Holder<Structure>>> jobsNow(MinecraftServer server) {
		Map<ServerLevel, List<Holder<Structure>>> jobs = new HashMap<>();
		for (ServerLevel level : server.getAllLevels()) {
			List<Holder<Structure>> list = new ArrayList<>(StructureIndex.present(level));
			list.removeIf(h -> h.unwrapKey().isEmpty());
			jobs.put(level, list);
		}
		return jobs;
	}
}
