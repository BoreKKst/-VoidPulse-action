package com.voidpulse.plugin;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Set;

public final class Main extends JavaPlugin implements Listener, CommandExecutor {

    private final Set<Player> loggedIn = new HashSet<>();

    @Override
    public void onEnable() {
        if (!getDataFolder().exists()) {
            getDataFolder().mkdir();
        }
        File accountsDir = new File(getDataFolder(), "accounts");
        if (!accountsDir.exists()) {
            accountsDir.mkdir();
        }

        saveDefaultConfig();

        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("register") != null) getCommand("register").setExecutor(this);
        if (getCommand("reg") != null) getCommand("reg").setExecutor(this);
        if (getCommand("login") != null) getCommand("login").setExecutor(this);
        if (getCommand("l") != null) getCommand("l").setExecutor(this);

        getLogger().info("VoidPulse Auth с командами /reg и /l успешно запущен!");
    }

    @Override
    public void onDisable() {
        loggedIn.clear();
        getLogger().info("VoidPulse Auth отключен.");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        File playerFile = new File(getDataFolder(), "accounts/" + player.getName().toLowerCase() + ".yml");
        
        player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 99999, 0, false, false));

        if (!playerFile.exists()) {
            player.sendMessage(ChatColor.RED + "Пожалуйста, зарегистрируйся! Напиши: /reg <пароль>");
        } else {
            player.sendMessage(ChatColor.YELLOW + "Пожалуйста, авторизуйся! Напиши: /l <пароль>");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        loggedIn.remove(event.getPlayer());
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!loggedIn.contains(player)) {
            Location from = event.getFrom();
            Location to = event.getTo();
            if (to != null && (from.getX() != to.getX() || from.getZ() != to.getZ())) {
                player.teleport(from);
                player.sendMessage(ChatColor.RED + "Сначала авторизуйся! Используй /l или /reg");
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Только для игроков!");
            return true;
        }

        Player player = (Player) sender;
        File playerFile = new File(getDataFolder(), "accounts/" + player.getName().toLowerCase() + ".yml");
        String cmd = command.getName().toLowerCase();

        if (cmd.equals("register") || cmd.equals("reg")) {
            if (playerFile.exists()) {
                player.sendMessage(ChatColor.RED + "Ты уже зарегистрирован! Используй /l <пароль>");
                return true;
            }
            if (args.length == 0) {
                player.sendMessage(ChatColor.RED + "Использование: /reg <пароль>");
                return true;
            }

            String password = args[0];
            String passwordHash = hashPassword(password);

            try {
                playerFile.createNewFile();
                FileConfiguration config = YamlConfiguration.loadConfiguration(playerFile);
                config.set("name", player.getName());
                config.set("password", passwordHash);
                config.set("ip", player.getAddress() != null ? player.getAddress().getAddress().getHostAddress() : "unknown");
                config.save(playerFile);

                loggedIn.add(player);
                player.removePotionEffect(PotionEffectType.BLINDNESS);
                player.sendMessage(ChatColor.GREEN + "Регистрация успешна! Добро пожаловать на VoidPulse.");
            } catch (IOException e) {
                player.sendMessage(ChatColor.RED + "Ошибка при сохранении файла аккаунта!");
            }
            return true;
        }

        if (cmd.equals("login") || cmd.equals("l")) {
            if (!playerFile.exists()) {
                player.sendMessage(ChatColor.RED + "Ты еще не зарегистрирован! Используй /reg <пароль>");
                return true;
            }
            if (loggedIn.contains(player)) {
                player.sendMessage(ChatColor.YELLOW + "Ты уже вошел в аккаунт!");
                return true;
            }
            if (args.length == 0) {
                player.sendMessage(ChatColor.RED + "Использование: /l <пароль>");
                return true;
            }

            FileConfiguration config = YamlConfiguration.loadConfiguration(playerFile);
            String savedHash = config.getString("password", "");
            String inputHash = hashPassword(args[0]);

            if (savedHash.equals(inputHash)) {
                loggedIn.add(player);
                player.removePotionEffect(PotionEffectType.BLINDNESS);
                player.sendMessage(ChatColor.GREEN + "Успешный вход! Приятной игры.");
            } else {
                player.sendMessage(ChatColor.RED + "Неверный пароль!");
            }
            return true;
        }

        return false;
    }

    private String hashPassword(String password) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encodedhash = digest.digest(password.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : encodedhash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            return password;
        }
    }
}
