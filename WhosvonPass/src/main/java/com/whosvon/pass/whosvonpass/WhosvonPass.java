package com.whosvon.pass.whosvonpass;

import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.util.*;

public final class WhosvonPass extends JavaPlugin {

    private PassManager passManager;
    private File configFile;
    private FileConfiguration config;

    @Override
    public void onEnable() {
        configFile = new File(getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            saveDefaultConfig();
        }
        config = YamlConfiguration.loadConfiguration(configFile);

        passManager = new PassManager(config);

        Objects.requireNonNull(getCommand("pass")).setExecutor(this);
        getServer().getPluginManager().registerEvents(new BlockBreakListener(passManager, config), this);
        getServer().getPluginManager().registerEvents(new PassMenuListener(passManager), this);

        getLogger().info("WhosvonPass plugin has been enabled.");
    }

    @Override
    public void onDisable() {
        saveConfig(); // Ensure config is saved

        getLogger().info("WhosvonPass plugin has been disabled.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Only players can use this command.");
            return false;
        }

        Player player = (Player) sender;

        if (args.length == 0) {
            passManager.openPassMenu(player);
        } else if (args.length == 1) {
            switch (args[0].toLowerCase()) {
                case "add":
                    if (player.hasPermission("whosvonpass.add")) {
                        passManager.addItemToPass(player.getInventory().getItemInMainHand());
                        player.sendMessage(ChatColor.RED + "Item Added To Pass!");
                    } else {
                        player.sendMessage(ChatColor.RED + "You do not have permission to use this command.");
                    }
                    break;
                case "reset":
                    if (player.hasPermission("whosvonpass.reset")) {
                        passManager.resetData();
                        player.sendMessage(ChatColor.RED + "Pass Data Has Been Reset!");
                    } else {
                        player.sendMessage(ChatColor.RED + "You do not have permission to use this command.");
                    }
                    break;
                case "claim":
                    passManager.claimReward(player);
                    break;
                case "reload":
                    if (player.hasPermission("whosvonpass.reload")) {
                        reloadConfig();
                        passManager.reloadConfig();
                        player.sendMessage(ChatColor.GREEN + "Config reloaded successfully!");
                    } else {
                        player.sendMessage(ChatColor.RED + "You do not have permission to use this command.");
                    }
                    break;
                default:
                    player.sendMessage(ChatColor.RED + "Unknown command. Usage: /pass [add|reset|claim|reload]");
                    break;
            }
        } else {
            player.sendMessage(ChatColor.RED + "Usage: /pass [add|reset|claim|reload]");
        }

        return true;
    }
}

class PassManager {
    private final Map<Player, PlayerPassData> playerData = new HashMap<>();
    private final List<ItemStack> passItems = new ArrayList<>();
    private FileConfiguration config;
    private File configFile;

    public PassManager(FileConfiguration config) {
        this.config = config;
        this.configFile = new File(WhosvonPass.getPlugin(WhosvonPass.class).getDataFolder(), "config.yml");

        loadPassItems();
    }

    private void loadPassItems() {
        ConfigurationSection rewardsSection = config.getConfigurationSection("rewards");
        if (rewardsSection != null) {
            for (String key : rewardsSection.getKeys(false)) {
                ConfigurationSection rewardSection = rewardsSection.getConfigurationSection(key);
                if (rewardSection != null) {
                    ItemStack item = rewardSection.getItemStack("item");
                    if (item != null) {
                        passItems.add(item);
                    }
                }
            }
        }
    }

    public void addItemToPass(ItemStack item) {
        if (passItems.size() < 100) {
            ItemStack itemCopy = item.clone();
            int requiredLevel = passItems.size() + 1;
            ItemMeta meta = itemCopy.getItemMeta();
            if (meta != null) {
                List<String> lore = new ArrayList<>();
                lore.add(ChatColor.GRAY + "Required Level: " + requiredLevel);
                lore.add(ChatColor.GRAY + "Blocks to Break: " + getXpForNextLevel(requiredLevel));
                meta.setLore(lore);
                itemCopy.setItemMeta(meta);
            }
            passItems.add(itemCopy);
            updateConfig();
        }
    }

    private void updateConfig() {
        config.set("rewards", null);
        for (int i = 0; i < passItems.size(); i++) {
            ItemStack item = passItems.get(i);
            String key = "rewards." + i;
            config.set(key + ".level", i + 1);
            config.set(key + ".item", item);
        }

        try {
            config.save(configFile);
        } catch (IOException e) {
            Bukkit.getLogger().warning("Error saving config.yml: " + e.getMessage());
        }
    }

    public void openPassMenu(Player player) {
        Inventory passInventory = createPassInventory(player);
        player.openInventory(passInventory);
    }

