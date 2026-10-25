package com.example.shop;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.UUID;

public class ShopPlugin extends JavaPlugin implements CommandExecutor {

    public static final TextColor BLUE = TextColor.color(0x00a2ff);
    public static final TextColor WHITE = NamedTextColor.WHITE;
    public static final TextColor GREEN = NamedTextColor.GREEN;
    public static final TextColor RED = NamedTextColor.RED;

    private static final DecimalFormat FMT =
            new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.US));

    public record QuickEntry(Material material, int amount) {}

    private Economy econ;
    private File dataFile;
    private YamlConfiguration data;
    private MenuListener menus;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            getLogger().severe("Vault + an economy plugin are required. Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        econ = rsp.getProvider();

        dataFile = new File(getDataFolder(), "data.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);

        menus = new MenuListener(this);
        getServer().getPluginManager().registerEvents(menus, this);
        for (String cmd : new String[]{"shop", "quickbuy", "shards"}) {
            getCommand(cmd).setExecutor(this);
        }
    }

    @Override
    public void onDisable() {
        if (data != null) saveData();
    }

    // ---------------------------------------------------------------- commands

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        String n = c.getName().toLowerCase();
        if (n.equals("shards")) return shardsCommand(s, a);
        if (!(s instanceof Player p)) {
            s.sendMessage("Players only.");
            return true;
        }
        if (n.equals("shop")) menus.openMain(p, false, -1);
        else menus.openQuick(p);
        return true;
    }

    private boolean shardsCommand(CommandSender s, String[] a) {
        if (a.length == 0) {
            if (s instanceof Player p) {
                p.sendMessage(Component.text("Shards: ", BLUE)
                        .append(Component.text(fmt(getShards(p.getUniqueId())), WHITE)));
            }
            return true;
        }
        if (!s.hasPermission("shop.admin") || a.length < 3) {
            s.sendMessage(Component.text("Usage: /shards <give|take|set> <player> <amount>", RED));
            return true;
        }
        Player t = Bukkit.getPlayerExact(a[1]);
        if (t == null) {
            s.sendMessage(Component.text("Player not online.", RED));
            return true;
        }
        long amt;
        try {
            amt = Long.parseLong(a[2]);
        } catch (NumberFormatException ex) {
            s.sendMessage(Component.text("Invalid amount.", RED));
            return true;
        }
        long cur = getShards(t.getUniqueId());
        switch (a[0].toLowerCase()) {
            case "give" -> setShards(t.getUniqueId(), cur + amt);
            case "take" -> setShards(t.getUniqueId(), Math.max(0, cur - amt));
            case "set" -> setShards(t.getUniqueId(), Math.max(0, amt));
            default -> {
                s.sendMessage(Component.text("Usage: /shards <give|take|set> <player> <amount>", RED));
                return true;
            }
        }
        s.sendMessage(Component.text(t.getName() + " now has " + fmt(getShards(t.getUniqueId())) + " shards.", WHITE));
        return true;
    }

    // -------------------------------------------------------------------- data

    private void saveData() {
        try {
            data.save(dataFile);
        } catch (IOException e) {
            getLogger().warning("Could not save data.yml: " + e.getMessage());
        }
    }

    public QuickEntry getQuick(UUID id, int slot) {
        String b = "quick." + id + "." + slot;
        String item = data.getString(b + ".item");
        if (item == null) return null;
        Material m = Material.matchMaterial(item);
        if (m == null) return null;
        return new QuickEntry(m, Math.max(1, data.getInt(b + ".amount", 1)));
    }

    public void setQuick(UUID id, int slot, Material m, int amount) {
        String b = "quick." + id + "." + slot;
        data.set(b + ".item", m.name());
        data.set(b + ".amount", amount);
        saveData();
    }

    public void removeQuick(UUID id, int slot) {
        data.set("quick." + id + "." + slot, null);
        saveData();
    }

    public void clearQuick(UUID id) {
        data.set("quick." + id, null);
        saveData();
    }

    public long getShards(UUID id) {
        return data.getLong("shards." + id, 0L);
    }

    public void setShards(UUID id, long v) {
        data.set("shards." + id, v);
        saveData();
    }

    /** Price of one unit of the material (first shop that sells it) or -1. */
    public double findPrice(Material m) {
        ConfigurationSection shops = getConfig().getConfigurationSection("shops");
        if (shops == null) return -1;
        for (String cat : shops.getKeys(false)) {
            String path = "shops." + cat + ".items." + m.name();
            if (getConfig().contains(path)) return getConfig().getDouble(path);
        }
        return -1;
    }

    // ------------------------------------------------------------------ helpers

    public static String fmt(double d) {
        return FMT.format(d);
    }

    public static String pretty(String s) {
        StringBuilder sb = new StringBuilder();
        for (String w : s.toLowerCase().split("_")) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }

    /** Sends to both chat and action bar. */
    public void send(Player p, Component c) {
        p.sendMessage(c);
        p.sendActionBar(c);
    }

    private int space(Player p, ItemStack st) {
        int free = 0;
        for (ItemStack c : p.getInventory().getStorageContents()) {
            if (c == null || c.getType().isAir()) free += st.getMaxStackSize();
            else if (c.isSimilar(st)) free += Math.max(0, c.getMaxStackSize() - c.getAmount());
        }
        return free;
    }

    private void inventoryFull(Player p) {
        send(p, Component.text("Inventory Full", RED));
    }

    // --------------------------------------------------------------- purchasing

    /** Buys with money (Vault). */
    public boolean buyItem(Player p, ItemStack stack, String name, double total) {
        if (space(p, stack) < stack.getAmount()) {
            inventoryFull(p);
            return false;
        }
        double bal = econ.getBalance(p);
        if (bal < total) {
            send(p, Component.text("You dont have enough money. ", WHITE)
                    .append(Component.text("Need: ", BLUE))
                    .append(Component.text("$" + fmt(total - bal), GREEN)));
            return false;
        }
        if (!econ.withdrawPlayer(p, total).transactionSuccess()) {
            send(p, Component.text("Transaction failed.", RED));
            return false;
        }
        p.getInventory().addItem(stack.clone());
        send(p, Component.text("Purchased " + stack.getAmount() + " ", WHITE)
                .append(Component.text(name, BLUE))
                .append(Component.text(" for ", WHITE))
                .append(Component.text("$" + fmt(total), GREEN)));
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.2f);
        return true;
    }

    /** Buys with shards. */
    public boolean buyWithShards(Player p, ItemStack stack, String name, long cost) {
        if (space(p, stack) < stack.getAmount()) {
            inventoryFull(p);
            return false;
        }
        long bal = getShards(p.getUniqueId());
        if (bal < cost) {
            send(p, Component.text("You dont have enough shards. ", WHITE)
                    .append(Component.text("Need: ", BLUE))
                    .append(Component.text(fmt(cost - bal) + " Shards", GREEN)));
            return false;
        }
        setShards(p.getUniqueId(), bal - cost);
        p.getInventory().addItem(stack.clone());
        send(p, Component.text("Purchased " + stack.getAmount() + " ", WHITE)
                .append(Component.text(name, BLUE))
                .append(Component.text(" for ", WHITE))
                .append(Component.text(fmt(cost) + " Shards", GREEN)));
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.2f);
        return true;
    }
}
