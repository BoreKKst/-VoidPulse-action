package com.voidpulse;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class VoidPulsePlugin extends JavaPlugin implements Listener {

    private String apiUrl;
    private String secretKey;
    private String inviteLink;

    // Хранение локальных данных ожидания верификации: Ник -> {code, sessionId}
    private final Map<String, PendingVerification> pendingVerifications = new ConcurrentHashMap<>();
    
    // Список игроков, которые прошли Discord, но еще не ввели /login или /reg
    private final Set<UUID> unauthenticatedPlayers = ConcurrentHashMap.newKeySet();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .build();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfigValues();

        // Создаем папку для аккаунтов
        File accountsDir = new File(getDataFolder(), "accounts");
        if (!accountsDir.exists()) {
            accountsDir.mkdirs();
        }

        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("§a[VoidPulse] Плагин авторизации и интеграции с Discord успешно запущен!");
    }

    private void loadConfigValues() {
        FileConfiguration config = getConfig();
        apiUrl = config.getString("discord.api-url", "https://your-bot.onrender.com");
        secretKey = config.getString("discord.secret-key", "DEFAULT_KEY");
        inviteLink = config.getString("discord.invite-link", "discord.gg/voidpulse");
    }

    @Override
    public void onDisable() {
        getLogger().info("§c[VoidPulse] Плагин выключен.");
    }

    // --- ЛОГИКА ВХОДА И ПРОВЕРКИ ДИСКОРДА ---

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String nick = player.getName();
        UUID uuid = player.getUniqueId();

        // Накладываем слепоту при входе, пока не авторизуется
        player.addPotionEffect(newPotionEffect(PotionEffectType.BLINDNESS, 999999, 1, false, false));

        // Проверяем через API бота на Render, подтвердил ли игрок код в ДС
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            PendingVerification pending = pendingVerifications.get(nick.toLowerCase());

            if (pending != null) {
                // Игрок уже получал код, проверяем статус у бота (/api/check)
                boolean approved = checkApiStatus(nick, pending.code, pending.sessionId);
                if (approved) {
                    // Дискорд пройден! Удаляем из ожидания и пускаем на этап пароля
                    pendingVerifications.remove(nick.toLowerCase());
                    unauthenticatedPlayers.add(uuid);
                    
                    Bukkit.getScheduler().runTask(this, () -> {
                        player.sendMessage(ChatColor.translateAlternateColorCodes('&', 
                                getConfig().getString("messages.not-logged-in", "&cАвторизуйтесь через /login или /reg")));
                    });
                    return;
                }
            }

            // Если не подтвержден или сессия новая — генерируем новый код и кикаем
            String code = generateRandomCode();
            String sessionId = UUID.randomUUID().toString();

            pendingVerifications.put(nick.toLowerCase(), new PendingVerification(code, sessionId));

            // Отправляем запрос боту на регистрацию сессии (/api/register)
            sendRegisterApi(nick, code, sessionId);

            // Формируем текст кика
            String kickMsg = getConfig().getString("messages.kick-verification", "")
                    .replace("{invite}", inviteLink)
                    .replace("{code}", code);

            Bukkit.getScheduler().runTask(this, () -> {
                player.kickPlayer(ChatColor.translateAlternateColorCodes('&', kickMsg));
            });
        });
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        unauthenticatedPlayers.remove(event.getPlayer().getUniqueId());
    }

    // --- БЛОКИРОВКА ДЕЙСТВИЙ ДО АВТОРИЗАЦИИ ---

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (unauthenticatedPlayers.contains(event.getPlayer().getUniqueId())) {
            // Запрещаем ходить, пока не введет пароль
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
            // Разрешаем только команды авторизации
            if (!msg.startsWith("/reg") && !msg.startsWith("/login") && !msg.startsWith("/l ")) {
                event.setCancelled(true);
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', 
                        getConfig().getString("messages.not-logged-in", "&cСначала авторизуйтесь!")));
            }
        }
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        if (unauthenticatedPlayers.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.translateAlternateColorCodes('&', 
                    getConfig().getString("messages.not-logged-in", "&cСначала авторизуйтесь!")));
        }
    }

    // --- КОМАНДЫ РЕГИСТРАЦИИ И ВХОДА (/reg, /login) ---

    @Override
    public boolean onCommand(org.bukkit.command.CommandSender sender, org.bukkit.command.Command command, String label, String[] args) {
        if (!(sender instanceof Player)) return true;
        Player player = (Player);
        String name = player.getName().toLowerCase();
        File accountFile = new File(getDataFolder(), "accounts/" + name + ".yml");

        if (command.getName().equalsIgnoreCase("reg")) {
            if (!unauthenticatedPlayers.contains(player.getUniqueId())) {
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&cВы уже вошли в систему!"));
                return true;
            }
            if (accountFile.exists()) {
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&cАккаунт уже зарегистрирован! Используйте /login <пароль>"));
                return true;
            }
            if (args.length < 1) {
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', getConfig().getString("messages.usage-reg", "&c/reg <пароль>")));
                return true;
            }

            String password = args[0];
            String hash = hashPassword(password);

            // Сохраняем файл аккаунта
            FileConfiguration accConfig = YamlConfiguration.loadConfiguration(accountFile);
            accConfig.set("password", hash);
            accConfig.set("ip", player.getAddress().getAddress().getHostAddress());
            try {
                accConfig.save(accountFile);
            } catch (IOException e) {
                e.printStackTrace();
            }

            // Успех авторизации
            authenticatePlayer(player);
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', getConfig().getString("messages.register-success", "&aУспешная регистрация!")));
            return true;
        }

        if (command.getName().equalsIgnoreCase("login") || command.getName().equalsIgnoreCase("l")) {
            if (!unauthenticatedPlayers.contains(player.getUniqueId())) {
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', getConfig().getString("messages.already-logged", "&cВы уже вошли!")));
                return true;
            }
            if (!accountFile.exists()) {
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&cАккаунт не зарегистрирован! Используйте /reg <пароль>"));
                return true;
            }
            if (args.length < 1) {
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', getConfig().getString("messages.usage-login", "&c/login <пароль>")));
                return true;
            }

            FileConfiguration accConfig = YamlConfiguration.loadConfiguration(accountFile);
            String savedHash = accConfig.getString("password", "");

            if (hashPassword(args[0]).equals(savedHash)) {
                authenticatePlayer(player);
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', getConfig().getString("messages.login-success", "&aУспешный вход!")));
            } else {
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', getConfig().getString("messages.wrong-password", "&cНеверный пароль!")));
            }
            return true;
        }

        return false;
    }

    private void authenticatePlayer(Player player) {
        unauthenticatedPlayers.remove(player.getUniqueId());
        player.removePotionEffect(PotionEffectType.BLINDNESS);
    }

    // --- HTTP СЕТЕВЫЕ ЗАПРОСЫ К RENDER-БОТУ ---

    private void sendRegisterApi(String nick, String code, String sessionId) {
        try {
            String json = String.format("{\"secret_key\":\"%s\",\"mc_nick\":\"%s\",\"code\":\"%s\",\"session_id\":\"%s\"}",
                    secretKey, nick, code, sessionId);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl + "/api/register"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            getLogger().warning("[API ERROR] Не удалось отправить сессию на Render: " + e.getMessage());
        }
    }

    private boolean checkApiStatus(String nick, String code, String sessionId) {
        try {
            String json = String.format("{\"secret_key\":\"%s\",\"mc_nick\":\"%s\",\"code\":\"%s\",\"session_id\":\"%s\"}",
                    secretKey, nick, code, sessionId);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl + "/api/check"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            String body = response.body();

            // Проверяем JSON-ответ на "APPROVED":true
            return body.contains("\"success\":true") && body.contains("\"code\":\"APPROVED\"");
        } catch (Exception e) {
            getLogger().warning("[API ERROR] Ошибка проверки статуса на Render: " + e.getMessage());
            return false;
        }
    }

    // --- ВСПОМОГАТЕЛЬНЫЕ УТИЛИТЫ ---

    private String generateRandomCode() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder();
        Random random = new Random();
        for (int i = 0; i < 6; i++) {
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
            return password; // fallback
        }
    }

    private PotionEffect newPotionEffect(PotionEffectType type, int duration, int amplifier, boolean ambient, boolean particles) {
        return new PotionEffect(type, duration, amplifier, ambient, particles);
    }

    private static class PendingVerification {
        String code;
        String sessionId;

        public PendingVerification(String code, String sessionId) {
            this.code = code;
            this.sessionId = sessionId;
        }
    }
}
