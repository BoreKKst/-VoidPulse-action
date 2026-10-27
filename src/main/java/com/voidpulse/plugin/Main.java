package com.voidpulse.plugin;

import org.bukkit.plugin.java.JavaPlugin;

public final class Main extends JavaPlugin {

    @Override
    public void onEnable() {
        getLogger().info("VoidPulse plugin successfully enabled!");
    }

    @Override
    public void onDisable() {
        getLogger().info("VoidPulse plugin disabled.");
    }
}
