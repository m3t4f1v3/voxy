package me.cortex.voxy.client.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.jellysquid.mods.sodium.client.gui.SodiumOptionsGUI;
import net.minecraft.client.gui.screens.Screen;

public class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> {
            if (VoxyCommon.isAvailable()) {
				//Sorry jelly and douira, please dont hurt me
				
                SodiumOptionsGUI screen = null;
				if (Arrays.stream(SodiumOptionsGUI.class.getMethods()).anyMatch(method -> method.getName().equals("createScreen"))) {
					screen = (SodiumOptionsGUI) SodiumOptionsGUI.createScreen(parent);
				}
				
                try {
					if (screen == null) {
						// Embeddium support
						Constructor<SodiumOptionsGUI> constructor = SodiumOptionsGUI.class.getDeclaredConstructor(Screen.class);
						constructor.setAccessible(true);
						screen = constructor.newInstance(parent);
					}
					
                    //We cant use .setPage() as that invokes rebuildGui, however the screen hasnt been initalized yet
                    // causing things to crash
                    var field = SodiumOptionsGUI.class.getDeclaredField("currentPage");
                    field.setAccessible(true);
                    field.set(screen, VoxyConfigScreenPages.voxyOptionPage);
                    field.setAccessible(false);
                } catch (Exception e) {
                    Logger.error("Failed to set the current page to voxy", e);
                }
                return screen;
            } else {
                return null;
            }
        };
    }
}