    private Inventory createPassInventory(Player player) {
        Inventory passInventory = Bukkit.createInventory(null, 54, "Battle Pass");
        PlayerPassData data = playerData.computeIfAbsent(player, k -> new PlayerPassData());
        Set<Integer> claimedLevels = data.getClaimedLevels();
        int playerLevel = data.getLevel();

        for (int i = 0; i < passItems.size(); i++) {
            ItemStack item = passItems.get(i).clone();
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                List<String> lore = meta.getLore();
                if (lore == null) {
                    lore = new ArrayList<>();
                }
                if (i + 1 > playerLevel) {
                    lore.add(ChatColor.RED + "Insufficient Level");
                } else if (claimedLevels.contains(i + 1)) {
                    lore.add(ChatColor.GREEN + "Unlocked");
                } else {
                    lore.add(ChatColor.RED + "Locked");
                }
                meta.setLore(lore);
                item.setItemMeta(meta);
            }
            passInventory.addItem(item);
        }

        return passInventory;
    }

    public void resetData() {
        playerData.clear();
        passItems.clear();
        updateConfig();
    }

    public void claimReward(Player player) {
        PlayerPassData data = playerData.get(player);
        if (data != null) {
            int currentLevel = data.getLevel();

            // Find the first unclaimed reward
            for (int i = 1; i <= currentLevel; i++) {
                if (!data.getClaimedLevels().contains(i)) {
                    ItemStack reward = passItems.get(i - 1).clone();
                    ItemMeta meta = reward.getItemMeta();
                    if (meta != null) {
                        meta.setLore(null); // Example modification to reward item meta
                        reward.setItemMeta(meta);
                    }
                    player.getInventory().addItem(reward);
                    String successMessage = ChatColor.translateAlternateColorCodes('&', config.getString("messages.claim-success", "You have claimed your level %level% reward!").replace("%level%", String.valueOf(i)));
                    player.sendMessage(successMessage);
                    data.claimReward(i);

                    // Log the claim action
                    WhosvonPass plugin = WhosvonPass.getPlugin(WhosvonPass.class);
                    plugin.getLogger().info(player.getName() + " claimed level " + i + " reward.");
                    return;
                }
            }

            player.sendMessage(ChatColor.RED + config.getString("messages.no-rewards-to-claim", "You do not have any rewards to claim."));
            WhosvonPass plugin = WhosvonPass.getPlugin(WhosvonPass.class);
            plugin.getLogger().warning("No rewards to claim for player: " + player.getName());
        } else {
            player.sendMessage(ChatColor.RED + config.getString("messages.no-rewards-to-claim", "You do not have any rewards to claim."));
            WhosvonPass plugin = WhosvonPass.getPlugin(WhosvonPass.class);
            plugin.getLogger().warning("No rewards to claim for player: " + player.getName());
        }
    }

    public void reloadConfig() {
        config = YamlConfiguration.loadConfiguration(configFile);
        loadPassItems();
    }

    private int getXpForNextLevel(int level) {
        return (int) (50 * Math.pow(1.3, level - 1));
    }

    public void addBlockBreakXp(Player player, int xp) {
        PlayerPassData data = playerData.getOrDefault(player, new PlayerPassData());
        data.addXp(xp);
        playerData.put(player, data);

        int currentLevel = data.getLevel();
        int xpNeededForNextLevel = getXpForNextLevel(currentLevel);

        while (data.getXp() >= xpNeededForNextLevel && currentLevel < 100) {
            currentLevel++;
            data.setLevel(currentLevel);
            xpNeededForNextLevel = getXpForNextLevel(currentLevel);
        }

        new BukkitRunnable() {
            @Override
            public void run() {
                player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(ChatColor.GREEN + "XP: " + data.getXp()));
            }
        }.runTaskLater(WhosvonPass.getPlugin(WhosvonPass.class), 1L);
    }
}

class PlayerPassData {
    private int level = 1;
    private int xp = 0;
    private final Set<Integer> claimedLevels = new HashSet<>();

    public void addXp(int amount) {
        xp += amount;
        while (xp >= getXpForNextLevel(level) && level < 100) {
            level++;
        }
    }

    public boolean canClaimCurrentLevelReward() {
        return !claimedLevels.contains(level) && level <= 100;
    }

    public void claimReward(int level) {
        claimedLevels.add(level);
    }

    public Set<Integer> getClaimedLevels() {
        return claimedLevels;
    }

    public int getLevel() {
        return level;
    }

    public void setLevel(int level) {
        this.level = level;
    }

    public int getXp() {
        return xp;
    }

    private int getXpForNextLevel(int level) {
        return (int) (50 * Math.pow(1.3, level - 1));
    }
}

class BlockBreakListener implements Listener {
    private final PassManager passManager;
    private final FileConfiguration config;

    public BlockBreakListener(PassManager passManager, FileConfiguration config) {
        this.passManager = passManager;
        this.config = config;
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        int xpPerBlock = config.getInt("xp-per-block", 5);
        passManager.addBlockBreakXp(player, xpPerBlock);
    }
}

class PassMenuListener implements Listener {
    private final PassManager passManager;

    public PassMenuListener(PassManager passManager) {
        this.passManager = passManager;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getView().getTitle().equals("Battle Pass")) {
            event.setCancelled(true);
        }
    }
}
