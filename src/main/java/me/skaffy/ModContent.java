package me.skaffy;

import java.util.EnumMap;
import java.util.Map;
import me.skaffy.block.RealisticSandBlock;
import me.skaffy.block.RealisticWaterBlock;
import me.skaffy.fluid.RealisticWaterFluid;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;

/**
 * Blocks, fluids and items for every {@link Material}. Registry ids keep the original names for water and sand
 * ({@code realistic_water}, {@code realistic_sand}, {@code realistic_water_bucket}) so existing worlds still load.
 */
public final class ModContent {
	private static final Map<Material, FlowingFluid> SOURCES = new EnumMap<>(Material.class);
	private static final Map<Material, FlowingFluid> FLOWING = new EnumMap<>(Material.class);
	private static final Map<Material, Block> BLOCKS = new EnumMap<>(Material.class);
	private static final Map<Material, Item> ITEMS = new EnumMap<>(Material.class);

	static {
		registerFluid(Material.WATER, MapColor.WATER);
		registerFluid(Material.MILK, MapColor.SNOW);
		registerFluid(Material.SUSPICIOUS_MILK, MapColor.QUARTZ);
		registerFluid(Material.LAVA, MapColor.FIRE);
		registerGranular(Material.SAND, MapColor.SAND, SoundType.SAND, NoteBlockInstrument.SNARE);
		registerGranular(Material.RED_SAND, MapColor.COLOR_ORANGE, SoundType.SAND, NoteBlockInstrument.SNARE);
		registerGranular(Material.GRAVEL, MapColor.STONE, SoundType.GRAVEL, NoteBlockInstrument.SNARE);
		registerGranular(Material.SNOW, MapColor.SNOW, SoundType.SNOW, NoteBlockInstrument.HARP);
	}

	public static final FlowingFluid REALISTIC_WATER = SOURCES.get(Material.WATER);
	public static final FlowingFluid FLOWING_REALISTIC_WATER = FLOWING.get(Material.WATER);
	public static final Block REALISTIC_WATER_BLOCK = BLOCKS.get(Material.WATER);
	public static final Item REALISTIC_WATER_BUCKET = ITEMS.get(Material.WATER);
	public static final Block REALISTIC_SAND = BLOCKS.get(Material.SAND);
	public static final Item REALISTIC_SAND_ITEM = ITEMS.get(Material.SAND);

	private ModContent() {
	}

	private static void registerFluid(Material m, MapColor color) {
		String base = "realistic_" + m.id;
		FlowingFluid flowing = Registry.register(BuiltInRegistries.FLUID, RealisticWater.id("flowing_" + base), new RealisticWaterFluid.Flowing(m));
		FlowingFluid source = Registry.register(BuiltInRegistries.FLUID, RealisticWater.id(base), new RealisticWaterFluid.Source(m));
		FLOWING.put(m, flowing);
		SOURCES.put(m, source);

		ResourceKey<Block> blockKey = ResourceKey.create(Registries.BLOCK, RealisticWater.id(base));
		Block block = Registry.register(BuiltInRegistries.BLOCK, blockKey, new RealisticWaterBlock(source,
			BlockBehaviour.Properties.of()
				.mapColor(color)
				.replaceable()
				.noCollission()
				.strength(100.0F)
				.pushReaction(PushReaction.DESTROY)
				.noLootTable()
				.liquid()
				.sound(SoundType.EMPTY)
				.lightLevel(state -> m == Material.LAVA ? 15 : 0)
				.setId(blockKey)));
		BLOCKS.put(m, block);

		ResourceKey<Item> bucketKey = ResourceKey.create(Registries.ITEM, RealisticWater.id(base + "_bucket"));
		ITEMS.put(m, Registry.register(BuiltInRegistries.ITEM, bucketKey,
			new BucketItem(source, new Item.Properties().craftRemainder(Items.BUCKET).stacksTo(1).setId(bucketKey))));
	}

	private static void registerGranular(Material m, MapColor color, SoundType sound, NoteBlockInstrument instrument) {
		ResourceKey<Block> blockKey = ResourceKey.create(Registries.BLOCK, RealisticWater.id("realistic_" + m.id));
		Block block = Registry.register(BuiltInRegistries.BLOCK, blockKey, new RealisticSandBlock(m,
			BlockBehaviour.Properties.of()
				.mapColor(color)
				.instrument(instrument)
				.strength(0.5F)
				.sound(sound)
				.noOcclusion()
				.pushReaction(PushReaction.DESTROY)
				.isSuffocating((state, level, pos) -> false)
				.isViewBlocking((state, level, pos) -> false)
				.setId(blockKey)));
		BLOCKS.put(m, block);

		ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, RealisticWater.id("realistic_" + m.id));
		BlockItem item = new BlockItem(block, new Item.Properties().useBlockDescriptionPrefix().setId(itemKey));
		item.registerBlocks(Item.BY_BLOCK, item);
		ITEMS.put(m, Registry.register(BuiltInRegistries.ITEM, itemKey, item));
	}

	public static Block block(Material m) {
		return BLOCKS.get(m);
	}

	/** The bucket (fluids) or block item (granular materials) for a material. */
	public static Item item(Material m) {
		return ITEMS.get(m);
	}

	public static Item bucket(Material m) {
		return ITEMS.get(m);
	}

	public static FlowingFluid sourceFluid(Material m) {
		return SOURCES.get(m);
	}

	public static FlowingFluid flowingFluid(Material m) {
		return FLOWING.get(m);
	}

	static void init() {
		ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.TOOLS_AND_UTILITIES).register(entries -> {
			entries.addAfter(Items.WATER_BUCKET, item(Material.WATER));
			entries.addAfter(Items.MILK_BUCKET, item(Material.MILK));
			entries.addAfter(item(Material.MILK), item(Material.SUSPICIOUS_MILK));
			entries.addAfter(Items.LAVA_BUCKET, item(Material.LAVA));
		});
		ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.NATURAL_BLOCKS).register(entries -> {
			entries.addAfter(Items.SAND, item(Material.SAND));
			entries.addAfter(Items.RED_SAND, item(Material.RED_SAND));
			entries.addAfter(Items.GRAVEL, item(Material.GRAVEL));
			entries.addAfter(Items.SNOW_BLOCK, item(Material.SNOW));
		});
	}
}
