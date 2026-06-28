package me.cortex.voxy.client;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.commonImpl.VoxyCommon;

import java.nio.file.Path;

public class ClientSessionEvents {
    public static boolean inSession = false;

    // Full storage base path computed at handleLogin time, when all APIs are available.
    private static Path cachedBasePath;

    public static void sessionStart(Path basePath) {
        if (inSession) throw new IllegalStateException("Cannot start new session while in a session");
        inSession = true;
        ClientSessionEvents.cachedBasePath = basePath;

        //Should never try creating multiple instances via session start
        if (VoxyCommon.getInstance() != null) throw new IllegalStateException();

        if (VoxyCommon.isAvailable()) {
            if (VoxyConfig.CONFIG.enabled) {
                VoxyCommon.createInstance();
            }
        }
    }

    /**
     * Returns the base path computed at login time, or null if not in a session.
     */
    public static Path getCachedBasePath() {
        return cachedBasePath;
    }

    public static void sessionEnd() {
        if (!inSession) throw new IllegalStateException("Cannot end a session while not in a session");
        inSession = false;
        cachedBasePath = null;

        VoxyCommon.shutdownInstance();
    }
}
