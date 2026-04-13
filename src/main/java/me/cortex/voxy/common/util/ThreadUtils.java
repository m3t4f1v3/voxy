package me.cortex.voxy.common.util;

import org.lwjgl.system.Platform;

public class ThreadUtils {

	public static boolean isWindows = Platform.get() == Platform.WINDOWS;
    public static boolean isLinux = Platform.get() == Platform.LINUX;
    
}
