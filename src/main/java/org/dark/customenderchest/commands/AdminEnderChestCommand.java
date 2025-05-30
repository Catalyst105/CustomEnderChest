package org.dark.customenderchest.commands;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.*;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.dark.customenderchest.CustomEnderChest;
import org.dark.customenderchest.utilities.DatabaseHandler;

import java.util.*;

public class AdminEnderChestCommand implements CommandExecutor, TabCompleter, Listener {
    private final CustomEnderChest plugin;
    private final DatabaseHandler databaseHandler;
    private final Map<UUID, UUID> openEnderChests = new HashMap<>();
    private final String inventoryTitle;
    private final FileConfiguration config;

    public AdminEnderChestCommand(CustomEnderChest plugin, DatabaseHandler databaseHandler) {
        this.plugin = plugin;
        this.databaseHandler = databaseHandler;
        this.config = plugin.getConfig();
        this.inventoryTitle = ChatColor.translateAlternateColorCodes('&', "&5EnderChest &8- &f");
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    private String getMessage(String path) {
        return ChatColor.translateAlternateColorCodes('&',
                config.getString("messages." + path, "&cMessage not found: " + path));
    }

    private String getMessage(String path, String... replacements) {
        String message = ChatColor.translateAlternateColorCodes('&',
                config.getString("messages." + path, "&cMessage not found: " + path));

        for (int i = 0; i < replacements.length; i++) {
            message = message.replace("%" + (i + 1) + "%", replacements[i]);
        }

        return message;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length < 1) {
            sender.sendMessage(getMessage("achest-usage"));
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "reload":
                handleReloadCommand(sender);
                break;
            case "view":
                if (args.length < 2) {
                    sender.sendMessage(getMessage("view-usage"));
                    return true;
                }
                handleViewCommand(sender, args[1]);
                break;
            case "delete":
                if (args.length < 2) {
                    sender.sendMessage(getMessage("achest-usage"));
                    return true;
                }
                handleDeleteCommand(sender, args[1]);
                break;
            default:
                sender.sendMessage(getMessage("achest-usage"));
        }

        return true;
    }

    private void handleReloadCommand(CommandSender sender) {
        if (!sender.hasPermission("enderchest.admin.reload")) {
            sender.sendMessage(getMessage("no-permission-reload"));
            return;
        }

        try {
            // Reload the plugin configuration
            plugin.reloadConfig();

            // Close all currently open admin EnderChests
            for (Map.Entry<UUID, UUID> entry : openEnderChests.entrySet()) {
                Player viewer = Bukkit.getPlayer(entry.getKey());
                if (viewer != null && viewer.isOnline()) {
                    viewer.closeInventory();
                }
            }
            openEnderChests.clear();


            sender.sendMessage(getMessage("config-reloaded"));
        } catch (Exception e) {
            plugin.getLogger().severe("Error during plugin reload: " + e.getMessage());
            sender.sendMessage(getMessage("no-permission-reload"));
        }
    }

