package com.voidpulse;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
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
        getLogger().info("§a[VoidPulse] Автономный плагин авторизации с капчей успешно запущен!");
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
        UUID uuid = event.getPlayer().getUniqueId();
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
            if (!msg.startsWith("/reg") && !msg.startsWith("/login") && !msg.startsWith("/l ")) {
                event.setCancelled(true);
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&cСначала пройдите капчу и авторизуйтесь!"));
            }
        }
    }

    // --- КОМАНДЫ РЕГИСТРАЦИИ И ВХОДА ---

    @Override
    public boolean onCommand(org.bukkit.command.CommandSender sender, org.bukkit.command.Command command, String label, String[] args) {
        if (!(sender instanceof Player)) return true;
        Player player = (Player) sender;
        UUID uuid = player.getUniqueId();
        String name = player.getName().toLowerCase();
        File accountFile = new File(getDataFolder(), "accounts/" + name + ".yml");

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
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&a🎉 Успешная регистрация и вход!"));
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
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&a🔓 Успешный вход в игру!"));
            } else {
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&c❌ Неверный пароль!"));
            }
            return true;
        }

        return false;
    }

    private void authenticatePlayer(Player player) {
        unauthenticatedPlayers.remove(player.getUniqueId());
        // Гарантированно снимаем эффект слепоты и возвращаем нормальное зрение
        player.removePotionEffect(PotionEffectType.BLINDNESS);
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
