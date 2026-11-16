plugins {
	java
}

group = "me.morty517"
version = "1.0.0"

repositories {
	// Paper's repository mirrors Maven Central too; listed first so builds don't depend on Central's rate limits.
	maven("https://repo.papermc.io/repository/maven-public/")
	mavenCentral()
}

dependencies {
	compileOnly("io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT")
}

java {
	toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks.processResources {
	inputs.property("version", project.version)
	filesMatching("plugin.yml") {
		expand("version" to project.version)
	}
}

tasks.jar {
	archiveFileName.set("EnhancedWSRelay-${project.version}.jar")
}
