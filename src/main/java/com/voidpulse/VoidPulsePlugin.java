package com.voidpulse;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class VoidPulsePlugin extends JavaPlugin implements Listener {

    private final Map<UUID, String> pendingCaptcha = new ConcurrentHashMap<>();
    private final Set<UUID> unauthenticatedPlayers = ConcurrentHashMap.newKeySet();

    @Override
    public void onEnable() {
        saveDefaultConfig();

        File accountsDir = new File(getDataFolder(), "accounts");
        if (!accountsDir.exists()) {
            accountsDir.mkdirs();
        }

        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("§a[VoidPulse] Автономный плагин авторизации с капчей и системой спавнов успешно запущен!");
    }

    @Override
    public void onDisable() {
        getLogger().info("§c[VoidPulse] Плагин выключен.");
    }

    // --- ВХОД И ВЫДАЧА КАРТЫ-КАПЧИ ---

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        unauthenticatedPlayers.add(uuid);
        player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 999999, 1, false, false));

        // Телепортируем на временный спаун авторизации, если он настроен
        if (getConfig().contains("auth-spawn.world")) {
            String worldName = getConfig().getString("auth-spawn.world");
            if (Bukkit.getWorld(worldName) != null) {
                Location authSpawn = new Location(
                        Bukkit.getWorld(worldName),
                        getConfig().getDouble("auth-spawn.x"),
                        getConfig().getDouble("auth-spawn.y"),
                        getConfig().getDouble("auth-spawn.z"),
                        (float) getConfig().getDouble("auth-spawn.yaw"),
                        (float) getConfig().getDouble("auth-spawn.pitch")
                );
                player.teleport(authSpawn);
            }
        }

        // Генерируем код капчи
        String captchaCode = generateRandomCode();
        pendingCaptcha.put(uuid, captchaCode);

        // Создаем карту в руку с кодом
        ItemStack mapItem = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) mapItem.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', "&b&lVOIDPULSE CAPTCHA"));
            meta.setLore(Arrays.asList(
                    ChatColor.translateAlternateColorCodes('&', "&7Код подтверждения: &e&l" + captchaCode),
                    ChatColor.translateAlternateColorCodes('&', "&7Введите этот код в чат!")
            ));
            mapItem.setItemMeta(meta);
        }

        player.getInventory().setItemInMainHand(mapItem);
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', 
                "&c[VoidPulse] В вашей руке карта с кодом капчи. Напишите его в чат!"));
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        
        // Если игрок уже был авторизован при выходе, сохраняем его текущую позицию
        if (!unauthenticatedPlayers.contains(uuid)) {
            File accountFile = new File(getDataFolder(), "accounts/" + player.getName().toLowerCase() + ".yml");
            if (accountFile.exists()) {
                savePlayerLocation(player, accountFile);
            }
        }

        unauthenticatedPlayers.remove(uuid);
        pendingCaptcha.remove(uuid);
    }

    // --- ПРОВЕРКА КАПЧИ И ЧАТА ---

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (unauthenticatedPlayers.contains(uuid)) {
            event.setCancelled(true);

            if (pendingCaptcha.containsKey(uuid)) {
                String inputCode = event.getMessage().trim().toUpperCase();
                String correctCode = pendingCaptcha.get(uuid);

                if (inputCode.equals(correctCode)) {
                    pendingCaptcha.remove(uuid);
                    
                    Bukkit.getScheduler().runTask(this, () -> {
                        player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
                        File accountFile = new File(getDataFolder(), "accounts/" + player.getName().toLowerCase() + ".yml");
                        
                        if (accountFile.exists()) {
                            player.sendMessage(ChatColor.translateAlternateColorCodes('&', 
                                    "&a✅ Капча пройдена! Введите &e/login <пароль>"));
                        } else {
                            player.sendMessage(ChatColor.translateAlternateColorCodes('&', 
                                    "&a✅ Капча пройдена! Введите &e/reg <пароль>"));
                        }
                    });
                } else {
                    player.sendMessage(ChatColor.translateAlternateColorCodes('&', 
                            "&c❌ Неверный код капчи! Посмотрите на карту в руке."));
                }
                return;
            }

            player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&cСначала авторизуйтесь через /login или /reg!"));
        }
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (unauthenticatedPlayers.contains(event.getPlayer().getUniqueId())) {
            if (event.getFrom().getX() != event.getTo().getX() || event.getFrom().getZ() != event.getTo().getZ()) {
                event.setTo(event.getFrom());
            }
        }
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (unauthenticatedPlayers.contains(player.getUniqueId())) {
            String msg = event.getMessage().toLowerCase();
            // Разрешаем также админскую команду установки спавна
            if (!msg.startsWith("/reg") && !msg.startsWith("/login") && !msg.startsWith("/l ") && !msg.startsWith("/voidpulse:setspawn") && !msg.startsWith("/setspawn")) {
                event.setCancelled(true);
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&cСначала пройдите капчу и авторизуйтесь!"));
            }
        }
    }

    // --- КОМАНДЫ (setspawn, reg, login) ---

    @Override
    public boolean onCommand(org.bukkit.command.CommandSender sender, org.bukkit.command.Command command, String label, String[] args) {
        if (!(sender instanceof Player)) return true;
        Player player = (Player) sender;
        UUID uuid = player.getUniqueId();
        String name = player.getName().toLowerCase();
        File accountFile = new File(getDataFolder(), "accounts/" + name + ".yml");

        // Команда установки временного спавна для авторизации: /voidpulse:setspawn или /setspawn
        if (command.getName().equalsIgnoreCase("setspawn") || label.equalsIgnoreCase("voidpulse:setspawn")) {
            if (!player.hasPermission("voidpulse.admin") && !player.isOp()) {
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&cУ вас нет прав на выполнение этой команды!"));
                return true;
            }

            Location loc = player.getLocation();
            getConfig().set("auth-spawn.world", loc.getWorld().getName());
            getConfig().set("auth-spawn.x", loc.getX());
            getConfig().set("auth-spawn.y", loc.getY());
            getConfig().set("auth-spawn.z", loc.getZ());
            getConfig().set("auth-spawn.yaw", loc.getYaw());
            getConfig().set("auth-spawn.pitch", loc.getPitch());
            saveConfig();

            player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&a✅ Временный спаун для авторизации успешно установлен здесь!"));
            return true;
        }

        if (pendingCaptcha.containsKey(uuid)) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&cСначала введите код с карты в чат!"));
            return true;
        }

        if (command.getName().equalsIgnoreCase("reg")) {
            if (!unauthenticatedPlayers.contains(uuid)) {
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&cВы уже вошли в систему!"));
                return true;
            }
            if (accountFile.exists()) {
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&cАккаунт уже зарегистрирован! Используйте /login <пароль>"));
                return true;
            }
            if (args.length < 1) {
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&cИспользование: /reg <пароль>"));
                return true;
            }

            String hash = hashPassword(args[0]);
            FileConfiguration accConfig = YamlConfiguration.loadConfiguration(accountFile);
            accConfig.set("password", hash);
            accConfig.set("ip", player.getAddress().getAddress().getHostAddress());
            try {
                accConfig.save(accountFile);
            } catch (IOException e) {
                e.printStackTrace();
            }

            authenticatePlayer(player);
            
            // Новая регистрация -> телепортируем на основной спаун мира
            player.teleport(player.getWorld().getSpawnLocation());
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&a🎉 Успешная регистрация! Добро пожаловать на сервер!"));
            return true;
        }

        if (command.getName().equalsIgnoreCase("login") || command.getName().equalsIgnoreCase("l")) {
            if (!unauthenticatedPlayers.contains(uuid)) {
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&cВы уже вошли!"));
                return true;
            }
            if (!accountFile.exists()) {
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&cАккаунт не зарегистрирован! Используйте /reg <пароль>"));
                return true;
            }
            if (args.length < 1) {
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&cИспользование: /login <пароль>"));
                return true;
            }

            FileConfiguration accConfig = YamlConfiguration.loadConfiguration(accountFile);
            String savedHash = accConfig.getString("password", "");

            if (hashPassword(args[0]).equals(savedHash)) {
                authenticatePlayer(player);
                
                // Вход существующего игрока -> телепортируем туда, где он вышел последний раз
                Location lastLoc = getPlayerLocation(player, accountFile);
                player.teleport(lastLoc);
                
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&a🔓 Успешный вход в игру! Вы возвращены на место выхода."));
            } else {
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&c❌ Неверный пароль!"));
            }
            return true;
        }

        return false;
    }

    private void authenticatePlayer(Player player) {
        unauthenticatedPlayers.remove(player.getUniqueId());
        player.removePotionEffect(PotionEffectType.BLINDNESS);
    }

    private void savePlayerLocation(Player player, File accountFile) {
        FileConfiguration config = YamlConfiguration.loadConfiguration(accountFile);
        Location loc = player.getLocation();
        config.set("last-location.world", loc.getWorld().getName());
        config.set("last-location.x", loc.getX());
        config.set("last-location.y", loc.getY());
        config.set("last-location.z", loc.getZ());
        config.set("last-location.yaw", loc.getYaw());
        config.set("last-location.pitch", loc.getPitch());
        try {
            config.save(accountFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private Location getPlayerLocation(Player player, File accountFile) {
        FileConfiguration config = YamlConfiguration.loadConfiguration(accountFile);
        if (config.contains("last-location.world")) {
            String worldName = config.getString("last-location.world");
            if (Bukkit.getWorld(worldName) != null) {
                return new Location(
                        Bukkit.getWorld(worldName),
                        config.getDouble("last-location.x"),
                        config.getDouble("last-location.y"),
                        config.getDouble("last-location.z"),
                        (float) config.getDouble("last-location.yaw"),
                        (float) config.getDouble("last-location.pitch")
                );
            }
        }
        return player.getWorld().getSpawnLocation();
    }

    private String generateRandomCode() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder();
        Random random = new Random();
        for (int i = 0; i < 5; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }

    private String hashPassword(String password) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(password.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
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