    private void handleViewCommand(CommandSender sender, String targetName) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(getMessage("only-players"));
            return;
        }

        if (!sender.hasPermission("enderchest.admin.view")) {
            sender.sendMessage(getMessage("no-permission-view"));
            return;
        }

        Player viewer = (Player) sender;
        UUID targetUUID = getPlayerUUID(targetName);

        if (targetUUID == null) {
            sender.sendMessage(getMessage("no-enderchest-found"));
            return;
        }

        openEnderChest(viewer, targetUUID, targetName);
    }

    private void handleDeleteCommand(CommandSender sender, String targetName) {
        if (!sender.hasPermission("enderchest.admin.delete")) {
            sender.sendMessage(getMessage("no-permission-view"));
            return;
        }

        UUID targetUUID = getPlayerUUID(targetName);
        if (targetUUID == null) {
            sender.sendMessage(getMessage("no-enderchest-found"));
            return;
        }

        if (deletePlayerData(targetUUID)) {
            sender.sendMessage(ChatColor.GREEN + "Successfully deleted EnderChest data for " + targetName);

            // Close the inventory if any player is viewing it
            for (Map.Entry<UUID, UUID> entry : openEnderChests.entrySet()) {
                if (entry.getValue().equals(targetUUID)) {
                    Player viewer = Bukkit.getPlayer(entry.getKey());
                    if (viewer != null && viewer.isOnline()) {
                        viewer.closeInventory();
                    }
                }
            }
        } else {
            sender.sendMessage(ChatColor.RED + "Failed to delete EnderChest data for " + targetName);
        }
    }

    private UUID getPlayerUUID(String playerName) {
        // Check online players first
        Player onlinePlayer = Bukkit.getPlayer(playerName);
        if (onlinePlayer != null) {
            return onlinePlayer.getUniqueId();
        }

        // Check offline players
        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerName);
        if (offlinePlayer.hasPlayedBefore()) {
            return offlinePlayer.getUniqueId();
        }

        // Check if exists in database
        return databaseHandler.hasInventory(playerName);
    }

    private void openEnderChest(Player viewer, UUID targetUUID, String targetName) {
        int lines = 6; // Admin view always shows maximum size
        String title = inventoryTitle + targetName;

        Inventory inventory = Bukkit.createInventory(null, lines * 9, title);
        ItemStack[] items = databaseHandler.loadInventory(targetUUID);

        if (items != null) {
            inventory.setContents(Arrays.copyOf(items, lines * 9));
        }

        openEnderChests.put(viewer.getUniqueId(), targetUUID);
        viewer.openInventory(inventory);
        viewer.playSound(viewer.getLocation(), Sound.BLOCK_ENDER_CHEST_OPEN, 1.0f, 1.0f);

        // Send viewing message
        viewer.sendMessage(getMessage("viewing", targetName));
    }

    private boolean deletePlayerData(UUID uuid) {
        try {
            databaseHandler.deleteInventory(uuid);
            return true;
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to delete player data: " + e.getMessage());
            return false;
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player)) return;

        Player player = (Player) event.getPlayer();
        UUID viewerUUID = player.getUniqueId();

        if (!openEnderChests.containsKey(viewerUUID)) return;

        // Check if the closing inventory is an EnderChest managed by this plugin
        if (event.getView().getTitle().startsWith(inventoryTitle)) {
            UUID targetUUID = openEnderChests.get(viewerUUID);

            // Save the inventory asynchronously
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    databaseHandler.saveInventory(targetUUID, event.getInventory().getContents());
                } catch (Exception e) {
                    plugin.getLogger().severe("Error saving inventory: " + e.getMessage());
                }
            });

            openEnderChests.remove(viewerUUID);
            player.playSound(player.getLocation(), Sound.BLOCK_ENDER_CHEST_CLOSE, 1.0f, 1.0f);
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            if (sender.hasPermission("enderchest.admin.view")) {
                completions.add("view");
            }
            if (sender.hasPermission("enderchest.admin.delete")) {
                completions.add("delete");
            }
            if (sender.hasPermission("enderchest.admin.reload")) {
                completions.add("reload");
            }
            return filterCompletions(completions, args[0]);
        }

        if (args.length == 2) {
            // Add online players
            for (Player player : Bukkit.getOnlinePlayers()) {
                completions.add(player.getName());
            }
            // Add players from the database
            completions.addAll(databaseHandler.getStoredPlayerNames());
            return filterCompletions(completions, args[1]);
        }

        return completions;
    }

    private List<String> filterCompletions(List<String> completions, String partial) {
        List<String> filtered = new ArrayList<>();
        String lowercasePartial = partial.toLowerCase();

        for (String str : completions) {
            if (str.toLowerCase().startsWith(lowercasePartial)) {
                filtered.add(str);
            }
        }

        return filtered;
    }
}