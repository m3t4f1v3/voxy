// package me.cortex.voxy.client;

// import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
// import me.cortex.voxy.client.core.VoxyRenderSystem;
// import me.cortex.voxy.commonImpl.VoxyCommon;
// import net.minecraft.client.MinecraftClient;
// import net.minecraft.client.gui.hud.debug.DebugHudEntry;
// import net.minecraft.client.gui.hud.debug.DebugHudLines;
// import net.minecraft.util.Colors;
// import net.minecraft.util.Formatting;
// import net.minecraft.util.Identifier;
// import net.minecraft.world.World;
// import net.minecraft.world.chunk.WorldChunk;
// import org.jetbrains.annotations.Nullable;

// import java.util.ArrayList;
// import java.util.List;

// public class VoxyDebugScreenEntry implements DebugHudEntry {
//     @Override
//     public void render(DebugHudLines lines, @Nullable World world, @Nullable WorldChunk clientChunk, @Nullable WorldChunk chunk) {
//         if (!VoxyCommon.isAvailable()) {
//             lines.addLine(Formatting.RED + "voxy-"+VoxyCommon.MOD_VERSION);//Voxy installed, not avalible
//             return;
//         }
//         var instance = VoxyCommon.getInstance();
//         if (instance == null) {
//             lines.addLine(Formatting.YELLOW + "voxy-" + VoxyCommon.MOD_VERSION);//Voxy avalible, no instance active
//             return;
//         }
//         VoxyRenderSystem vrs = null;
//         var wr = MinecraftClient.getInstance().worldRenderer;
//         if (wr != null) vrs = ((IGetVoxyRenderSystem) wr).getVoxyRenderSystem();

//         //Voxy instance active
//         lines.addLine((vrs==null?Formatting.DARK_GREEN:Formatting.GREEN)+"voxy-"+VoxyCommon.MOD_VERSION);

//         //lines.addLineToSection();
//         List<String> instanceLines = new ArrayList<>();
//         instance.addDebug(instanceLines);
//         lines.addLinesToSection(Identifier.of("voxy", "instance_debug"), instanceLines);

//         if (vrs != null) {
//             List<String> renderLines = new ArrayList<>();
//             vrs.addDebugInfo(renderLines);
//             lines.addLinesToSection(Identifier.of("voxy", "render_debug"), renderLines);
//         }
//     }


// }
