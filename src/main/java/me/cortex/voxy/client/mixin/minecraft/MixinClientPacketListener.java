package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.ClientSessionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.world.level.storage.LevelResource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.file.Path;

@Mixin(ClientPacketListener.class)
public class MixinClientPacketListener {
    @Shadow
    private ServerData serverData;

    @Inject(method = "handleLogin", at = @At("HEAD"))
    private void voxy$init(ClientboundLoginPacket packet, CallbackInfo ci) {
        if (!ClientSessionEvents.inSession) {
            ClientSessionEvents.sessionStart(resolveBasePath());
        }
    }

    private Path resolveBasePath() {
        var mc = Minecraft.getInstance();
        Path basePath = mc.gameDirectory.toPath().resolve(".voxy").resolve("saves");

        var iserver = mc.getSingleplayerServer();
        if (iserver != null) {
            // Singleplayer: store in the world's own directory
            return iserver.getWorldPath(LevelResource.ROOT).resolve("voxy");
        }

        if (mc.isConnectedToRealms()) {
            return basePath.resolve("realms");
        }

        // Multiplayer: use the server IP from ClientPacketListener.serverData
        // which is available at handleLogin time (set in the constructor).
        var self = (ClientPacketListener)(Object)this;
        var info = self.getServerData();
        if (info != null) {
            return basePath.resolve(info.ip.replace(":", "_"));
        }

        return basePath.resolve("UNKNOWN");
    }
}
