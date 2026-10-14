plugins {
	id("fabric-loom")
	`maven-publish`
}

repositories {
}

loom {
	splitEnvironmentSourceSets()

	mods {
		register("realistic-water") {
			sourceSet(sourceSets.main.get())
			sourceSet(sourceSets.getByName("client"))
		}
	}

	runs {
		// Headless smoke test: ./gradlew runSelftest (see SelfTest.java). Not part of the built mod's behaviour.
		register("selftest") {
			client()
			name("Self Test")
			runDir("run-selftest")
			vmArg("-Drealisticwater.selftest=true")
			// extra -D flags for the test, e.g. -PselftestArgs="-Drealisticwater.selftest.resolution=6 -Drealisticwater.selftest.clip=true"
			providers.gradleProperty("selftestArgs").orNull?.split(" ")?.filter { it.isNotBlank() }?.forEach { vmArg(it) }
			programArgs("--width", "640", "--height", "360")
		}
	}
}

dependencies {
	minecraft("com.mojang:minecraft:${providers.gradleProperty("minecraft_version").get()}")
	mappings(loom.officialMojangMappings())
	modImplementation("net.fabricmc:fabric-loader:${providers.gradleProperty("loader_version").get()}")

	modImplementation("net.fabricmc.fabric-api:fabric-api:${providers.gradleProperty("fabric_api_version").get()}")
}

tasks.processResources {
	val version = version
	inputs.property("version", version)

	filesMatching("fabric.mod.json") {
		expand("version" to version)
	}
}

tasks.withType<JavaCompile>().configureEach {
	options.release = 21
}

java {
	withSourcesJar()

	sourceCompatibility = JavaVersion.VERSION_21
	targetCompatibility = JavaVersion.VERSION_21
}

tasks.jar {
	val projectName = project.name
	inputs.property("projectName", projectName)

	from("LICENSE") {
		rename { "${it}_${projectName}" }
	}
}

tasks.named<Jar>("sourcesJar") {
	val projectName = project.name
	inputs.property("projectName", projectName)

	from("LICENSE") {
		rename { "${it}_${projectName}" }
	}
}
