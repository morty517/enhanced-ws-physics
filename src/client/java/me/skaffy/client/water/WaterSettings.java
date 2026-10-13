package me.skaffy.client.water;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.TreeMap;

public final class WaterSettings {
	public static int resolution = 6;
	public static int boxMargin = 6;

	public static float cfl = 2.0F;
	public static float maxDt = 1.0F / 40.0F;
	public static int maxStepsPerFrame = 4;
	public static int renderMaxStepsPerFrame = 64;
	public static int cgIterations = 5;
	public static int mgCoarseSweeps = 12;
	public static int extrapolationLayers = 4;
	public static float flipRatio = 0.99F;
	public static float volumeCorrection = 2.0F;
	public static float maxSpeed = 40.0F;
	public static float gravity = 9.81F;

	public static boolean whitewater = true;
	public static float wwTrappedAirRate = 30.0F;
	public static float wwWaveCrestRate = 45.0F;
	public static float wwEnergyMin = 1.0F;
	public static float wwEnergyMax = 6.0F;
	public static float wwTrappedAirMin = 2.0F;
	public static float wwTrappedAirMax = 14.0F;
	public static float wwWaveCrestMin = 0.4F;
	public static float wwWaveCrestMax = 2.5F;
	public static float wwFoamLifeMin = 1.5F;
	public static float wwFoamLifeMax = 4.0F;
	public static float wwBubbleBuoyancy = 2.5F;
	public static float wwBubbleDrag = 0.6F;
	public static float wwSprayDrag = 0.4F;
	public static float wwSprayThreshold = 3.0F;
	public static float wwBubbleThreshold = 6.0F;
	public static int wwMaxPerParticle = 4;
	public static int wwCellLimit = 12;

	public static float filmFriction = 40.0F;
	public static float filmDepth = 0.08F;
	public static boolean sheetFill = false;
	public static float sheetSmoothing = 2.0F;
	public static float strandedTime = 1.5F;
	public static int strandedNeighbors = 12;

	public static int surfaceSubdivision = 2;
	public static float surfaceKernel = 3.0F;
	public static float surfaceRadius = 0.9F;
	public static float surfaceIso = 0.5F;
	public static float dropletThreshold = 0.35F;
	public static float nearCull = 0.05F;

	public static float particleRadius = 0.4F;
	public static float thicknessScale = 1.0F;
	public static float refraction = 0.15F;
	public static float absorbR = 0.45F;
	public static float absorbG = 0.12F;
	public static float absorbB = 0.07F;
	public static float scatterR = 0.03F;
	public static float scatterG = 0.08F;
	public static float scatterB = 0.11F;
	public static float scatterDensity = 0.5F;
	public static float underwaterFog = 0.1F;
	public static float surfaceSmoothing = 0.6F;
	public static float detailStrength = 0.4F;
	public static float detailScale = 6.0F;
	public static float detailSpeed = 1.4F;
	public static float roughness = 0.06F;
	public static float specular = 6.0F;
	public static float wetDarkening = 0.3F;
	public static float filmShading = 0.25F;
	public static float wwRadius = 0.012F;
	public static float wwMaxPixels = 2.5F;
	public static float foamCoverage = 1.2F;
	public static float foamSize = 3.0F;
	public static float foamBubbleSize = 0.05F;
	public static float bubbleAlpha = 0.25F;
	public static float sprayAlpha = 0.35F;

	public static float sourceFill = 0.9F;
	public static boolean bucketMakesSource = false;

	public static boolean flowSounds = true;
	public static float flowSoundVolume = 1.0F;

	public static int sandResolution = 12;
	public static float sandFrictionAngle = 15.0F;
	public static float sandCohesion = 0.015F;
	public static float sandStiffness = 62.5F;
	public static float sandWallFriction = 0.5F;
	public static float sandCfl = 0.35F;
	public static int sandMaxStepsPerFrame = 16;
	public static float sandImpactDamping = 0.5F;
	public static float sandImpactRate = 2.0F;
	public static float sandImpactCompression = 0.15F;
	public static float sandRestSpeed = 0.05F;
	public static float sandRestTime = 0.15F;
	public static float sandWakeAccel = 0.5F;
	public static float sandSurfaceIso = 0.5F;
	public static float sandSurfaceSmoothing = 0.6F;
	public static float sandGrainSize = 0.01F;
	public static int sandGrainsPerParticle = 32;
	public static float sandGrainSpread = 0.5F;
	public static float sandGrainMinPixels = 0.7F;
	public static float sandBrightness = 1.1F;
	public static boolean sandSounds = true;

	// Milk: the water solver with different optics (nearly opaque, strong white scattering).
	public static float milkAbsorbR = 30.0F;
	public static float milkAbsorbG = 30.5F;
	public static float milkAbsorbB = 32.0F;
	public static float milkScatterR = 0.97F;
	public static float milkScatterG = 0.95F;
	public static float milkScatterB = 0.90F;
	public static float milkScatterDensity = 30.0F;

