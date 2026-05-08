package com.hazel.aidm;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
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
import java.util.HashSet;
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
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
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
    private static final String HIGH_RISK_DENIED_TOO_CLOSE =
            "Denied: high-risk events must be at least 500 blocks from spawn.";

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

    private static final Set<Material> PROTECTED_BLOCKS = Set.of(
            Material.CHEST,
            Material.TRAPPED_CHEST,
            Material.BARREL,
            Material.FURNACE,
            Material.BLAST_FURNACE,
            Material.SMOKER,
            Material.BEACON,
            Material.BEDROCK,
            Material.COMMAND_BLOCK,
            Material.REPEATING_COMMAND_BLOCK,
            Material.CHAIN_COMMAND_BLOCK);

    private final Gson gson = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    private final Map<UUID, NamespacedKey> lastKnownBiomeByPlayer = new HashMap<>();
    private final Map<String, LoreBook> loreBooksById = new LinkedHashMap<>();
    private final Map<String, EventPackage> eventPackagesById = new LinkedHashMap<>();
    private final Set<String> approvedEventIds = new HashSet<>();
    private final Deque<String> recentEventLines = new ArrayDeque<>();

    private Path serverRoot;
    private Path eventLogPath;
    private Path contentBooksPath;
    private Path contentEventsPath;
    private Path contentStructuresPath;
    private Path approvalsPath;
    private Path rollbacksPath;
    private boolean logChat;
    private int recentEventLimit;
    private double highRiskMinSpawnDistance;
    private double highRiskMaxSpawnDistance;
    private boolean allowHighRiskBeyondMaxSpawnDistance;
    private boolean requireApprovalForHighRisk;
    private boolean requireRollbackForHighRisk;
    private int maxHighRiskStructureBlocks;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadSettings();

        Bukkit.getPluginManager().registerEvents(this, this);
        if (getCommand("aidm") != null) {
            getCommand("aidm").setExecutor(this);
            getCommand("aidm").setTabCompleter(this);
        }

        loadApprovals();
        loadLoreBooks();
        loadEventPackages();
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
        contentEventsPath = resolveServerPath(getConfig().getString("content-events-path", "content/events"));
        contentStructuresPath = resolveServerPath(getConfig().getString("content-structures-path", "content/structures"));
        approvalsPath = resolveServerPath(getConfig().getString("approvals-path", "data/approvals/approved_events.json"));
        rollbacksPath = resolveServerPath(getConfig().getString("rollbacks-path", "data/rollbacks"));
        logChat = getConfig().getBoolean("log-chat", false);
        recentEventLimit = Math.max(1, getConfig().getInt("recent-event-limit", 10));

        highRiskMinSpawnDistance = Math.max(0, getConfig().getDouble("highRiskMinSpawnDistance", 500));
        highRiskMaxSpawnDistance = Math.max(highRiskMinSpawnDistance,
                getConfig().getDouble("highRiskMaxSpawnDistance", 5000));
        allowHighRiskBeyondMaxSpawnDistance =
                getConfig().getBoolean("allowHighRiskBeyondMaxSpawnDistance", false);
        requireApprovalForHighRisk = getConfig().getBoolean("requireApprovalForHighRisk", true);
        requireRollbackForHighRisk = getConfig().getBoolean("requireRollbackForHighRisk", true);
        maxHighRiskStructureBlocks = Math.max(1, getConfig().getInt("maxHighRiskStructureBlocks", 5000));

        createParentDirectory(eventLogPath);
        createDirectory(contentBooksPath);
        createDirectory(contentEventsPath);
        createDirectory(contentStructuresPath);
        createParentDirectory(approvalsPath);
        createDirectory(rollbacksPath);
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

    private void loadEventPackages() {
        eventPackagesById.clear();
        createDirectory(contentEventsPath);

        try (Stream<Path> files = Files.list(contentEventsPath)) {
            files.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                    .sorted()
                    .forEach(this::loadEventPackageFile);
        } catch (IOException exception) {
            getLogger().warning("Failed to list event package directory: " + exception.getMessage());
        }
    }

    private void loadEventPackageFile(Path path) {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            EventPackage eventPackage = gson.fromJson(reader, EventPackage.class);
            if (!isValidEventPackage(eventPackage)) {
                getLogger().warning("Skipping invalid event package JSON: " + path.getFileName());
                return;
            }
            eventPackagesById.put(eventPackage.id, eventPackage);
        } catch (IOException | JsonSyntaxException exception) {
            getLogger().warning("Failed to load event package " + path.getFileName() + ": " + exception.getMessage());
        }
    }

    private boolean isValidEventPackage(EventPackage eventPackage) {
        return eventPackage != null
                && eventPackage.id != null
                && isSafeId(eventPackage.id)
                && !eventPackage.id.isBlank()
                && eventPackage.title != null
                && !eventPackage.title.isBlank()
                && eventPackage.location != null
                && eventPackage.structure != null
                && "json_blocks".equalsIgnoreCase(nullToEmpty(eventPackage.structure.type))
                && eventPackage.structure.structure_id != null
                && isSafeId(eventPackage.structure.structure_id)
                && !eventPackage.structure.structure_id.isBlank();
    }

    private void loadApprovals() {
        approvedEventIds.clear();
        if (!Files.exists(approvalsPath)) {
            return;
        }

        try (Reader reader = Files.newBufferedReader(approvalsPath, StandardCharsets.UTF_8)) {
            ApprovalFile approvalFile = gson.fromJson(reader, ApprovalFile.class);
            if (approvalFile != null && approvalFile.approved_events != null) {
                approvedEventIds.addAll(approvalFile.approved_events);
            }
        } catch (IOException | JsonSyntaxException exception) {
            getLogger().warning("Failed to load approvals: " + exception.getMessage());
        }
    }

    private boolean saveApprovals() {
        ApprovalFile approvalFile = new ApprovalFile();
        approvalFile.approved_events = new ArrayList<>(approvedEventIds);
        Collections.sort(approvalFile.approved_events);

        try {
            createParentDirectory(approvalsPath);
            Files.writeString(approvalsPath, gson.toJson(approvalFile) + System.lineSeparator(), StandardCharsets.UTF_8);
            return true;
        } catch (IOException exception) {
            getLogger().warning("Failed to save approvals: " + exception.getMessage());
            return false;
        }
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
            case "reloadevents" -> reloadEvents(sender);
            case "listevents" -> listEvents(sender);
            case "eventinfo" -> eventInfo(sender, args);
            case "previewevent" -> previewEvent(sender, args);
            case "approveevent" -> approveEvent(sender, args);
            case "startevent" -> startEvent(sender, args);
            case "cancellevent" -> cancelEvent(sender, args);
            case "rollbackevent" -> rollbackEvent(sender, args);
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
        sender.sendMessage(ChatColor.YELLOW + "/aidm reloadevents");
        sender.sendMessage(ChatColor.YELLOW + "/aidm listevents");
        sender.sendMessage(ChatColor.YELLOW + "/aidm eventinfo <event_id>");
        sender.sendMessage(ChatColor.YELLOW + "/aidm previewevent <event_id>");
        sender.sendMessage(ChatColor.YELLOW + "/aidm approveevent <event_id>");
        sender.sendMessage(ChatColor.YELLOW + "/aidm startevent <event_id>");
        sender.sendMessage(ChatColor.YELLOW + "/aidm cancellevent <event_id>");
        sender.sendMessage(ChatColor.YELLOW + "/aidm rollbackevent <event_id>");
    }

    private void sendStatus(CommandSender sender) {
        sender.sendMessage(ChatColor.GREEN + "AIDungeonMasterObserver is active.");
        sender.sendMessage(ChatColor.GRAY + "Event log: " + eventLogPath);
        sender.sendMessage(ChatColor.GRAY + "Lore books: " + contentBooksPath);
        sender.sendMessage(ChatColor.GRAY + "Event packages: " + contentEventsPath);
        sender.sendMessage(ChatColor.GRAY + "Structures: " + contentStructuresPath);
        sender.sendMessage(ChatColor.GRAY + "Loaded books: " + loreBooksById.size());
        sender.sendMessage(ChatColor.GRAY + "Loaded events: " + eventPackagesById.size());
        sender.sendMessage(ChatColor.GRAY + "Approved events: " + approvedEventIds.size());
        sender.sendMessage(ChatColor.GRAY + "High-risk distance: "
                + formatNumber(highRiskMinSpawnDistance) + "-" + formatNumber(highRiskMaxSpawnDistance)
                + " blocks from spawn");
        sender.sendMessage(ChatColor.GRAY + "Chat logging: " + logChat);
    }

    private void reloadContent(CommandSender sender) {
        reloadSettings();
        loadLoreBooks();
        sender.sendMessage(ChatColor.GREEN + "Reloaded " + loreBooksById.size() + " lore book(s).");
    }

    private void reloadEvents(CommandSender sender) {
        reloadSettings();
        loadApprovals();
        loadEventPackages();
        sender.sendMessage(ChatColor.GREEN + "Reloaded " + eventPackagesById.size() + " event package(s).");
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

    private void listEvents(CommandSender sender) {
        if (eventPackagesById.isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + "No event packages are loaded. Run /aidm reloadevents after generating content.");
            return;
        }

        sender.sendMessage(ChatColor.GOLD + "Loaded event packages:");
        for (EventPackage eventPackage : eventPackagesById.values()) {
            sender.sendMessage(ChatColor.YELLOW + "- " + eventPackage.id
                    + ChatColor.GRAY + " | " + eventPackage.title
                    + " | risk=" + riskLevel(eventPackage)
                    + " | approved=" + approvedEventIds.contains(eventPackage.id));
        }
    }

    private void eventInfo(CommandSender sender, String[] args) {
        EventPackage eventPackage = getEventArgument(sender, args, "eventinfo");
        if (eventPackage == null) {
            return;
        }

        sender.sendMessage(ChatColor.GOLD + eventPackage.title + ChatColor.GRAY + " (" + eventPackage.id + ")");
        sender.sendMessage(ChatColor.GRAY + "Story arc: " + nullToEmpty(eventPackage.story_arc));
        sender.sendMessage(ChatColor.GRAY + "Risk level: " + riskLevel(eventPackage));
        sender.sendMessage(ChatColor.GRAY + "Description: " + nullToEmpty(eventPackage.description));
        sender.sendMessage(ChatColor.GRAY + "Structure: " + eventPackage.structure.structure_id);
        sender.sendMessage(ChatColor.GRAY + "Approved: " + approvedEventIds.contains(eventPackage.id));
        if (eventPackage.tags != null && !eventPackage.tags.isEmpty()) {
            sender.sendMessage(ChatColor.GRAY + "Tags: " + String.join(", ", eventPackage.tags));
        }
    }

    private void previewEvent(CommandSender sender, String[] args) {
        EventPackage eventPackage = getEventArgument(sender, args, "previewevent");
        if (eventPackage == null) {
            return;
        }

        ResolvedEventTarget target = resolveEventTarget(sender, eventPackage);
        if (!target.success()) {
            sender.sendMessage(ChatColor.RED + target.errorMessage);
            return;
        }

        StructureLoadResult structureResult = loadAndValidateStructure(eventPackage);
        HighRiskCheck highRiskCheck = checkHighRiskRules(eventPackage, target);
        boolean approvalRequired = isApprovalRequired(eventPackage);
        boolean rollbackRequired = isHighRisk(eventPackage) && requireRollbackForHighRisk;

        sender.sendMessage(ChatColor.GOLD + "Preview: " + eventPackage.title);
        sender.sendMessage(ChatColor.GRAY + "Risk level: " + riskLevel(eventPackage));
        sender.sendMessage(ChatColor.GRAY + "Target: " + target.world.getName()
                + " " + target.x + " " + target.y + " " + target.z);
        sender.sendMessage(ChatColor.GRAY + "Distance from spawn: " + formatNumber(target.distanceFromSpawn));
        sender.sendMessage(ChatColor.GRAY + "High-risk distance rule: "
                + (highRiskCheck.allowed ? ChatColor.GREEN + "passes" : ChatColor.RED + "fails"));
        sender.sendMessage(ChatColor.GRAY + "Approval required: " + approvalRequired
                + " | approved: " + approvedEventIds.contains(eventPackage.id));
        sender.sendMessage(ChatColor.GRAY + "Rollback required: " + rollbackRequired);
        sender.sendMessage(ChatColor.GRAY + "Structure validation: "
                + (structureResult.success ? "ok (" + structureResult.structure.blocks.size() + " block(s))"
                : "failed: " + structureResult.errorMessage));
        if (!highRiskCheck.allowed) {
            sender.sendMessage(ChatColor.RED + highRiskCheck.message);
        }
    }

    private void approveEvent(CommandSender sender, String[] args) {
        EventPackage eventPackage = getEventArgument(sender, args, "approveevent");
        if (eventPackage == null) {
            return;
        }

        approvedEventIds.add(eventPackage.id);
        if (saveApprovals()) {
            sender.sendMessage(ChatColor.GREEN + "Approved event: " + eventPackage.id);
        } else {
            sender.sendMessage(ChatColor.RED + "Failed to save approval file.");
        }
    }

    private void cancelEvent(CommandSender sender, String[] args) {
        EventPackage eventPackage = getEventArgument(sender, args, "cancellevent");
        if (eventPackage == null) {
            return;
        }

        approvedEventIds.remove(eventPackage.id);
        if (saveApprovals()) {
            sender.sendMessage(ChatColor.GREEN + "Removed approval for event: " + eventPackage.id);
        } else {
            sender.sendMessage(ChatColor.RED + "Failed to save approval file.");
        }
    }

    private void startEvent(CommandSender sender, String[] args) {
        EventPackage eventPackage = getEventArgument(sender, args, "startevent");
        if (eventPackage == null) {
            return;
        }

        ResolvedEventTarget target = resolveEventTarget(sender, eventPackage);
        if (!target.success()) {
            sender.sendMessage(ChatColor.RED + target.errorMessage);
            return;
        }

        // This is the core containment check for dangerous generated content.
        // High-risk packages are denied before structure loading or placement if
        // their target is inside the configured spawn safety zone.
        HighRiskCheck highRiskCheck = checkHighRiskRules(eventPackage, target);
        if (!highRiskCheck.allowed) {
            sender.sendMessage(ChatColor.RED + highRiskCheck.message);
            return;
        }

        if (isApprovalRequired(eventPackage) && !approvedEventIds.contains(eventPackage.id)) {
            sender.sendMessage(ChatColor.RED + "Denied: event approval is required before start.");
            return;
        }

        StructureLoadResult structureResult = loadAndValidateStructure(eventPackage);
        if (!structureResult.success) {
            sender.sendMessage(ChatColor.RED + "Denied: " + structureResult.errorMessage);
            return;
        }

        PlacementValidation placementValidation = validatePlacement(eventPackage, structureResult.structure, target);
        if (!placementValidation.allowed) {
            sender.sendMessage(ChatColor.RED + "Denied: " + placementValidation.message);
            return;
        }

        if (isHighRisk(eventPackage)) {
            boolean rollbackSaved = saveRollbackSnapshot(eventPackage, structureResult.structure, target);
            if (!rollbackSaved && requireRollbackForHighRisk) {
                sender.sendMessage(ChatColor.RED + "Denied: rollback snapshot creation failed.");
                return;
            }
            if (!rollbackSaved) {
                sender.sendMessage(ChatColor.YELLOW + "Warning: rollback snapshot creation failed, continuing because config allows it.");
            }
        }

        placeStructure(structureResult.structure, target);
        sender.sendMessage(ChatColor.GREEN + "Started event: " + eventPackage.id);
        sender.sendMessage(ChatColor.GRAY + "Placed structure " + eventPackage.structure.structure_id
                + " at " + target.world.getName() + " " + target.x + " " + target.y + " " + target.z);
    }

    private void rollbackEvent(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /aidm rollbackevent <event_id>");
            return;
        }
        if (!isSafeId(args[1])) {
            sender.sendMessage(ChatColor.RED + "Invalid event id.");
            return;
        }

        Path rollbackPath = rollbacksPath.resolve(args[1] + ".json").normalize();
        if (!Files.exists(rollbackPath)) {
            sender.sendMessage(ChatColor.RED + "No rollback snapshot found for event: " + args[1]);
            return;
        }

        try (Reader reader = Files.newBufferedReader(rollbackPath, StandardCharsets.UTF_8)) {
            RollbackSnapshot snapshot = gson.fromJson(reader, RollbackSnapshot.class);
            if (snapshot == null || snapshot.world == null || snapshot.blocks == null) {
                sender.sendMessage(ChatColor.RED + "Rollback snapshot is malformed.");
                return;
            }

            World world = Bukkit.getWorld(snapshot.world);
            if (world == null) {
                sender.sendMessage(ChatColor.RED + "Rollback world is not loaded: " + snapshot.world);
                return;
            }

            for (RollbackBlock rollbackBlock : snapshot.blocks) {
                Block block = world.getBlockAt(rollbackBlock.x, rollbackBlock.y, rollbackBlock.z);
                restoreBlock(block, rollbackBlock);
            }
            sender.sendMessage(ChatColor.GREEN + "Rolled back event: " + args[1]
                    + " (" + snapshot.blocks.size() + " block(s) restored)");
        } catch (IOException | JsonSyntaxException exception) {
            sender.sendMessage(ChatColor.RED + "Rollback failed: " + exception.getMessage());
        }
    }

    private EventPackage getEventArgument(CommandSender sender, String[] args, String commandName) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /aidm " + commandName + " <event_id>");
            return null;
        }

        EventPackage eventPackage = eventPackagesById.get(args[1]);
        if (eventPackage == null) {
            sender.sendMessage(ChatColor.RED + "Unknown event id: " + args[1]);
            return null;
        }
        return eventPackage;
    }

    private ResolvedEventTarget resolveEventTarget(CommandSender sender, EventPackage eventPackage) {
        LocationSpec locationSpec = eventPackage.location;
        String mode = nullToEmpty(locationSpec.mode).toLowerCase(Locale.ROOT);

        World world = resolveWorld(sender, locationSpec);
        if (world == null) {
            return ResolvedEventTarget.error("No target world is available.");
        }

        int targetX;
        int targetY;
        int targetZ;

        if ("at_player".equals(mode) || "near_player".equals(mode)) {
            Player player = resolveLocationPlayer(sender, locationSpec);
            if (player == null) {
                return ResolvedEventTarget.error("Target player is not online.");
            }
            Location playerLocation = player.getLocation();
            targetY = playerLocation.getBlockY();
            if ("near_player".equals(mode)) {
                int radius = Math.max(0, locationSpec.radius_min);
                targetX = playerLocation.getBlockX() + radius;
                targetZ = playerLocation.getBlockZ();
            } else {
                targetX = playerLocation.getBlockX();
                targetZ = playerLocation.getBlockZ();
            }
            world = player.getWorld();
        } else if ("fixed".equals(mode)) {
            targetX = locationSpec.x;
            targetY = locationSpec.y;
            targetZ = locationSpec.z;
        } else {
            return ResolvedEventTarget.error("Unknown location mode: " + locationSpec.mode);
        }

        Location spawn = world.getSpawnLocation();
        double distanceFromSpawn = horizontalDistance(targetX, targetZ, spawn.getBlockX(), spawn.getBlockZ());
        return ResolvedEventTarget.success(world, targetX, targetY, targetZ, distanceFromSpawn);
    }

    private World resolveWorld(CommandSender sender, LocationSpec locationSpec) {
        if (locationSpec.world != null && !locationSpec.world.isBlank()) {
            return Bukkit.getWorld(locationSpec.world);
        }
        if (sender instanceof Player player) {
            return player.getWorld();
        }
        List<World> worlds = Bukkit.getWorlds();
        return worlds.isEmpty() ? null : worlds.get(0);
    }

    private Player resolveLocationPlayer(CommandSender sender, LocationSpec locationSpec) {
        if (locationSpec.player != null && !locationSpec.player.isBlank()) {
            return Bukkit.getPlayerExact(locationSpec.player);
        }
        return sender instanceof Player player ? player : null;
    }

    private HighRiskCheck checkHighRiskRules(EventPackage eventPackage, ResolvedEventTarget target) {
        if (!isHighRisk(eventPackage)) {
            return new HighRiskCheck(true, "not high-risk");
        }

        if (target.distanceFromSpawn < highRiskMinSpawnDistance) {
            return new HighRiskCheck(false, HIGH_RISK_DENIED_TOO_CLOSE);
        }

        if (target.distanceFromSpawn > highRiskMaxSpawnDistance && !allowHighRiskBeyondMaxSpawnDistance) {
            return new HighRiskCheck(false,
                    "Denied: high-risk events must be within "
                            + formatNumber(highRiskMaxSpawnDistance)
                            + " blocks from spawn unless config allows farther placement.");
        }

        return new HighRiskCheck(true, "passes");
    }

    private boolean isApprovalRequired(EventPackage eventPackage) {
        if (isHighRisk(eventPackage) && requireApprovalForHighRisk) {
            return true;
        }
        return eventPackage.requires_approval;
    }

    private StructureLoadResult loadAndValidateStructure(EventPackage eventPackage) {
        Path structurePath = contentStructuresPath
                .resolve(eventPackage.structure.structure_id + ".json")
                .normalize();

        if (!structurePath.startsWith(contentStructuresPath.normalize())) {
            return StructureLoadResult.error("Structure id is not allowed: " + eventPackage.structure.structure_id);
        }
        if (!Files.exists(structurePath)) {
            return StructureLoadResult.error("Missing structure file: " + structurePath.getFileName());
        }

        try (Reader reader = Files.newBufferedReader(structurePath, StandardCharsets.UTF_8)) {
            JsonBlockStructure structure = gson.fromJson(reader, JsonBlockStructure.class);
            if (structure == null || structure.blocks == null || structure.blocks.isEmpty()) {
                return StructureLoadResult.error("Structure has no blocks.");
            }
            if (isHighRisk(eventPackage) && structure.blocks.size() > maxHighRiskStructureBlocks) {
                return StructureLoadResult.error("High-risk structure has " + structure.blocks.size()
                        + " blocks, max is " + maxHighRiskStructureBlocks + ".");
            }

            // Every AI-provided material/block data string is validated before
            // any block is placed. Unknown or non-block materials are rejected.
            for (int index = 0; index < structure.blocks.size(); index++) {
                StructureBlock structureBlock = structure.blocks.get(index);
                Material material = parseBlockMaterial(structureBlock.material);
                if (material == null) {
                    return StructureLoadResult.error("Invalid block material at index " + index + ": "
                            + structureBlock.material);
                }
                if (structureBlock.block_data != null && !structureBlock.block_data.isBlank()) {
                    try {
                        Bukkit.createBlockData(structureBlock.block_data);
                    } catch (IllegalArgumentException exception) {
                        return StructureLoadResult.error("Invalid block_data at index " + index + ": "
                                + structureBlock.block_data);
                    }
                }
            }
            return StructureLoadResult.success(structure);
        } catch (IOException | JsonSyntaxException exception) {
            return StructureLoadResult.error("Malformed structure JSON: " + exception.getMessage());
        }
    }

    private PlacementValidation validatePlacement(
            EventPackage eventPackage,
            JsonBlockStructure structure,
            ResolvedEventTarget target) {
        for (StructureBlock structureBlock : structure.blocks) {
            int x = target.x + structureBlock.dx;
            int y = target.y + structureBlock.dy;
            int z = target.z + structureBlock.dz;
            Block existingBlock = target.world.getBlockAt(x, y, z);

            if (isProtectedBlock(existingBlock.getType())) {
                return PlacementValidation.denied("protected block would be overwritten at "
                        + target.world.getName() + " " + x + " " + y + " " + z
                        + " (" + existingBlock.getType().name() + ")");
            }
        }
        return PlacementValidation.allowed();
    }

    private boolean saveRollbackSnapshot(
            EventPackage eventPackage,
            JsonBlockStructure structure,
            ResolvedEventTarget target) {
        RollbackSnapshot snapshot = new RollbackSnapshot();
        snapshot.event_id = eventPackage.id;
        snapshot.created_at = Instant.now().toString();
        snapshot.world = target.world.getName();
        snapshot.blocks = new ArrayList<>();

        for (StructureBlock structureBlock : structure.blocks) {
            int x = target.x + structureBlock.dx;
            int y = target.y + structureBlock.dy;
            int z = target.z + structureBlock.dz;
            Block existingBlock = target.world.getBlockAt(x, y, z);

            RollbackBlock rollbackBlock = new RollbackBlock();
            rollbackBlock.x = x;
            rollbackBlock.y = y;
            rollbackBlock.z = z;
            rollbackBlock.previous_material = existingBlock.getType().name();
            rollbackBlock.previous_block_data = existingBlock.getBlockData().getAsString();
            snapshot.blocks.add(rollbackBlock);
        }

        try {
            createDirectory(rollbacksPath);
            Path rollbackPath = rollbacksPath.resolve(eventPackage.id + ".json").normalize();
            Files.writeString(rollbackPath, gson.toJson(snapshot) + System.lineSeparator(), StandardCharsets.UTF_8);
            return true;
        } catch (IOException exception) {
            getLogger().warning("Failed to save rollback snapshot: " + exception.getMessage());
            return false;
        }
    }

    private void placeStructure(JsonBlockStructure structure, ResolvedEventTarget target) {
        for (StructureBlock structureBlock : structure.blocks) {
            int x = target.x + structureBlock.dx;
            int y = target.y + structureBlock.dy;
            int z = target.z + structureBlock.dz;
            Block block = target.world.getBlockAt(x, y, z);

            if (structureBlock.block_data != null && !structureBlock.block_data.isBlank()) {
                block.setBlockData(Bukkit.createBlockData(structureBlock.block_data), false);
            } else {
                Material material = parseBlockMaterial(structureBlock.material);
                block.setType(material, false);
            }
        }
    }

    private void restoreBlock(Block block, RollbackBlock rollbackBlock) {
        if (rollbackBlock.previous_block_data != null && !rollbackBlock.previous_block_data.isBlank()) {
            try {
                BlockData blockData = Bukkit.createBlockData(rollbackBlock.previous_block_data);
                block.setBlockData(blockData, false);
                return;
            } catch (IllegalArgumentException exception) {
                getLogger().warning("Invalid rollback block_data, falling back to material: "
                        + rollbackBlock.previous_block_data);
            }
        }

        Material material = parseBlockMaterial(rollbackBlock.previous_material);
        if (material != null) {
            block.setType(material, false);
        }
    }

    private Material parseBlockMaterial(String materialName) {
        if (materialName == null || materialName.isBlank()) {
            return null;
        }
        Material material = Material.matchMaterial(materialName);
        if (material == null) {
            material = Material.matchMaterial("minecraft:" + materialName.toLowerCase(Locale.ROOT));
        }
        if (material == null || !material.isBlock()) {
            return null;
        }
        return material;
    }

    private boolean isProtectedBlock(Material material) {
        return PROTECTED_BLOCKS.contains(material) || material.name().endsWith("SHULKER_BOX");
    }

    private boolean isHighRisk(EventPackage eventPackage) {
        return "high".equalsIgnoreCase(riskLevel(eventPackage));
    }

    private String riskLevel(EventPackage eventPackage) {
        String riskLevel = nullToEmpty(eventPackage.risk_level).toLowerCase(Locale.ROOT);
        if ("low".equals(riskLevel) || "medium".equals(riskLevel) || "high".equals(riskLevel)) {
            return riskLevel;
        }
        return "low";
    }

    private double horizontalDistance(int targetX, int targetZ, int spawnX, int spawnZ) {
        double deltaX = targetX - spawnX;
        double deltaZ = targetZ - spawnZ;
        return Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
    }

    private String formatNumber(double value) {
        if (Math.floor(value) == value) {
            return Long.toString((long) value);
        }
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private boolean isSafeId(String id) {
        return id != null && id.matches("[A-Za-z0-9_-]+");
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
            return filterPrefix(List.of(
                    "status",
                    "reloadcontent",
                    "givebook",
                    "listbooks",
                    "recent",
                    "reloadevents",
                    "listevents",
                    "eventinfo",
                    "previewevent",
                    "approveevent",
                    "startevent",
                    "cancellevent",
                    "rollbackevent"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("givebook")) {
            return filterPrefix(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("givebook")) {
            return filterPrefix(new ArrayList<>(loreBooksById.keySet()), args[2]);
        }
        if (args.length == 2 && isEventCommand(args[0])) {
            return filterPrefix(new ArrayList<>(eventPackagesById.keySet()), args[1]);
        }
        return Collections.emptyList();
    }

    private boolean isEventCommand(String commandName) {
        return Set.of(
                "eventinfo",
                "previewevent",
                "approveevent",
                "startevent",
                "cancellevent",
                "rollbackevent").contains(commandName.toLowerCase(Locale.ROOT));
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

    private static final class EventPackage {
        String id;
        String title;
        String story_arc;
        String description;
        String risk_level;
        boolean requires_approval;
        LocationSpec location;
        StructureSpec structure;
        List<String> lore_books;
        List<String> tags;
    }

    private static final class LocationSpec {
        String mode;
        String world;
        String player;
        int x;
        int y = 64;
        int z;
        int radius_min;
        int radius_max;
    }

    private static final class StructureSpec {
        String type;
        String structure_id;
    }

    private static final class JsonBlockStructure {
        String id;
        List<StructureBlock> blocks;
    }

    private static final class StructureBlock {
        int dx;
        int dy;
        int dz;
        String material;
        String block_data;
    }

    private static final class ApprovalFile {
        List<String> approved_events = new ArrayList<>();
    }

    private static final class RollbackSnapshot {
        String event_id;
        String created_at;
        String world;
        List<RollbackBlock> blocks;
    }

    private static final class RollbackBlock {
        int x;
        int y;
        int z;
        String previous_material;
        String previous_block_data;
    }

    private static final class ResolvedEventTarget {
        final boolean resolved;
        final String errorMessage;
        final World world;
        final int x;
        final int y;
        final int z;
        final double distanceFromSpawn;

        private ResolvedEventTarget(
                boolean resolved,
                String errorMessage,
                World world,
                int x,
                int y,
                int z,
                double distanceFromSpawn) {
            this.resolved = resolved;
            this.errorMessage = errorMessage;
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.distanceFromSpawn = distanceFromSpawn;
        }

        static ResolvedEventTarget success(World world, int x, int y, int z, double distanceFromSpawn) {
            return new ResolvedEventTarget(true, "", world, x, y, z, distanceFromSpawn);
        }

        static ResolvedEventTarget error(String errorMessage) {
            return new ResolvedEventTarget(false, errorMessage, null, 0, 0, 0, 0);
        }

        boolean success() {
            return resolved;
        }
    }

    private static final class HighRiskCheck {
        final boolean allowed;
        final String message;

        HighRiskCheck(boolean allowed, String message) {
            this.allowed = allowed;
            this.message = message;
        }
    }

    private static final class StructureLoadResult {
        final boolean success;
        final JsonBlockStructure structure;
        final String errorMessage;

        private StructureLoadResult(boolean success, JsonBlockStructure structure, String errorMessage) {
            this.success = success;
            this.structure = structure;
            this.errorMessage = errorMessage;
        }

        static StructureLoadResult success(JsonBlockStructure structure) {
            return new StructureLoadResult(true, structure, "");
        }

        static StructureLoadResult error(String errorMessage) {
            return new StructureLoadResult(false, null, errorMessage);
        }
    }

    private static final class PlacementValidation {
        final boolean allowed;
        final String message;

        private PlacementValidation(boolean allowed, String message) {
            this.allowed = allowed;
            this.message = message;
        }

        static PlacementValidation allowed() {
            return new PlacementValidation(true, "");
        }

        static PlacementValidation denied(String message) {
            return new PlacementValidation(false, message);
        }
    }
}
