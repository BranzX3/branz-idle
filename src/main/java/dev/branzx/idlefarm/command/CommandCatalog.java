package dev.branzx.idlefarm.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Single source of truth for command discovery. Execution stays in the
 * command handlers, while help, tab completion and GUI hints share this map.
 */
public final class CommandCatalog {

    public enum Audience {
        PLAYER, ADMIN
    }

    public record Entry(
            String name,
            String syntax,
            String description,
            String category,
            Audience audience,
            String permission,
            List<String> aliases
    ) {
        public Entry {
            aliases = List.copyOf(aliases);
        }

        public boolean matches(String value) {
            if (name.equalsIgnoreCase(value)) {
                return true;
            }
            return aliases.stream().anyMatch(alias -> alias.equalsIgnoreCase(value));
        }
    }

    private static final List<Entry> PLAYER_BASE = List.of(
            player("balance", "", "ยอดเงิน เวลาออนไลน์ และจำนวน Node", "overview"),
            player("top", "", "อันดับผู้เล่นตาม Coins", "overview"),
            player("nodes", "", "เปิดรายการ Node", "territory"),
            player("map", "", "เปิดแผนที่ Territory", "territory"),
            player("claim", "<type>", "ยึด chunk ที่ยืนอยู่", "territory"),
            player("unclaim", "", "ยกเลิก Node ที่ยืนอยู่", "territory"),
            player("collect", "[nodeId]", "เก็บผลผลิตของ Node ปัจจุบันหรือตาม id", "territory"),
            player("convert", "<type>", "เปลี่ยนประเภท Node ปัจจุบัน", "territory"),
            player("visit", "<player>", "ไปเยี่ยม Residential ของผู้เล่น", "territory"),
            player("trust", "<player> [role]", "ให้สิทธิ์ใน Territory", "social"),
            player("untrust", "<player>", "ถอนสิทธิ์ใน Territory", "social"),
            player("hire", "", "เปิดหน้าจ้าง Worker", "workers"),
            player("bag", "", "เปิดกระเป๋า Worker", "workers"),
            player("fuse", "", "เปิด Fuse Station", "workers"),
            player("assign", "", "มอบหมาย Worker ให้ Node", "workers"),
            player("eject", "<slot>", "นำ Worker ออกจาก Node", "workers"),
            restrictedPlayer("skin", "<name>", "เปลี่ยนสกิน Worker", "workers", "idlefarm.skin"),
            player("warehouse", "", "เปิด Warehouse", "economy"),
            player("shop", "", "เปิด Boosters และ Perks", "economy"),
            player("credits", "", "ดู Credits และประวัติ", "economy"),
            player("trade", "[accept|decline|offer|view|confirm|cancel]",
                    "จัดการ trade ที่กำลังดำเนินอยู่", "social"),
            player("progress", "", "เปิด Chronicle และเป้าหมาย", "progress",
                    "chronicle", "journal", "commissions", "projects"),
            player("focus", "", "ตั้ง Node ปัจจุบันเป็น Focused Node", "progress"),
            player("commission", "<type>", "เลือก Commission", "progress"),
            player("chapter", "", "รับรางวัล Starter Chapter", "progress"),
            player("project", "<id>", "ส่งของเข้า Project", "progress"),
            player("build", "<specialization|refinement|mastery> <value>", "ปรับแต่ง Node", "progress"),
            player("explore", "[info|prepare|start|claim] [nodeId]",
                    "จัดการ Exploration ของ Node", "progress"),
            player("expedition", "", "เปิด Global Expedition", "progress")
    );

    private static final List<Entry> PLAYER;

    static {
        List<Entry> entries = new ArrayList<>(PLAYER_BASE);
        entries.add(player("frontier",
                "[info|craft <tier>|repair|train <material> <amount>]",
                "Profession, crafting and equipment for Lv.101+", "progress"));
        PLAYER = List.copyOf(entries);
    }