	// Suspicious milk: translucent, greyish white, and lazy (low FLIP ratio, sticky film that takes long to dry).
	public static float suspiciousAbsorbR = 5.0F;
	public static float suspiciousAbsorbG = 5.0F;
	public static float suspiciousAbsorbB = 4.6F;
	public static float suspiciousScatterR = 0.86F;
	public static float suspiciousScatterG = 0.86F;
	public static float suspiciousScatterB = 0.84F;
	public static float suspiciousScatterDensity = 4.0F;
	public static float suspiciousFlipRatio = 0.8F;
	public static float suspiciousFilmFriction = 160.0F;
	public static float suspiciousStrandedTime = 8.0F;
	/** How fast touching fluids blend into each other (1/s); 0 keeps them apart like oil and water. */
	public static float fluidMixRate = 0.3F;

	// Red sand: the sand solver with the red sand texture and a touch more cohesion.
	public static float redSandFrictionAngle = 15.0F;
	public static float redSandCohesion = 0.02F;

	// Gravel: coarser, steeper and loose (no cohesion), drawn as fewer, bigger stones.
	public static float gravelFrictionAngle = 24.0F;
	public static float gravelCohesion = 0.0F;
	public static float gravelGrainSize = 0.035F;
	public static int gravelGrainsPerParticle = 6;
	public static float gravelBrightness = 1.0F;

	// Snow: cohesive and steep, holds walls and clumps; light, so water pushes it around easily.
	public static float snowFrictionAngle = 35.0F;
	public static float snowCohesion = 0.06F;
	public static float snowGrainSize = 0.012F;
	public static int snowGrainsPerParticle = 24;
	public static float snowBrightness = 1.0F;
	public static float snowWaterDrag = 9.0F;

	// Lava: its own simulation. Opaque, glowing, lazy and sticky; solidifies where it touches water.
	public static float lavaScatterR = 1.0F;
	public static float lavaScatterG = 0.36F;
	public static float lavaScatterB = 0.06F;
	public static float lavaScatterDensity = 60.0F;
	public static float lavaAbsorb = 60.0F;
	/** Brightness of the glow, independent of daylight. */
	public static float lavaEmission = 1.7F;
	/** How much of the surface is covered by dark cooling crust (0..1). */
	public static float lavaCrust = 0.45F;
	public static float lavaFlipRatio = 0.3F;
	/** How fast lava loses sideways speed (1/s): higher = thicker, slower lava. */
	public static float lavaViscosity = 10.0F;
	public static float lavaFilmFriction = 400.0F;
	public static float lavaStrandedTime = 60.0F;
	/** Chance per step that a lava particle touching water solidifies / a water particle touching lava boils away. */
	public static float lavaSolidifyRate = 0.06F;
	public static float lavaBoilRate = 0.3F;
	/** Fraction of a block's lava that has to solidify before the block turns to stone (obsidian when it is most of it). */
	public static float lavaSolidifyBlock = 0.12F;
	public static float lavaObsidianFraction = 0.45F;

	// Coupling between fluids and granular materials.
	public static boolean coupling = true;
	/** Fraction of a fully packed cell's grains above which a fluid cell counts as solid (moving with the grains). */
	public static float couplingSolidFraction = 0.5F;
	/** How fast submerged sand / gravel takes on the water's velocity (1/s). */
	public static float sandWaterDrag = 6.0F;
	public static float gravelWaterDrag = 2.5F;
	/** Fraction of gravity cancelled for submerged grains (water/grain density ratio). */
	public static float sandBuoyancy = 0.6F;

	public static String ffmpeg = "";
	public static boolean renderFreezeWorld = true;
	public static boolean renderSteadyPreview = true;
	public static float renderCameraSmoothing = 0.12F;

	private WaterSettings() {
	}

	public static Map<String, Field> fields() {
		Map<String, Field> out = new TreeMap<>();
		for (Field f : WaterSettings.class.getFields()) {
			if (Modifier.isStatic(f.getModifiers())) {
				out.put(f.getName(), f);
			}
		}
		return out;
	}

	public static String set(String name, String value) throws ReflectiveOperationException {
		Field f = fields().get(name);
		if (f == null) {
			throw new NoSuchFieldException(name);
		}
		Class<?> t = f.getType();
		if (t == int.class) {
			f.setInt(null, Integer.parseInt(value));
		} else if (t == float.class) {
			f.setFloat(null, Float.parseFloat(value));
		} else if (t == boolean.class) {
			f.setBoolean(null, Boolean.parseBoolean(value));
		} else if (t == String.class) {
			f.set(null, value.equals("-") ? "" : value);
		}
		return String.valueOf(f.get(null));
	}
}
