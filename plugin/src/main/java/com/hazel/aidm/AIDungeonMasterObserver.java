package com.hazel.aidm;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.plugin.java.JavaPlugin;

public final class AIDungeonMasterObserver extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private static final Set<Material> NOTABLE_BLOCKS = Set.of(
            Material.DIAMOND_ORE,
            Material.DEEPSLATE_DIAMOND_ORE,
            Material.EMERALD_ORE,
            Material.DEEPSLATE_EMERALD_ORE,
            Material.ANCIENT_DEBRIS,
            Material.SPAWNER,
            Material.ENCHANTING_TABLE,
            Material.BEACON,
            Material.AMETHYST_BLOCK,
            Material.BUDDING_AMETHYST,
            Material.OBSIDIAN,
            Material.CRYING_OBSIDIAN,
            Material.END_PORTAL_FRAME,
            Material.DRAGON_EGG);

    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private final Map<UUID, NamespacedKey> lastKnownBiomeByPlayer = new HashMap<>();
    private final Map<String, LoreBook> loreBooksById = new LinkedHashMap<>();
    private final Deque<String> recentEventLines = new ArrayDeque<>();

    private Path serverRoot;
    private Path eventLogPath;
    private Path contentBooksPath;
    private boolean logChat;
    private int recentEventLimit;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadSettings();

        Bukkit.getPluginManager().registerEvents(this, this);
        if (getCommand("aidm") != null) {
            getCommand("aidm").setExecutor(this);
            getCommand("aidm").setTabCompleter(this);
        }

        loadLoreBooks();
        getLogger().info("AIDungeonMasterObserver enabled. Logging to " + eventLogPath);
    }

    @Override
    public void onDisable() {
        getLogger().info("AIDungeonMasterObserver disabled.");
    }

    private void reloadSettings() {
        reloadConfig();
        serverRoot = Paths.get("").toAbsolutePath().normalize();
        eventLogPath = resolveServerPath(getConfig().getString("events-log-path", "data/events/player_events.jsonl"));
        contentBooksPath = resolveServerPath(getConfig().getString("content-books-path", "content/lore_books"));
        logChat = getConfig().getBoolean("log-chat", false);
        recentEventLimit = Math.max(1, getConfig().getInt("recent-event-limit", 10));
        createParentDirectory(eventLogPath);
        createDirectory(contentBooksPath);
    }

    private Path resolveServerPath(String configuredPath) {
        Path path = Paths.get(configuredPath);
        if (path.isAbsolute()) {
            return path.normalize();
        }
        return serverRoot.resolve(path).normalize();
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        rememberCurrentBiome(player);
        logEvent("player_join", player, player.getLocation(), Map.of("message", player.getName() + " joined the server"));
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        logEvent("player_quit", player, player.getLocation(), Map.of("message", player.getName() + " left the server"));
        lastKnownBiomeByPlayer.remove(player.getUniqueId());
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        String message = event.getDeathMessage() == null ? player.getName() + " died" : event.getDeathMessage();
        logEvent("player_death", player, player.getLocation(), Map.of("message", message));
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        Material material = event.getBlockPlaced().getType();
        if (!NOTABLE_BLOCKS.contains(material)) {
            return;
        }

        logEvent("notable_block_place", event.getPlayer(), event.getBlockPlaced().getLocation(),
                Map.of("material", material.name().toLowerCase(Locale.ROOT)));
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Material material = event.getBlock().getType();
        if (!NOTABLE_BLOCKS.contains(material)) {
            return;
        }

        logEvent("notable_block_break", event.getPlayer(), event.getBlock().getLocation(),
                Map.of("material", material.name().toLowerCase(Locale.ROOT)));
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || sameBlock(from, to)) {
            return;
        }

        Player player = event.getPlayer();
        Biome biome = to.getBlock().getBiome();
        NamespacedKey currentBiomeKey = biome.getKey();
        NamespacedKey previousBiomeKey = lastKnownBiomeByPlayer.put(player.getUniqueId(), currentBiomeKey);

        if (previousBiomeKey != null && !previousBiomeKey.equals(currentBiomeKey)) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("from_biome", previousBiomeKey.toString());
            details.put("to_biome", currentBiomeKey.toString());
            logEvent("biome_enter", player, to, details);
        }
    }

    @EventHandler
    public void onPlayerChat(AsyncChatEvent event) {
        if (!logChat) {
            return;
        }

        String message = PlainTextComponentSerializer.plainText().serialize(event.message());
        logEvent("player_chat", event.getPlayer(), event.getPlayer().getLocation(), Map.of("message", message));
    }

    private boolean sameBlock(Location first, Location second) {
        World firstWorld = first.getWorld();
        World secondWorld = second.getWorld();
        if (firstWorld == null || secondWorld == null || !firstWorld.equals(secondWorld)) {
            return false;
        }
        return first.getBlockX() == second.getBlockX()
                && first.getBlockY() == second.getBlockY()
                && first.getBlockZ() == second.getBlockZ();
    }

    private void rememberCurrentBiome(Player player) {
        lastKnownBiomeByPlayer.put(player.getUniqueId(), player.getLocation().getBlock().getBiome().getKey());
    }

    private synchronized void logEvent(String type, Player player, Location location, Map<String, Object> details) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("id", UUID.randomUUID().toString());
        event.put("timestamp", Instant.now().toString());
        event.put("type", type);
        event.put("player", player.getName());
        event.put("player_uuid", player.getUniqueId().toString());
        event.put("world", location.getWorld() == null ? "unknown" : location.getWorld().getName());
        event.put("x", location.getBlockX());
        event.put("y", location.getBlockY());
        event.put("z", location.getBlockZ());
        event.put("details", details == null ? Collections.emptyMap() : details);

        String jsonLine = gson.toJson(event);
        try {
            createParentDirectory(eventLogPath);
            Files.writeString(eventLogPath, jsonLine + System.lineSeparator(), StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            addRecentEvent(jsonLine);
        } catch (IOException exception) {
            getLogger().warning("Failed to write AI DM event: " + exception.getMessage());
        }
    }

    private void addRecentEvent(String jsonLine) {
        recentEventLines.addLast(jsonLine);
        while (recentEventLines.size() > recentEventLimit) {
            recentEventLines.removeFirst();
        }
    }

    private void loadLoreBooks() {
        loreBooksById.clear();
        createDirectory(contentBooksPath);

        try (Stream<Path> files = Files.list(contentBooksPath)) {
            files.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                    .sorted()
                    .forEach(this::loadLoreBookFile);
        } catch (IOException exception) {
            getLogger().warning("Failed to list lore book directory: " + exception.getMessage());
        }
    }

    private void loadLoreBookFile(Path path) {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            LoreBook book = gson.fromJson(reader, LoreBook.class);
            if (!isValidLoreBook(book)) {
                getLogger().warning("Skipping invalid lore book JSON: " + path.getFileName());
                return;
            }
            loreBooksById.put(book.id, book);
        } catch (IOException | JsonSyntaxException exception) {
            getLogger().warning("Failed to load lore book " + path.getFileName() + ": " + exception.getMessage());
        }
    }

    private boolean isValidLoreBook(LoreBook book) {
        return book != null
                && book.id != null
                && !book.id.isBlank()
                && book.title != null
                && !book.title.isBlank()
                && book.author != null
                && !book.author.isBlank()
                && book.pages != null
                && !book.pages.isEmpty();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "status" -> sendStatus(sender);
            case "reloadcontent" -> reloadContent(sender);
            case "givebook" -> giveBook(sender, args);
            case "listbooks" -> listBooks(sender);
            case "recent" -> showRecent(sender);
            default -> sendUsage(sender);
        }

        return true;
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "AIDM commands:");
        sender.sendMessage(ChatColor.YELLOW + "/aidm status");
        sender.sendMessage(ChatColor.YELLOW + "/aidm reloadcontent");
        sender.sendMessage(ChatColor.YELLOW + "/aidm listbooks");
        sender.sendMessage(ChatColor.YELLOW + "/aidm givebook <player> <book_id>");
        sender.sendMessage(ChatColor.YELLOW + "/aidm recent");
    }

    private void sendStatus(CommandSender sender) {
        sender.sendMessage(ChatColor.GREEN + "AIDungeonMasterObserver is active.");
        sender.sendMessage(ChatColor.GRAY + "Event log: " + eventLogPath);
        sender.sendMessage(ChatColor.GRAY + "Lore books: " + contentBooksPath);
        sender.sendMessage(ChatColor.GRAY + "Loaded books: " + loreBooksById.size());
        sender.sendMessage(ChatColor.GRAY + "Chat logging: " + logChat);
    }

    private void reloadContent(CommandSender sender) {
        reloadSettings();
        loadLoreBooks();
        sender.sendMessage(ChatColor.GREEN + "Reloaded " + loreBooksById.size() + " lore book(s).");
    }

    private void giveBook(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(ChatColor.RED + "Usage: /aidm givebook <player> <book_id>");
            return;
        }

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Player is not online: " + args[1]);
            return;
        }

        LoreBook loreBook = loreBooksById.get(args[2]);
        if (loreBook == null) {
            sender.sendMessage(ChatColor.RED + "Unknown book id: " + args[2]);
            return;
        }

        target.getInventory().addItem(createBookItem(loreBook));
        sender.sendMessage(ChatColor.GREEN + "Gave " + loreBook.id + " to " + target.getName() + ".");
    }

    private ItemStack createBookItem(LoreBook loreBook) {
        ItemStack itemStack = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta bookMeta = (BookMeta) itemStack.getItemMeta();
        bookMeta.setTitle(limit(loreBook.title, 32));
        bookMeta.setAuthor(limit(loreBook.author, 32));

        List<String> safePages = new ArrayList<>();
        int pageCount = Math.min(loreBook.pages.size(), 100);
        for (int index = 0; index < pageCount; index++) {
            safePages.add(limit(loreBook.pages.get(index), 1024));
        }
        bookMeta.setPages(safePages);
        itemStack.setItemMeta(bookMeta);
        return itemStack;
    }

    private String limit(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private void listBooks(CommandSender sender) {
        if (loreBooksById.isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + "No lore books are loaded. Run /aidm reloadcontent after generating content.");
            return;
        }

        sender.sendMessage(ChatColor.GOLD + "Loaded lore books:");
        for (LoreBook loreBook : loreBooksById.values()) {
            sender.sendMessage(ChatColor.YELLOW + "- " + loreBook.id + ChatColor.GRAY + " | " + loreBook.title);
        }
    }

    private void showRecent(CommandSender sender) {
        List<String> events = new ArrayList<>(recentEventLines);
        if (events.isEmpty()) {
            events = readLastLines(eventLogPath, recentEventLimit);
        }

        if (events.isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + "No recent AI DM events found yet.");
            return;
        }

        sender.sendMessage(ChatColor.GOLD + "Recent AI DM events:");
        for (String eventLine : events) {
            sender.sendMessage(ChatColor.GRAY + limit(eventLine, 220));
        }
    }

    private List<String> readLastLines(Path path, int count) {
        if (!Files.exists(path)) {
            return Collections.emptyList();
        }

        Deque<String> lines = new ArrayDeque<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.addLast(line);
                while (lines.size() > count) {
                    lines.removeFirst();
                }
            }
        } catch (IOException exception) {
            getLogger().warning("Failed to read recent events: " + exception.getMessage());
        }
        return new ArrayList<>(lines);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filterPrefix(List.of("status", "reloadcontent", "givebook", "listbooks", "recent"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("givebook")) {
            return filterPrefix(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("givebook")) {
            return filterPrefix(new ArrayList<>(loreBooksById.keySet()), args[2]);
        }
        return Collections.emptyList();
    }

    private List<String> filterPrefix(List<String> options, String prefix) {
        String lowerPrefix = prefix.toLowerCase(Locale.ROOT);
        return options.stream()
                .filter(option -> option.toLowerCase(Locale.ROOT).startsWith(lowerPrefix))
                .toList();
    }

    private void createParentDirectory(Path path) {
        Path parent = path.getParent();
        if (parent != null) {
            createDirectory(parent);
        }
    }

    private void createDirectory(Path path) {
        try {
            Files.createDirectories(path);
        } catch (IOException exception) {
            getLogger().warning("Failed to create directory " + path + ": " + exception.getMessage());
        }
    }

    private static final class LoreBook {
        String id;
        String title;
        String author;
        List<String> pages;
        List<String> tags;
        List<String> source_events;
    }
}