    private static final List<Entry> ADMIN = List.of(
            admin("dashboard", "", "เปิด Admin Hub", "overview", "idlefarm.admin"),
            admin("node", "", "ตรวจสอบ Node ที่ยืนอยู่", "inspection", "idlefarm.admin.operations"),
            admin("claims", "<player>", "ดู claim ของผู้เล่น", "inspection", "idlefarm.admin.operations"),
            admin("audit", "[player]", "ดู audit log ล่าสุด", "inspection", "idlefarm.admin.audit"),
            admin("metrics", "", "ดู economy/telemetry summary", "system", "idlefarm.admin.audit"),
            admin("validate", "", "ตรวจสอบ content ก่อน publish", "system", "idlefarm.admin.content"),
            admin("reload", "<reason>", "โหลด config, pools และ schematics ใหม่", "system", "idlefarm.admin.operations"),
            admin("pool", "[type[.bracket-N]|publish|rollback|discard] [reason]",
                    "เปิด/จัดการ drop pool draft", "content", "idlefarm.admin.content"),
            admin("schem", "<action> ...", "เครื่องมือสร้าง schematic", "content", "idlefarm.admin.content"),
            admin("npc", "<refresh|list|state> ... [reason]", "ตรวจสอบและ refresh Worker NPC", "content", "idlefarm.admin.content"),
            admin("event", "<spawn|cancel|list> [type] [reason]", "จัดการ Exploration event", "operations", "idlefarm.admin.operations"),
            admin("explevel", "<level> <reason>", "ตั้ง Exploration level ของ Node", "operations", "idlefarm.admin.operations"),
            admin("setcap", "<player> <base> <bonus> <reason>", "ตั้ง Node cap", "economy", "idlefarm.admin.economy"),
            admin("give", "<money|item> ... <reason>", "ปรับ Coins หรือมอบ item", "economy", "idlefarm.admin.economy"),
            admin("credits", "<player> <amount> <reason>", "ปรับ Credits", "economy", "idlefarm.admin.economy"),
            admin("forceunclaim", "<world> <x> <z> <reason>", "บังคับยกเลิก claim", "danger", "idlefarm.admin.operations")
    );

    private CommandCatalog() {
    }

    public static List<Entry> playerEntries() {
        return PLAYER;
    }

    public static List<Entry> adminEntries() {
        return ADMIN;
    }

    public static Entry findPlayer(String name) {
        return PLAYER.stream().filter(entry -> entry.matches(name)).findFirst().orElse(null);
    }

    public static Entry findAdmin(String name) {
        return ADMIN.stream().filter(entry -> entry.matches(name)).findFirst().orElse(null);
    }

    public static List<String> categories(Audience audience) {
        List<Entry> entries = audience == Audience.PLAYER ? PLAYER : ADMIN;
        return entries.stream().map(Entry::category).distinct().toList();
    }

    public static List<Entry> inCategory(Audience audience, String category) {
        List<Entry> entries = audience == Audience.PLAYER ? PLAYER : ADMIN;
        return entries.stream()
                .filter(entry -> entry.category().equalsIgnoreCase(category))
                .toList();
    }

    public static List<String> suggestions(List<Entry> entries, String prefix) {
        String normalized = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.name().startsWith(normalized)) {
                result.add(entry.name());
            }
        }
        return result;
    }

    private static Entry player(String name, String syntax, String description, String category,
                                String... aliases) {
        return new Entry(name, syntax, description, category, Audience.PLAYER, null, List.of(aliases));
    }

    private static Entry restrictedPlayer(String name, String syntax, String description, String category,
                                          String permission) {
        return new Entry(name, syntax, description, category, Audience.PLAYER, permission, List.of());
    }

    private static Entry admin(String name, String syntax, String description, String category,
                               String permission) {
        return new Entry(name, syntax, description, category, Audience.ADMIN, permission, List.of());
    }
}
