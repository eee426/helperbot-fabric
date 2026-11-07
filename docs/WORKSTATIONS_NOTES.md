# 工作方块和药水：实验分支的思路

`feature/potions-workstations`（提交 `e6ad15e`，2026-11-01）是一次实验，**没有合并，分支已经删除**。它从很早的版本分出来，到删除时 main 已经往前走了 83 个提交，直接合并不亚于重写。这里留下它的思路，以后在 main 上重新做。

它没有在游戏里测过，下面的代码只说明思路，用之前要重新确认。

## 它加了什么

| 内容 | 新的计划动作 | 新的执行步骤 |
|---|---|---|
| 高炉、烟熏炉 | 沿用 `Smelt`，配方多记一个 `station` | 沿用 `SmeltStep`，放哪种炉子看配方 |
| 切石机 | `Cut` | `CutStep` |
| 锻造台（只做升级，不做纹饰） | `Smith` | `SmithStep` |
| 酿造台 | `Brew` | `BrewStep` |
| 药箭 | `Tip` | `TipStep` |
| 装水瓶 | `Fill`（当时只装瓶子） | `FetchWaterTask` 多一个“装什么、装几个” |

**有意不做：** 附魔、铁砧、织布机。

## 实用价值

- **马上有用：** 切石机、高炉、烟熏炉。材料都在主世界，优先做。
- **要玩家给材料：** 酿造。酿造台要烈焰棒，燃料是烈焰粉，几乎所有药水还要下界疣，这些都在下界。
- **基本用不上：** 下界合金升级要下界材料和只在结构里出现的锻造模板。药箭要滞留药水，也就是要龙息。

机器人去不了下界之前，酿造和锻造只能靠玩家放材料进背包。

## 值得抄的思路

### 1. 计划里算“物品 + 药水”：`Stuff`

所有药水都是同一个物品 `minecraft:potion`，靠组件区分；喷溅药水、滞留药水、药箭也一样。只用 `Item` 记账，治疗药水和水瓶就是一回事。

```java
public record Stuff(Item item, Holder<Potion> potion) {   // potion 为 null：普通物品
	public static Stuff of(Item item) { return new Stuff(item, null); }
	public static Stuff of(ItemStack stack) {
		PotionContents c = stack.get(DataComponents.POTION_CONTENTS);
		return new Stuff(stack.getItem(), c == null ? null : c.potion().orElse(null));
	}
	public boolean matches(ItemStack s) { /* 同物品、同药水 */ }
	public ItemStack stack(int count)   { /* PotionContents.createItemStack(item, potion) */ }
	public Component name()             { return stack(1).getHoverName(); }
}
```

分支把规划器的虚拟背包、`noGather`、`RecipeIndex` 的键、`MobDrops` 的键、任务的 `item` 全换成了 `Stuff`。

- **指令：** `BotCommands` 用 `Stuff.of(ItemArgument...createItemStack(1, false))`，所以 `/bot get potion[potion_contents={potion:"minecraft:healing"}]` 能直接用。
- **生物掉落：** 也按 `Stuff` 记。骷髅掉的药箭带药水，不能和普通药箭混在一起。

**注意：** 这一步改动面最大，只有真的要做酿造或药箭时才需要。只做切石机、高炉、烟熏炉，完全不用 `Stuff`。

### 2. 配方全部从游戏读，不手写

```java
// 烹饪类：看配方类型决定工作方块
Item station = type == RecipeType.SMELTING ? Items.FURNACE
		: type == RecipeType.BLASTING ? Items.BLAST_FURNACE
		: type == RecipeType.SMOKING ? Items.SMOKER : null;

// 切石机：recipe instanceof StonecutterRecipe，input() 是原料
// 锻造：只收 SmithingTransformRecipe（纹饰不改变物品，不要）
//       templateIngredient()、baseIngredient()、additionIngredient()，后者可能为空
```

### 3. 酿造表列不出来，就挨个问

`PotionBrewing` 没有列出全部配方的接口，所以穷举：所有药水 × 三种瓶子 × 所有酿造材料。

```java
PotionBrewing brewing = level.getServer().potionBrewing();
// 先筛出材料：brewing.isIngredient(new ItemStack(item))
for (Holder<Potion> potion : BuiltInRegistries.POTION.asHolderIdMap()) {
	for (Item bottle : new Item[]{Items.POTION, Items.SPLASH_POTION, Items.LINGERING_POTION}) {
		ItemStack input = PotionContents.createItemStack(bottle, potion);
		for (Item ingredient : ingredients) {
			ItemStack ing = new ItemStack(ingredient);
			if (!brewing.hasMix(input, ing)) continue;
			ItemStack output = brewing.mix(ing, input.copy());
			if (output.isEmpty() || Stuff.of(output).equals(Stuff.of(input))) continue;
			// 记下：output ← input + ingredient
		}
	}
}
```

好处是数据包和其他模组加的酿造配方也能用。

