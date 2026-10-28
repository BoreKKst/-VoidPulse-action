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
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.map.MinecraftFont;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

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
    
    // Безопасное хранилище инвентарей на время авторизации (защита от дюпов и потери вещей)
    private final ConcurrentHashMap<UUID, ItemStack[]> savedInventories = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, ItemStack[]> savedArmor = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, BukkitTask> authTimeouts = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();

        File accountsDir = new File(getDataFolder(), "accounts");
        if (!accountsDir.exists()) {
            accountsDir.mkdirs();
        }

        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("§a[VoidPulse] Автономный плагин капчи и защиты от дюпа успешно запущен!");
    }

    @Override
    public void onDisable() {
        // Возвращаем вещи всем, кто остался в памяти при выключении плагина
        for (UUID uuid : unauthenticatedPlayers) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                restoreInventory(p);
            }
        }
        getLogger().info("§c[VoidPulse] Плагин выключен.");
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        unauthenticatedPlayers.add(uuid);

        // 1. Сохраняем инвентарь и броню в память, затем очищаем игрока (защита от дюпа)
        savedInventories.put(uuid, player.getInventory().getContents());
        savedArmor.put(uuid, player.getInventory().getArmorContents());
        player.getInventory().clear();

        // 2. Телепортируем на временный спаун авторизации, если он настроен
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

        // 3. Генерируем код капчи и выдаем карту
        String captchaCode = generateRandomCode();
        pendingCaptcha.put(uuid, captchaCode);

        ItemStack mapItem = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) mapItem.getItemMeta();
        if (meta != null) {
            MapView mapView = Bukkit.createMap(player.getWorld());
            mapView.getRenderers().clear();
            mapView.addRenderer(new MapRenderer() {
                @Override
                public void render(MapView map, MapCanvas canvas, Player p) {
                    canvas.drawText(15, 40, MinecraftFont.Font, "VOIDPULSE");
                    canvas.drawText(15, 60, MinecraftFont.Font, "CAPTCHA CODE:");
                    canvas.drawText(25, 80, MinecraftFont.Font, "-> " + captchaCode + " <-");
                }
            });
            meta.setMapView(mapView);
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', "&b&lКАРТА КАПЧИ"));
            mapItem.setItemMeta(meta);
        }

        player.getInventory().setItemInMainHand(mapItem);
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', 
                "&c[VoidPulse] Внимание! Ваши вещи временно защищены в хранилище. Посмотрите на карту в руке и введите код в чат!"));

        // 4. Таймер предупреждения на 10 секунд (через 50 секунд)
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (unauthenticatedPlayers.contains(uuid)) {
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', 
                        "&c[VoidPulse] Внимание! Осталось 10 секунд на авторизацию, иначе вы будете кикнуты, а вещи возвращены!"));
            }
        }, 20L * 50);

        // 5. Таймер полного таймаута на 60 секунд (возврат вещей и кик)
        BukkitTask timeoutTask = Bukkit.getScheduler().runTaskLater(this, () -> {
            if (unauthenticatedPlayers.contains(uuid)) {
                restoreInventory(player);
                unauthenticatedPlayers.remove(uuid);
                pendingCaptcha.remove(uuid);
                player.kickPlayer(ChatColor.translateAlternateColorCodes('&', "&c[VoidPulse] Время на авторизацию истекло! Ваши вещи возвращены."));
            }
        }, 20L * 60);
        
        authTimeouts.put(uuid, timeoutTask);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        
        // Отменяем таймер кика
        if (authTimeouts.containsKey(uuid)) {
            authTimeouts.get(uuid).cancel();
            authTimeouts.remove(uuid);
        }

        // Если вышел неавторизованным — сразу возвращаем вещи в инвентарь на случай повторного входа
        if (unauthenticatedPlayers.contains(uuid)) {
            restoreInventory(player);
            unauthenticatedPlayers.remove(uuid);
            pendingCaptcha.remove(uuid);
        } else {
            File accountFile = new File(getDataFolder(), "accounts/" + player.getName().toLowerCase() + ".yml");
            if (accountFile.exists()) {
                savePlayerLocation(player, accountFile);
            }
        }
        
        savedInventories.remove(uuid);
        savedArmor.remove(uuid);
    }

    // --- ЗАЩИТА ОТ ДЕЙСТВИЙ ДО АВТОРИЗАЦИИ ---

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        if (unauthenticatedPlayers.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.translateAlternateColorCodes('&', "&cНельзя ломать блоки до авторизации!"));
        }
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        if (unauthenticatedPlayers.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.translateAlternateColorCodes('&', "&cНельзя ставить блоки до авторизации!"));
        }
    }

    @EventHandler
    public void onDropItem(PlayerDropItemEvent event) {
        if (unauthenticatedPlayers.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.translateAlternateColorCodes('&', "&cНельзя выбрасывать предметы до авторизации!"));
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player) {
            if (unauthenticatedPlayers.contains(event.getWhoClicked().getUniqueId())) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (unauthenticatedPlayers.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

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
                        // Очищаем карту из руки
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
            if (!msg.startsWith("/reg") && !msg.startsWith("/login") && !msg.startsWith("/l ") && !msg.startsWith("/voidpulse:setspawn") && !msg.startsWith("/setspawn")) {
                event.setCancelled(true);
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&cСначала пройдите капчу и авторизуйтесь!"));
            }
        }
    }

    @Override
    public boolean onCommand(org.bukkit.command.CommandSender sender, org.bukkit.command.Command command, String label, String[] args) {
        if (!(sender instanceof Player)) return true;
        Player player = (Player) sender;
        UUID uuid = player.getUniqueId();
        String name = player.getName().toLowerCase();
        File accountFile = new File(getDataFolder(), "accounts/" + name + ".yml");

        if (command.getName().equalsIgnoreCase("setspawn") || label.equalsIgnoreCase("voidpulse:setspawn")) {
            if (!player.hasPermission("voidpulse.admin") && !player.isOp()) {
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&cУ вас нет прав!"));
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

            player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&a✅ Спаун авторизации успешно установлен здесь!"));
            return true;
        }

        if (pendingCaptcha.containsKey(uuid)) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&cСначала введите код с карты в чат!"));
            return true;
        }

        if (command.getName().equalsIgnoreCase("reg")) {
            if (!unauthenticatedPlayers.contains(uuid)) {
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&cВы уже вошли!"));
                return true;
            }
            if (accountFile.exists()) {
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&cАккаунт уже есть! Используйте /login <пароль>"));
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

            // Успешная регистрация: завершаем сессию авторизации, возвращаем вещи и телепортируем на спаун
            completeAuthentication(player);
            player.teleport(player.getWorld().getSpawnLocation());
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&a🎉 Успешная регистрация и вход! Ваши вещи возвращены."));
            return true;
        }

        if (command.getName().equalsIgnoreCase("login") || command.getName().equalsIgnoreCase("l")) {
            if (!unauthenticatedPlayers.contains(uuid)) {
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&cВы уже вошли!"));
                return true;
            }
            if (!accountFile.exists()) {
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&cАккаунт не найден! Используйте /reg <пароль>"));
                return true;
            }
            if (args.length < 1) {
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&cИспользование: /login <пароль>"));
                return true;
            }

            FileConfiguration accConfig = YamlConfiguration.loadConfiguration(accountFile);
            String savedHash = accConfig.getString("password", "");

            if (hashPassword(args[0]).equals(savedHash)) {
                // Успешный вход: завершаем сессию, возвращаем вещи и отправляем на точку выхода
                completeAuthentication(player);
                Location lastLoc = getPlayerLocation(player, accountFile);
                player.teleport(lastLoc);
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&a🔓 Успешный вход! Ваши вещи возвращены, вы на месте выхода."));
            } else {
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&c❌ Неверный пароль!"));
            }
            return true;
        }

        return false;
    }

    private void completeAuthentication(Player player) {
        UUID uuid = player.getUniqueId();
        if (authTimeouts.containsKey(uuid)) {
            authTimeouts.get(uuid).cancel();
            authTimeouts.remove(uuid);
        }
        unauthenticatedPlayers.remove(uuid);
        pendingCaptcha.remove(uuid);
        restoreInventory(player); // Возвращаем инвентарь игроку в целости и сохранности
    }

    private void restoreInventory(Player player) {
        UUID uuid = player.getUniqueId();
        if (savedInventories.containsKey(uuid)) {
            player.getInventory().setContents(savedInventories.get(uuid));
            savedInventories.remove(uuid);
        }
        if (savedArmor.containsKey(uuid)) {
            player.getInventory().setArmorContents(savedArmor.get(uuid));
            savedArmor.remove(uuid);
        }
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
