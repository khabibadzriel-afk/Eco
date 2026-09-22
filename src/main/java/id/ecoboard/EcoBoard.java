package id.ecoboard;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

public class EcoBoard extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    /** Data satu player. Semua mulai dari 0. */
    static class Data {
        String name = "?";
        long money, mobKills, kills, deaths, playSec;
        boolean virtual; // akun placeholder (dibuat lewat /pay ke nama yang belum pernah join)
        List<String> pending = new ArrayList<>(); // notif pay yang masuk saat offline
    }

    private final Map<UUID, Data> data = new HashMap<>();
    private final Map<String, UUID> names = new HashMap<>(); // nama lowercase -> uuid
    private final Map<UUID, Scoreboard> boards = new HashMap<>();
    private File file;

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onEnable() {
        saveDefaultConfig();
        file = new File(getDataFolder(), "data.yml");
        load();

        Bukkit.getPluginManager().registerEvents(this, this);
        for (String cmd : new String[]{"pay", "balance", "baltop", "setmoney", "addmoney", "takemoney", "setstat", "resetstats"}) {
            getCommand(cmd).setExecutor(this);
            getCommand(cmd).setTabCompleter(this);
        }

        // tiap 1 detik: playtime + refresh scoreboard
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                getData(p).playSec++;
                if (getConfig().getBoolean("scoreboard.enabled", true)) updateBoard(p);
            }
        }, 20L, 20L);

        // autosave tiap 5 menit
        Bukkit.getScheduler().runTaskTimer(this, this::save, 6000L, 6000L);
    }

    @Override
    public void onDisable() {
        save();
    }

    // ------------------------------------------------------------------ storage

    private void load() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection sec = y.getConfigurationSection("players");
        if (sec == null) return;
        for (String k : sec.getKeys(false)) {
            UUID id;
            try { id = UUID.fromString(k); } catch (IllegalArgumentException e) { continue; }
            String p = "players." + k + ".";
            Data d = new Data();
            d.name = y.getString(p + "name", "?");
            d.money = y.getLong(p + "money");
            d.mobKills = y.getLong(p + "mobkills");
            d.kills = y.getLong(p + "kills");
            d.deaths = y.getLong(p + "deaths");
            d.playSec = y.getLong(p + "playsec");
            d.virtual = y.getBoolean(p + "virtual");
            d.pending = new ArrayList<>(y.getStringList(p + "pending"));
            data.put(id, d);
            names.put(d.name.toLowerCase(), id);
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, Data> e : data.entrySet()) {
            String p = "players." + e.getKey() + ".";
            Data d = e.getValue();
            y.set(p + "name", d.name);
            y.set(p + "money", d.money);
            y.set(p + "mobkills", d.mobKills);
            y.set(p + "kills", d.kills);
            y.set(p + "deaths", d.deaths);
            y.set(p + "playsec", d.playSec);
            y.set(p + "virtual", d.virtual);
            y.set(p + "pending", d.pending);
        }
        try {
            getDataFolder().mkdirs();
            y.save(file);
        } catch (IOException ex) {
            getLogger().severe("Gagal save data.yml: " + ex.getMessage());
        }
    }

    private Data getData(UUID id, String name) {
        Data d = data.get(id);
        if (d == null) {
            d = new Data();
            data.put(id, d);
            // Kalau sebelumnya ada akun placeholder dengan nama ini (dari /pay), gabungkan
            if (name != null) {
                UUID old = names.get(name.toLowerCase());
                Data ov = old == null ? null : data.get(old);
                if (ov != null && ov.virtual && !old.equals(id)) {
                    d.money = ov.money;
                    d.pending.addAll(ov.pending);
                    data.remove(old);
                    names.remove(name.toLowerCase());
                }
            }
        }
        if (name != null && !name.equals(d.name)) {
            names.remove(d.name.toLowerCase());
            d.name = name;
            names.put(name.toLowerCase(), id);
        }
        return d;
    }

    private Data getData(Player p) {
        return getData(p.getUniqueId(), p.getName());
    }

    /** Cari UUID dari nama: online -> pernah join / akun placeholder. Null kalau belum ada. */
    private UUID resolve(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online.getUniqueId();
        return names.get(name.toLowerCase());
    }

    // ------------------------------------------------------------------ format helpers

    private static final java.util.regex.Pattern HEX = java.util.regex.Pattern.compile("&#([A-Fa-f0-9]{6})");

    /** Support &a style dan &#RRGGBB */
    private static String color(String s) {
        java.util.regex.Matcher m = HEX.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            StringBuilder r = new StringBuilder("\u00a7x");
            for (char ch : m.group(1).toCharArray()) r.append('\u00a7').append(ch);
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(r.toString()));
        }
        m.appendTail(sb);
        return ChatColor.translateAlternateColorCodes('&', sb.toString());
    }

    /** 33800000000 -> 33.8B, 50000000 -> 50M */
    static String fmtMoney(long v) {
        String[] suf = {"", "K", "M", "B", "T"};
        double d = v;
        int i = 0;
        while (Math.abs(d) >= 1000 && i < suf.length - 1) {
            d /= 1000;
            i++;
        }
        if (i == 0) return String.valueOf(v);
        d = Math.floor(d * 10) / 10;
        String s = String.format(Locale.US, "%.1f", d);
        if (s.endsWith(".0")) s = s.substring(0, s.length() - 2);
        return s + suf[i];
    }

    /** "50M", "1.5B", "2k", "1000" -> long. Return -1 kalau invalid. */
    static long parseAmount(String in) {
        String s = in.toLowerCase(Locale.ROOT).replace(",", "").trim();
        if (s.isEmpty()) return -1;
        long mult = 1;
        char last = s.charAt(s.length() - 1);
        switch (last) {
            case 'k': mult = 1_000L; break;
            case 'm': mult = 1_000_000L; break;
            case 'b': mult = 1_000_000_000L; break;
            case 't': mult = 1_000_000_000_000L; break;
            default: break;
        }
        if (mult != 1) s = s.substring(0, s.length() - 1);
        try {
            return new BigDecimal(s).multiply(BigDecimal.valueOf(mult))
                    .setScale(0, RoundingMode.DOWN).longValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            return -1;
        }
    }

    /** 43d 2h style */
    static String fmtTime(long sec) {
        long d = sec / 86400, h = (sec % 86400) / 3600, m = (sec % 3600) / 60;
        if (d > 0) return d + "d " + h + "h";
        if (h > 0) return h + "h " + m + "m";
        if (m > 0) return m + "m";
        return sec + "s";
    }

    // ------------------------------------------------------------------ scoreboard

    private Scoreboard buildBoard(int lineCount) {
        Scoreboard sb = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective o = sb.registerNewObjective("eb", "dummy", "EcoBoard");
        o.setDisplaySlot(DisplaySlot.SIDEBAR);
        hideNumbers(o);
        for (int i = 0; i < lineCount; i++) {
            String entry = "\u00a7" + Integer.toHexString(i); // entry unik & tak terlihat
            Team t = sb.registerNewTeam("l" + i);
            t.addEntry(entry);
            o.getScore(entry).setScore(lineCount - i);
        }
        return sb;
    }

    /** Sembunyikan angka merah di kanan sidebar (Paper 1.20.4+ & client 1.20.3+). Diam-diam skip kalau tidak didukung. */
    private void hideNumbers(Objective o) {
        try {
            Class<?> nf = Class.forName("io.papermc.paper.scoreboard.numbers.NumberFormat");
            Object blank = nf.getMethod("blank").invoke(null);
            Objective.class.getMethod("numberFormat", nf).invoke(o, blank);
        } catch (Throwable ignored) {
        }
    }

    private void updateBoard(Player p) {
        List<String> lines = getConfig().getStringList("scoreboard.lines");
        if (lines.size() > 15) lines = lines.subList(0, 15);

        Scoreboard sb = boards.get(p.getUniqueId());
        if (sb == null || sb.getTeams().size() != lines.size()) {
            sb = buildBoard(lines.size());
            boards.put(p.getUniqueId(), sb);
        }
        if (p.getScoreboard() != sb) p.setScoreboard(sb);

        Data d = getData(p);
        Objective o = sb.getObjective("eb");
        String title = getConfig().getString("scoreboard.title", "&f%player%").replace("%player%", p.getName());
        int missing = getConfig().getInt("scoreboard.min-title-length", 0) - ChatColor.stripColor(color(title)).length();
        if (missing > 0) {
            int left = missing / 2;
            title = " ".repeat(left) + title + " ".repeat(missing - left);
        }
        o.setDisplayName(color(title));
        for (int i = 0; i < lines.size(); i++) {
            String text = lines.get(i)
                    .replace("%money%", fmtMoney(d.money))
                    .replace("%mobkills%", String.valueOf(d.mobKills))
                    .replace("%kills%", String.valueOf(d.kills))
                    .replace("%deaths%", String.valueOf(d.deaths))
                    .replace("%playtime%", fmtTime(d.playSec));
            sb.getTeam("l" + i).setPrefix(color(text));
        }
    }

    // ------------------------------------------------------------------ events

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Data d = getData(p);
        if (!d.pending.isEmpty()) {
            for (String m : d.pending) p.sendMessage(m);
            d.pending.clear();
        }
        if (getConfig().getBoolean("scoreboard.enabled", true)) updateBoard(p);
        sendPack(p);
    }

    private void sendPack(Player p) {
        String url = getConfig().getString("resource-pack.url", "");
        if (url == null || url.isEmpty()) return;
        String sha = getConfig().getString("resource-pack.sha1", "");
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!p.isOnline()) return;
            try {
                if (sha != null && sha.length() == 40) {
                    byte[] h = new byte[20];
                    for (int i = 0; i < 20; i++) h[i] = (byte) Integer.parseInt(sha.substring(i * 2, i * 2 + 2), 16);
                    p.setResourcePack(url, h);
                } else {
                    p.setResourcePack(url);
                }
            } catch (Exception ex) {
                getLogger().warning("Gagal kirim resource pack: " + ex.getMessage());
            }
        }, 20L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        boards.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent e) {
        Player v = e.getEntity();
        getData(v).deaths++;
        Player k = v.getKiller();
        if (k != null && !k.equals(v)) getData(k).kills++;
    }

    @EventHandler
    public void onMobDeath(EntityDeathEvent e) {
        if (e.getEntity() instanceof Player) return;
        Player k = e.getEntity().getKiller();
        if (k != null) getData(k).mobKills++;
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        switch (c.getName().toLowerCase()) {
            case "pay": return cmdPay(s, a);
            case "balance": return cmdBalance(s, a);
            case "baltop": return cmdBaltop(s);
            default: return cmdAdmin(s, c.getName().toLowerCase(), a);
        }
    }

    private boolean cmdPay(CommandSender s, String[] a) {
        if (!(s instanceof Player)) {
            s.sendMessage("Hanya player yang bisa pakai /pay.");
            return true;
        }
        Player p = (Player) s;
        if (a.length < 2) {
            p.sendMessage(color("&cUsage: /pay <player> <amount>"));
            return true;
        }
        UUID tid = resolve(a[0]);
        if (tid == null) {
            // Nama belum ada -> buat akun placeholder supaya /pay tetap jalan
            if (!a[0].matches("[A-Za-z0-9_]{1,16}")) {
                p.sendMessage(color("&cNama player tidak valid."));
                return true;
            }
            tid = UUID.nameUUIDFromBytes(("EcoBoardVirtual:" + a[0].toLowerCase()).getBytes(StandardCharsets.UTF_8));
            Data v = new Data();
            v.name = a[0];
            v.virtual = true;
            data.put(tid, v);
            names.put(a[0].toLowerCase(), tid);
        }
        if (tid.equals(p.getUniqueId())) {
            p.sendMessage(color("&cKamu tidak bisa pay diri sendiri."));
            return true;
        }
        long amt = parseAmount(a[1]);
        if (amt <= 0) {
            p.sendMessage(color("&cJumlah tidak valid. Contoh: 1000, 50k, 2M, 1.5B"));
            return true;
        }

        Data from = getData(p);
        Data to = getData(tid, null);
        if (from.money < amt) {
            p.sendMessage(color("&cUang kamu tidak cukup. Saldo: &a$ &f" + fmtMoney(from.money)));
            return true;
        }
        long newTo;
        try {
            newTo = Math.addExact(to.money, amt);
        } catch (ArithmeticException ex) {
            p.sendMessage(color("&cSaldo penerima terlalu besar."));
            return true;
        }
        from.money -= amt;
        to.money = newTo;

        String amtStr = fmtMoney(amt);
        p.sendMessage(color(getConfig().getString("messages.pay-sent", "&fYou paid %player% &a$ %amount%")
                .replace("%player%", to.name).replace("%amount%", amtStr)));

        String recv = color(getConfig().getString("messages.pay-received", "&f%player% paid you &a$ %amount%")
                .replace("%player%", from.name).replace("%amount%", amtStr));
        Player tp = Bukkit.getPlayer(tid);
        if (tp != null) tp.sendMessage(recv);
        else to.pending.add(recv); // dikirim pas dia join
        return true;
    }

    private boolean cmdBalance(CommandSender s, String[] a) {
        UUID id;
        if (a.length >= 1) {
            id = resolve(a[0]);
            if (id == null) {
                s.sendMessage(color("&cPlayer tidak ditemukan."));
                return true;
            }
        } else if (s instanceof Player) {
            id = ((Player) s).getUniqueId();
        } else {
            s.sendMessage("Usage: /balance <player>");
            return true;
        }
        Data d = getData(id, null);
        s.sendMessage(color("&f" + d.name + " &7- &a$ &f" + fmtMoney(d.money)));
        return true;
    }

    private boolean cmdBaltop(CommandSender s) {
        List<Data> top = data.values().stream()
                .sorted((x, y) -> Long.compare(y.money, x.money))
                .limit(10).collect(Collectors.toList());
        s.sendMessage(color("&e&lTop Money"));
        int i = 1;
        for (Data d : top) {
            s.sendMessage(color("&7" + i++ + ". &f" + d.name + " &a$ &f" + fmtMoney(d.money)));
        }
        return true;
    }

    private boolean cmdAdmin(CommandSender s, String cmd, String[] a) {
        if (a.length < 1) {
            s.sendMessage(color("&cUsage: /" + cmd + " <player> "
                    + (cmd.equals("setstat") ? "<mobkills|kills|deaths|playtime> <value>"
                    : cmd.equals("resetstats") ? "" : "<amount>")));
            return true;
        }
        UUID id = resolve(a[0]);
        if (id == null) {
            s.sendMessage(color("&cPlayer &f" + a[0] + " &cbelum pernah join."));
            return true;
        }
        Data d = getData(id, null);

        if (cmd.equals("resetstats")) {
            d.money = d.mobKills = d.kills = d.deaths = d.playSec = 0;
            s.sendMessage(color("&aSemua stat &f" + d.name + " &adireset ke 0."));
            return true;
        }

        if (cmd.equals("setstat")) {
            if (a.length < 3) {
                s.sendMessage(color("&cUsage: /setstat <player> <mobkills|kills|deaths|playtime> <value>"));
                return true;
            }
            long v = parseAmount(a[2]);
            if (v < 0) {
                s.sendMessage(color("&cNilai tidak valid."));
                return true;
            }
            switch (a[1].toLowerCase()) {
                case "mobkills": d.mobKills = v; break;
                case "kills": d.kills = v; break;
                case "deaths": d.deaths = v; break;
                case "playtime": d.playSec = v; break; // dalam detik
                default:
                    s.sendMessage(color("&cStat: mobkills, kills, deaths, playtime (detik)"));
                    return true;
            }
            s.sendMessage(color("&a" + a[1].toLowerCase() + " milik &f" + d.name + " &adiset ke &f" + v));
            return true;
        }

        // setmoney / addmoney / takemoney
        if (a.length < 2) {
            s.sendMessage(color("&cUsage: /" + cmd + " <player> <amount>"));
            return true;
        }
        long amt = parseAmount(a[1]);
        if (amt < 0) {
            s.sendMessage(color("&cJumlah tidak valid. Contoh: 1000, 50k, 2M, 33.8B"));
            return true;
        }
        try {
            switch (cmd) {
                case "setmoney": d.money = amt; break;
                case "addmoney": d.money = Math.addExact(d.money, amt); break;
                default: d.money = Math.max(0, d.money - amt); break; // takemoney
            }
        } catch (ArithmeticException ex) {
            s.sendMessage(color("&cHasil melebihi batas maksimum."));
            return true;
        }
        s.sendMessage(color("&aSaldo &f" + d.name + " &asekarang &a$ &f" + fmtMoney(d.money)));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        String name = c.getName().toLowerCase();
        if (a.length == 1 && !name.equals("baltop")) {
            String pre = a[0].toLowerCase();
            return data.values().stream().map(d -> d.name)
                    .filter(n -> n.toLowerCase().startsWith(pre)).sorted().collect(Collectors.toList());
        }
        if (a.length == 2 && name.equals("setstat")) {
            return Arrays.asList("mobkills", "kills", "deaths", "playtime");
        }
        return Collections.emptyList();
    }
}
