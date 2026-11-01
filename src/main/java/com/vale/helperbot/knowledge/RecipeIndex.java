package com.vale.helperbot.knowledge;

import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The server's crafting and furnace recipes, indexed by what they make. Built from the live
 * recipe manager, so data packs and other mods' recipes are included.
 */
public final class RecipeIndex {
	/** A crafting recipe: one craft uses each ingredient once and makes {@code resultCount}. */
	public record Craft(RecipeHolder<CraftingRecipe> holder, Item result, int resultCount,
						List<Ingredient> ingredients, boolean needsTable) {
	}

	/** A furnace recipe: one input item makes {@code resultCount} after {@code cookingTime} ticks. */
	public record Smelt(RecipeHolder<AbstractCookingRecipe> holder, Item result, int resultCount,
						Ingredient input, int cookingTime) {
	}

	private static Map<Item, List<Craft>> crafts;
	private static Map<Item, List<Smelt>> smelts;

	private RecipeIndex() {
	}

	public static void clearCache() {
		crafts = null;
		smelts = null;
	}

	public static List<Craft> craftsFor(ServerLevel level, Item item) {
		build(level);
		return crafts.getOrDefault(item, List.of());
	}

	public static List<Smelt> smeltsFor(ServerLevel level, Item item) {
		build(level);
		return smelts.getOrDefault(item, List.of());
	}

	@SuppressWarnings("unchecked")
	private static void build(ServerLevel level) {
		if (crafts != null) {
			return;
		}
		crafts = new HashMap<>();
		smelts = new HashMap<>();
		ContextMap context = SlotDisplayContext.fromLevel(level);
		for (RecipeHolder<?> holder : level.getServer().getRecipeManager().getRecipes()) {
			Recipe<?> recipe = holder.value();
			if (recipe.isSpecial() || recipe.placementInfo().isImpossibleToPlace() || recipe.display().isEmpty()) {
				continue; // dynamic recipes (fireworks, map cloning...) have no fixed result
			}
			ItemStack result = recipe.display().get(0).result().resolveForFirstStack(context);
			if (result.isEmpty()) {
				continue;
			}
			List<Ingredient> ingredients = recipe.placementInfo().ingredients();
			if (recipe instanceof CraftingRecipe crafting && crafting.getType() == RecipeType.CRAFTING) {
				boolean needsTable = recipe instanceof ShapedRecipe shaped
						? shaped.getWidth() > 2 || shaped.getHeight() > 2
						: ingredients.size() > 4;
				crafts.computeIfAbsent(result.getItem(), k -> new ArrayList<>()).add(new Craft(
						(RecipeHolder<CraftingRecipe>) holder, result.getItem(), result.getCount(), ingredients, needsTable));
			} else if (recipe instanceof AbstractCookingRecipe cooking && cooking.getType() == RecipeType.SMELTING) {
				smelts.computeIfAbsent(result.getItem(), k -> new ArrayList<>()).add(new Smelt(
						(RecipeHolder<AbstractCookingRecipe>) holder, result.getItem(), result.getCount(),
						cooking.input(), cooking.cookingTime()));
			}
		}
		// Simplest recipes first: they're tried first when planning.
		crafts.values().forEach(list -> list.sort(Comparator.comparingInt(c -> c.ingredients().size())));
	}

	/** The items an ingredient accepts. */
	public static List<Item> options(Ingredient ingredient) {
		return ingredient.items().map(Holder::value).toList();
	}
}