药箭的配方是动态的（`isSpecial`），普通读配方的流程会跳过它。分支的做法是：看到 `TippedArrowRecipe` 就记一下，然后给每种药水生成“滞留药水 + 8 支箭”。

### 4. 执行步骤走原版菜单

切石、锻造、药箭都新建原版菜单对象，按玩家点击的顺序操作，结果和玩家一样。

```java
// 切石机
menu = new StonecutterMenu(0, inv, ContainerLevelAccess.create(level, pos));
menu.getSlot(StonecutterMenu.INPUT_SLOT).set(input);
menu.slotsChanged(menu.container);                // 不调用这句，配方列表是空的
menu.clickMenuButton(bot, index);                 // index 在 menu.getVisibleRecipes().entries() 里找
menu.quickMoveStack(bot, StonecutterMenu.RESULT_SLOT);
menu.removed(bot);                                // 结束时调用，剩下的原料回背包

// 锻造台
menu = new SmithingMenu(0, inv, ContainerLevelAccess.create(level, pos));
// 依次放 TEMPLATE_SLOT、BASE_SLOT、ADDITIONAL_SLOT，然后：
menu.createResult();
menu.quickMoveStack(bot, SmithingMenu.RESULT_SLOT);

// 药箭：配方书放不了，手动填 CraftingMenu 的 1～9 格，第 5 格放药水，其余放箭，再取 0 号格
```

**判断成功的方法：** 取结果前后各数一次背包里的成品，数量没增加就算失败。不要相信方法的返回值。

酿造台没有用菜单，和 `SmeltStep` 一样直接操作方块实体：0～2 号格放瓶子，3 号格放材料，4 号格放烈焰粉。酿造完成的标志是 3 号格的材料被用掉。原版一批 400 刻，分支超过 700 刻算失败。

### 5. 规划成本

- **酿造：** 每 3 瓶一批，每批一份材料，1 个烈焰粉够酿 20 次，成本 `固定 40 + 400 × 批数`。
- **切石和锻造：** `固定 40 + 10 × 次数`。
- **炉子：** 高炉、烟熏炉烧得快，配方的 `cookingTime` 本来就短，所以直接比较成本，不用特判。做一个新炉子不划算时，它自然选普通熔炉。
- **缺烈焰粉：** 不假设有燃料，直接说明“只能在下界获得”。

### 6. 执行层的小工具

- **`WorkStep` 接口：** `tick()`、`failure()`、`describe()`。`AcquireTask` 只需要一个 `WORK` 阶段，不用每种工作方块各加一个分支。
- **`Bag`：** 从背包拿东西、数东西、放回去（满了就掉在脚下）。现在 `SmeltStep` 里有一份私有的 `take`，可以一起收进去。
- **切石和锻造的状态流程都一样：** 放方块 → 操作 → 收回。可以抽成一份，分支里是复制的。

## 在现在的 main 上怎么做

分支之后，规划器变了很多，下面这些**不能照搬**：

| 地方 | 分支的写法 | main 现在 |
|---|---|---|
| 配方筛选 | 所有配方全搜 | 有估算图，要改 `acquire`、`estimateFrom`、`localEstimate` 三处 |
| `Station` | `new Station(item, block)` | 多一个 `OutOfReach` 参数 |
| `Fill` | 装玻璃瓶 | 装水桶，用来拿 `WATER_BUCKET` |
| 目标物品 | 背包里的目标也算材料 | 有 `reserved`，目标不能当自己的材料 |
| `MAX_DEPTH` | 10 | 16 |
| 记账 | `Stuff` | `Item` |

建议顺序：

1. **先统一“来源”：** 现在加一种来源要改规划器三处，再加 `AcquireTask` 的多个 switch。先做成一个接口，后面每种工作方块只加一个实现。
2. **`WorkStep` 和 `Bag`：** 先把 `CraftStep`、`SmeltStep` 接上，不改变行为，场景应该全过。
3. **高炉、烟熏炉：** 最小的一步，只是配方多一个 `station`。
4. **切石机。**
5. **需要药水时再做：** `Stuff`、酿造、装水瓶、药箭。把水瓶和水桶合成一种“装水”来源。锻造放到机器人能拿到下界材料之后。

## 分支写的测试（当时只写在清单里，都没跑过）

| 编号 | 内容 | 应该怎样 |
|---|---|---|
| C24 | 有圆石和切石机，要 8 个圆石台阶 | 计划里有切石 |
| C25 | 有烟熏炉、生牛肉、燃料，要牛排 | 用烟熏炉；做一个不划算时用普通熔炉 |
| C26 | 给齐酿造材料和烈焰粉，要治疗药水 | 酿出来 |
| C27 | 同上但没有烈焰粉 | 一开始就说明缺烈焰粉，不乱跑 |
| C28 | 要治疗药箭 | 说明需要龙息，做不出来 |
| C29 | 改完物品记账后 | 门、粉色羊毛、粘土、皮革和熔炼都不受影响 |
