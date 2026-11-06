plugins {
    id("dev.kikugie.stonecutter")
}
// Stonecutter activation is controlled from settings.gradle.kts (generate all variants by default)
stonecutter {
    handlers {
        inherit("json5", "json")
    }
    // Activate the variant for local verification
    active("1.20.1-fabric")
    gradle.rootProject.allprojects.forEach { p ->
        if (p.name.startsWith("1.20.1")) {
            val ext = p.extensions.findByType(dev.kikugie.stonecutter.build.StonecutterBuildExtension::class.java)
            if (ext != null) {
                ext.replacements.string(object : org.gradle.api.Action<dev.kikugie.stonecutter.build.config.ReplacementContainer.StringReplacementSpec> {
                    override fun execute(spec: dev.kikugie.stonecutter.build.config.ReplacementContainer.StringReplacementSpec) {
                        spec.direction.set(true)
                        spec.replace("net.caffeinemc.mods.sodium.api.util.", "net.caffeinemc.mods.sodium.api.util.")
                        spec.replace("net.caffeinemc", "me.jellysquid")
                        spec.replace("net/caffeinemc", "me/jellysquid")
                        spec.replace("ResourceLocation.parse(", "ResourceLocation.tryParse(")
                        spec.replace("ResourceLocation.fromNamespaceAndPath(", "new ResourceLocation(")
                        spec.replace("net.minecraft.world.level.chunk.status.ChunkStatus", "net.minecraft.world.level.chunk.ChunkStatus")
                        spec.replace("NbtIo.readCompressed(new ByteArrayInputStream(data), NbtAccounter.unlimitedHeap())", "NbtIo.readCompressed(new ByteArrayInputStream(data))")
                        spec.replace("state.isError()", "state.error().isPresent()")
                        spec.replace("state.getOrThrow()", "state.getOrThrow(false, Logger::error)")
                        spec.replace("blockStatesRes.getPartialOrThrow()", "blockStatesRes.getOrThrow(true, Logger::error)")
                        spec.replace("ZipFile.builder().setFile(zip).get()", "new ZipFile(zip)")
                        spec.replace("info.isRealm()", "Minecraft.getInstance().isConnectedToRealms()")
                        spec.replace("addVertex(", "vertex(")
                        spec.replace("setColor(", "color(")
                        spec.replace("setUv(", "uv(")
                        spec.replace("setUv1(", "overlayCoords(")
                        spec.replace("setUv2(", "uv2(")
                        spec.replace("setNormal(", "normal(")
                        spec.replace("Streams.of", "Arrays.stream")
                    }
                })
            }
        }
    }
}