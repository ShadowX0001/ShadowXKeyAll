package com.shadowx.keyall;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class ShadowXKeyAllExpansion extends PlaceholderExpansion {
    private final ShadowXKeyAll plugin;

    public ShadowXKeyAllExpansion(ShadowXKeyAll plugin) { this.plugin = plugin; }
    @Override public @NotNull String getIdentifier() { return "shadowxkeyall"; }
    @Override public @NotNull String getAuthor() { return "ShadowX0001"; }
    @Override public @NotNull String getVersion() { return plugin.getDescription().getVersion(); }
    @Override public boolean persist() { return true; }
    @Override public @Nullable String onPlaceholderRequest(Player player, @NotNull String params) {
        if (player == null) return "";
        String value = plugin.placeholderValue(params, player);
        return value == null ? "" : value;
    }
}
