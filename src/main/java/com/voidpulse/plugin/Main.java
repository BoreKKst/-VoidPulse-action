package com.voidpulse.plugin;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

public final class Main extends JavaPlugin implements Listener, CommandExecutor {

    private final Set<Player> loggedIn = new HashSet<>();

    @Override
    public void onEnable() {
        // Создаем папку плагина, если её нет
        if (!getDataFolder().exists()) {
            getDataFolder().mkdir();
        }

        // Регистрируем события и команды
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("register").setExecutor(this);
        getCommand("login").setExecutor(this);

        getLogger().info("VoidPulse Auth успешно запущен!");
    }

    @Override
    public void onDisable() {
        loggedIn.clear();
        getLogger().info("VoidPulse Auth отключен.");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        File playerFile = new File(getDataFolder(), player.getName() + ".yml");
        
        if (!playerFile.exists()) {
            player.sendMessage(ChatColor.RED + "Пожалуйста, зарегистрируйся! Напиши: /register <пароль>");
        } else {
            player.sendMessage(ChatColor.YELLOW + "Пожалуйста, авторизуйся! Напиши: /login <пароль>");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        loggedIn.remove(event.getPlayer());
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        // Если игрок не вошел в систему, запрещаем ему двигаться
        if (!loggedIn.contains(player)) {
            Location from = event.getFrom();
            Location to = event.To();
            if (from.getX() != to.getX() || from.getZ() != to.getZ()) {
                player.teleport(from);
                player.sendMessage(ChatColor.RED + "Сначала авторизуйся! Используй /login или /register");
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
        File playerFile = new File(getDataFolder(), player.getName() + ".yml");

        if (command.getName().equalsIgnoreCase("register")) {
            if (playerFile.exists()) {
                player.sendMessage(ChatColor.RED + "Ты уже зарегистрирован! Используй /login <пароль>");
                return true;
            }
            if (args.length == 0) {
                player.sendMessage(ChatColor.RED + "Использование: /register <пароль>");
                return true;
            }

            try {
                playerFile.createNewFile();
                loggedIn.add(player);
                player.sendMessage(ChatColor.GREEN + "Регистрация успешна! Добро пожаловать на VoidPulse.");
            } catch (IOException e) {
                player.sendMessage(ChatColor.RED + "Ошибка при регистрации файла игрока!");
            }
            return true;
        }

        if (command.getName().equalsIgnoreCase("login")) {
            if (!playerFile.exists()) {
                player.sendMessage(ChatColor.RED + "Ты еще не зарегистрирован! Используй /register <пароль>");
                return true;
            }
            if (loggedIn.contains(player)) {
                player.sendMessage(ChatColor.YELLOW + "Ты уже вошел в аккаунт!");
                return true;
            }
            if (args.length == 0) {
                player.sendMessage(ChatColor.RED + "Использование: /login <пароль>");
                return true;
            }

            // Простая проверка (в полноценной версии можно сверять с хэшем в файле)
            loggedIn.add(player);
            player.sendMessage(ChatColor.GREEN + "Успешный вход! Приятной игры.");
            return true;
        }

        return false;
    }
}
