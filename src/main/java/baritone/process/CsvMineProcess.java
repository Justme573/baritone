package baritone.process;

import baritone.api.pathing.goals.GoalBlock;
import baritone.Baritone;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.utils.BaritoneProcessHelper;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import baritone.api.utils.BlockOptionalMeta;
import baritone.process.ChestManager;
import java.util.Arrays;

public class CsvMineProcess extends BaritoneProcessHelper {
    private enum State {
        IDLE,
        MINING,
        GOING_TO_CHEST,
        DEPOSITING_WALK,
        DEPOSITING_OPEN,
        DEPOSITING_LOOK,
        DEPOSITING_ITEMS,
        GOING_TO_DISCARD,
        DEPOSITING_DISCARD_LOOK,
        DEPOSITING_DISCARD_OPEN,
        DEPOSITING_DISCARD_ITEMS
    }

    private static class ItemGoal {
        public final String blockName;
        public final int amount;

        public ItemGoal(String blockName, int amount) {
            this.blockName = blockName;
            this.amount = amount;
        }
    }
    private State state = State.IDLE;
    private List<ItemGoal> itemGoals = new ArrayList<>();
    private int currentItemIndex = 0;
    private boolean waitingForMine = false;
    private final ChestManager chestManager;
    private static final List<String> NEVER_DEPOSIT = Arrays.asList(
            "pickaxe", "shovel", "axe", "sword", "hoe",
            "helmet", "chestplate", "leggings", "boots",
            "bow", "crossbow", "shield", "totem",
            "flint_and_steel", "compass", "clock"
    );

    public CsvMineProcess(Baritone baritone) {
        super(baritone);
        this.chestManager = new ChestManager(baritone.getDirectory());
    }

    @Override
    public boolean isActive() {
        if (state == State.IDLE) return false;

        if (waitingForMine) {
            logDirect("Inventar slots belegt: " +
                    ctx.player().getInventory().items.stream()
                            .filter(s -> !s.isEmpty()).count() + "/36");
            // Inventar voll? Dann zur Kiste
            if (isInventoryFull()) {
                baritone.getMineProcess().cancel();
                waitingForMine = false;
                state = State.GOING_TO_CHEST;
                logDirect("Inventar voll, fahre zur Kiste...");
                logDirect("Bekannte Storage-Kisten: " +
                        chestManager.getChestsByType(ChestManager.ChestType.STORAGE).size());
                logDirect("Bekannte Discard-Kisten: " +
                        chestManager.getChestsByType(ChestManager.ChestType.DISCARD).size());
                return true;
            }
            // MineProcess fertig?
            if (!baritone.getMineProcess().isActive()) {
                waitingForMine = false;
                currentItemIndex++;
                logDirect("Item fertig, nächstes...");
            }
            return false; // MineProcess soll laufen
        }

        return true;
    }

    private boolean isInventoryFull() {
        return ctx.player().getInventory().items.stream()
                .filter(stack -> !stack.isEmpty())
                .count() >= 36;
    }



    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        switch (state) {
            case MINING: {
                if (currentItemIndex >= itemGoals.size()) {
                    logDirect("Alle Items aus CSV fertig gemined! Fahre zur Kiste...");
                    state = State.GOING_TO_CHEST;
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }

                ItemGoal current = itemGoals.get(currentItemIndex);

                BlockOptionalMeta bom;
                try {
                    bom = new BlockOptionalMeta(current.blockName);
                } catch (Exception e) {
                    logDirect("Unbekannter Block: " + current.blockName + " — wird übersprungen");
                    currentItemIndex++;
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }

                logDirect("Starte Mining: " + current.blockName + " x" + current.amount);
                baritone.getMineProcess().mine(current.amount, bom);
                waitingForMine = true;
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }

            case GOING_TO_CHEST: {
                // Nächste Storage-Kiste finden
                List<ChestManager.ChestEntry> storageChests = chestManager.getChestsByType(ChestManager.ChestType.STORAGE);
                List<ChestManager.ChestEntry> discardChests = chestManager.getChestsByType(ChestManager.ChestType.DISCARD);

                if (storageChests.isEmpty() && discardChests.isEmpty()) {
                    logDirect("Keine Kisten gespeichert! Benutze #savechest storage oder #savechest discard");
                    state = State.MINING;
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }

                // Nächste Kiste berechnen
                net.minecraft.core.BlockPos playerPos = ctx.playerFeet();

                ChestManager.ChestEntry target = null;
                if (!storageChests.isEmpty()) {
                    target = storageChests.stream()
                            .min(java.util.Comparator.comparingDouble(c -> c.pos.distSqr(playerPos)))
                            .orElse(null);
                }
                if (target == null && !discardChests.isEmpty()) {
                    target = discardChests.stream()
                            .min(java.util.Comparator.comparingDouble(c -> c.pos.distSqr(playerPos)))
                            .orElse(null);
                }

                if (target == null) {
                    state = State.MINING;
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }

                // Sind wir nah genug an der Kiste?
                if (playerPos.distSqr(target.pos) <= 9) {
                    state = State.DEPOSITING_LOOK;
                    logDirect("Kiste erreicht, lagere ein...");
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }

                // Zur Kiste navigieren
                return new PathingCommand(
                        new GoalBlock(target.pos),
                        PathingCommandType.SET_GOAL_AND_PATH
                );
            }
            case DEPOSITING_LOOK: {
                List<ChestManager.ChestEntry> discardChests = chestManager.getChestsByType(ChestManager.ChestType.DISCARD);
                List<ChestManager.ChestEntry> storageChests = chestManager.getChestsByType(ChestManager.ChestType.STORAGE);
                net.minecraft.core.BlockPos playerPos = ctx.playerFeet();

                ChestManager.ChestEntry clickTarget = null;
                if (!storageChests.isEmpty()) {
                    clickTarget = storageChests.stream()
                            .min(java.util.Comparator.comparingDouble(c -> c.pos.distSqr(playerPos)))
                            .orElse(null);
                }
                if (clickTarget == null && !discardChests.isEmpty()) {
                    clickTarget = discardChests.stream()
                            .min(java.util.Comparator.comparingDouble(c -> c.pos.distSqr(playerPos)))
                            .orElse(null);
                }

                if (clickTarget == null) {
                    state = State.MINING;
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }

                // Spieler zur Kiste drehen
                net.minecraft.world.phys.Vec3 chestCenter = net.minecraft.world.phys.Vec3.atCenterOf(clickTarget.pos);
                net.minecraft.world.phys.Vec3 eyePos = ctx.player().getEyePosition(1.0f);

                double dx = chestCenter.x - eyePos.x;
                double dy = chestCenter.y - eyePos.y;
                double dz = chestCenter.z - eyePos.z;
                double dist = Math.sqrt(dx * dx + dz * dz);

                float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                float pitch = (float) Math.toDegrees(-Math.atan2(dy, dist));

                baritone.getLookBehavior().updateTarget(
                        new baritone.api.utils.Rotation(yaw, pitch), true
                );

                // Nächsten Tick öffnen
                state = State.DEPOSITING_OPEN;
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
            case DEPOSITING_OPEN: {
                // Baritone stoppen
                List<ChestManager.ChestEntry> discardChests = chestManager.getChestsByType(ChestManager.ChestType.DISCARD);
                List<ChestManager.ChestEntry> storageChests = chestManager.getChestsByType(ChestManager.ChestType.STORAGE);
                net.minecraft.core.BlockPos playerPos = ctx.playerFeet();

                ChestManager.ChestEntry clickTarget = null;
                if (!storageChests.isEmpty()) {
                    clickTarget = storageChests.stream()
                            .min(java.util.Comparator.comparingDouble(c -> c.pos.distSqr(playerPos)))
                            .orElse(null);
                }
                if (clickTarget == null && !discardChests.isEmpty()) {
                    clickTarget = discardChests.stream()
                            .min(java.util.Comparator.comparingDouble(c -> c.pos.distSqr(playerPos)))
                            .orElse(null);
                }

                if (clickTarget == null) {
                    logDirect("Keine Kiste gefunden!");
                    state = State.MINING;
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }

                if (clickTarget.pos.distSqr(ctx.playerFeet()) > 9) {
                    // Zu weit weg — nochmal hinlaufen
                    state = State.GOING_TO_CHEST;
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }

                // GUI schon offen?
                if (ctx.player().containerMenu != ctx.player().inventoryMenu) {
                    state = State.DEPOSITING_ITEMS;
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
                // Kiste anklicken über die Minecraft interactBlock Methode
                ctx.player().connection.send(
                        new net.minecraft.network.protocol.game.ServerboundUseItemOnPacket(
                                net.minecraft.world.InteractionHand.MAIN_HAND,
                                new net.minecraft.world.phys.BlockHitResult(
                                        net.minecraft.world.phys.Vec3.atCenterOf(clickTarget.pos),
                                        net.minecraft.core.Direction.NORTH,
                                        clickTarget.pos,
                                        false
                                ),
                                0
                        )
                );
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }

            case DEPOSITING_ITEMS: {
                // GUI noch offen?
                if (ctx.player().containerMenu == ctx.player().inventoryMenu) {
                    // GUI wurde geschlossen
                    logDirect("Einlagern fertig, zurück zum Minen...");
                    state = State.MINING;
                    waitingForMine = false;
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }

                List<ChestManager.ChestEntry> storageChests = chestManager.getChestsByType(ChestManager.ChestType.STORAGE);
                List<ChestManager.ChestEntry> discardChests = chestManager.getChestsByType(ChestManager.ChestType.DISCARD);
                net.minecraft.core.BlockPos playerPos2 = ctx.playerFeet();

                ChestManager.ChestEntry storageTarget = storageChests.stream()
                        .min(java.util.Comparator.comparingDouble(c -> c.pos.distSqr(playerPos2)))
                        .orElse(null);
                ChestManager.ChestEntry discardTarget = discardChests.stream()
                        .min(java.util.Comparator.comparingDouble(c -> c.pos.distSqr(playerPos2)))
                        .orElse(null);

                net.minecraft.world.inventory.AbstractContainerMenu menu = ctx.player().containerMenu;

                boolean didSomething = false;
                for (int i = 0; i < menu.slots.size(); i++) {
                    net.minecraft.world.inventory.Slot slot = menu.slots.get(i);
                    if (!slot.container.equals(ctx.player().getInventory())) continue;
                    if (!slot.hasItem()) continue;

                    net.minecraft.world.item.ItemStack stack = slot.getItem();
                    String itemName = stack.getItem().getDescriptionId();
                    boolean isProtected = NEVER_DEPOSIT.stream()
                            .anyMatch(itemName::contains);
                    if (isProtected) continue;
                    boolean isOnList = itemGoals.stream().anyMatch(g -> {
                        // coal_ore → coal, iron_ore → raw_iron, etc.
                        String drop = g.blockName
                                .replace("coal_ore", "coal")
                                .replace("iron_ore", "raw_iron")
                                .replace("gold_ore", "raw_gold")
                                .replace("copper_ore", "raw_copper")
                                .replace("diamond_ore", "diamond")
                                .replace("emerald_ore", "emerald")
                                .replace("lapis_ore", "lapis_lazuli")
                                .replace("redstone_ore", "redstone")
                                .replace("nether_quartz_ore", "quartz")
                                .replace("ancient_debris", "netherite_scrap");
                        return itemName.contains(drop) || itemName.contains(g.blockName);
                    });

                    boolean isAtStorage = storageTarget != null && playerPos2.distSqr(storageTarget.pos) <= 9;
                    boolean isAtDiscard = discardTarget != null && playerPos2.distSqr(discardTarget.pos) <= 9;

                    boolean shouldDeposit = (isOnList && isAtStorage) || (!isOnList && isAtDiscard);

                    if (shouldDeposit) {
                        ctx.playerController().windowClick(
                                menu.containerId, i, 0,
                                net.minecraft.world.inventory.ClickType.QUICK_MOVE,
                                ctx.player()
                        );
                        didSomething = true;
                        break;
                    }
                }

                if (!didSomething) {
                    ctx.player().closeContainer();
                    if (!chestManager.getChestsByType(ChestManager.ChestType.DISCARD).isEmpty()) {
                        logDirect("Storage fertig, fahre zur Discard-Kiste...");
                        state = State.GOING_TO_DISCARD;
                    } else {
                        // Sind alle Items erledigt?
                        if (currentItemIndex >= itemGoals.size()) {
                            logDirect("Alles erledigt, Bot stoppt.");
                            onLostControl();
                        } else {
                            logDirect("Einlagern fertig, zurück zum Minen...");
                            state = State.MINING;
                            waitingForMine = false;
                        }
                    }
                }
            }
            case GOING_TO_DISCARD: {
                List<ChestManager.ChestEntry> discardChests = chestManager.getChestsByType(ChestManager.ChestType.DISCARD);
                if (discardChests.isEmpty()) {
                    state = State.MINING;
                    waitingForMine = false;
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }

                net.minecraft.core.BlockPos playerPos = ctx.playerFeet();
                ChestManager.ChestEntry target = discardChests.stream()
                        .min(java.util.Comparator.comparingDouble(c -> c.pos.distSqr(playerPos)))
                        .orElse(null);

                if (target == null) {
                    state = State.MINING;
                    waitingForMine = false;
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }

                if (playerPos.distSqr(target.pos) <= 9) {
                    logDirect("Discard-Kiste erreicht!");
                    state = State.DEPOSITING_DISCARD_LOOK;
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }

                return new PathingCommand(
                        new GoalBlock(target.pos),
                        PathingCommandType.SET_GOAL_AND_PATH
                );
            }

            case DEPOSITING_DISCARD_LOOK: {
                List<ChestManager.ChestEntry> discardChests = chestManager.getChestsByType(ChestManager.ChestType.DISCARD);
                net.minecraft.core.BlockPos playerPos = ctx.playerFeet();

                ChestManager.ChestEntry target = discardChests.stream()
                        .min(java.util.Comparator.comparingDouble(c -> c.pos.distSqr(playerPos)))
                        .orElse(null);

                if (target == null) {
                    state = State.MINING;
                    waitingForMine = false;
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }

                net.minecraft.world.phys.Vec3 chestCenter = net.minecraft.world.phys.Vec3.atCenterOf(target.pos);
                net.minecraft.world.phys.Vec3 eyePos = ctx.player().getEyePosition(1.0f);

                double dx = chestCenter.x - eyePos.x;
                double dy = chestCenter.y - eyePos.y;
                double dz = chestCenter.z - eyePos.z;
                double dist = Math.sqrt(dx * dx + dz * dz);

                float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                float pitch = (float) Math.toDegrees(-Math.atan2(dy, dist));

                baritone.getLookBehavior().updateTarget(
                        new baritone.api.utils.Rotation(yaw, pitch), true
                );

                state = State.DEPOSITING_DISCARD_OPEN;
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }

            case DEPOSITING_DISCARD_OPEN: {
                List<ChestManager.ChestEntry> discardChests = chestManager.getChestsByType(ChestManager.ChestType.DISCARD);
                net.minecraft.core.BlockPos playerPos = ctx.playerFeet();

                ChestManager.ChestEntry target = discardChests.stream()
                        .min(java.util.Comparator.comparingDouble(c -> c.pos.distSqr(playerPos)))
                        .orElse(null);

                if (target == null) {
                    state = State.MINING;
                    waitingForMine = false;
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }

                if (target.pos.distSqr(playerPos) > 9) {
                    state = State.GOING_TO_DISCARD;
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }

                if (ctx.player().containerMenu != ctx.player().inventoryMenu) {
                    state = State.DEPOSITING_DISCARD_ITEMS;
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }

                net.minecraft.world.phys.BlockHitResult hitResult = new net.minecraft.world.phys.BlockHitResult(
                        net.minecraft.world.phys.Vec3.atCenterOf(target.pos),
                        net.minecraft.core.Direction.UP,
                        target.pos,
                        false
                );
                ctx.playerController().processRightClickBlock(
                        ctx.player(),
                        ctx.world(),
                        net.minecraft.world.InteractionHand.MAIN_HAND,
                        hitResult
                );
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }

            case DEPOSITING_DISCARD_ITEMS: {
                if (ctx.player().containerMenu == ctx.player().inventoryMenu) {
                    logDirect("Discard fertig, zurück zum Minen...");
                    state = State.MINING;
                    waitingForMine = false;
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }

                net.minecraft.world.inventory.AbstractContainerMenu menu = ctx.player().containerMenu;
                boolean didSomething = false;

                for (int i = 0; i < menu.slots.size(); i++) {
                    net.minecraft.world.inventory.Slot slot = menu.slots.get(i);
                    if (!slot.container.equals(ctx.player().getInventory())) continue;
                    if (!slot.hasItem()) continue;

                    net.minecraft.world.item.ItemStack stack = slot.getItem();
                    String itemName = stack.getItem().getDescriptionId();

                    // Tools/Rüstung niemals wegwerfen
                    boolean isProtected = NEVER_DEPOSIT.stream().anyMatch(itemName::contains);
                    if (isProtected) continue;

                    // Nur Items die NICHT auf der CSV-Liste stehen in Discard
                    boolean isOnList = itemGoals.stream().anyMatch(g -> {
                        String drop = g.blockName
                                .replace("coal_ore", "coal")
                                .replace("iron_ore", "raw_iron")
                                .replace("gold_ore", "raw_gold")
                                .replace("copper_ore", "raw_copper")
                                .replace("diamond_ore", "diamond")
                                .replace("emerald_ore", "emerald")
                                .replace("lapis_ore", "lapis_lazuli")
                                .replace("redstone_ore", "redstone")
                                .replace("nether_quartz_ore", "quartz")
                                .replace("ancient_debris", "netherite_scrap");
                        return itemName.contains(drop) || itemName.contains(g.blockName);
                    });

                    if (!isOnList) {
                        ctx.playerController().windowClick(
                                menu.containerId, i, 0,
                                net.minecraft.world.inventory.ClickType.QUICK_MOVE,
                                ctx.player()
                        );
                        didSomething = true;
                        break;
                    }
                }

                if (!didSomething) {
                    ctx.player().closeContainer();
                    if (currentItemIndex >= itemGoals.size()) {
                        logDirect("Alles erledigt, Bot stoppt.");
                        onLostControl();
                    } else {
                        logDirect("Discard fertig, zurück zum Minen...");
                        state = State.MINING;
                        waitingForMine = false;
                    }
                }
            }

            default:
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
    }
    private int countInInventory(String blockName) {
        return ctx.player().getInventory().items.stream()
                .filter(stack -> !stack.isEmpty())
                .filter(stack -> stack.getItem().toString().contains(blockName.replace("_", ".")))
                .mapToInt(net.minecraft.world.item.ItemStack::getCount)
                .sum();
    }

    @Override
    public void onLostControl() {
        state = State.IDLE;
    }

    @Override
    public double priority() {
        return 0.5;
    }

    @Override
    public String displayName0() {
        return "CFV Mine Process";
    }

    public void start() {
        loadCsv();
        if (itemGoals.isEmpty()) {
            logDirect("Keine Items in der CSV gefunden, abbruch.");
            return;
        }
        state = State.MINING;
    }

    public void cancel() {
        onLostControl();
    }

    private void loadCsv() {
        itemGoals.clear();
        currentItemIndex = 0;

        Path csvPath = baritone.getPlayerContext().minecraft().gameDirectory
                .toPath().resolve("baritone").resolve("mine.csv");

        if (!Files.exists(csvPath)) {
            logDirect("mine.csv nicht gefunden in .minecraft/baritone/");
            return;
        }

        try (BufferedReader reader = Files.newBufferedReader(csvPath)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue; // Kommentare überspringen

                String[] parts = line.split(",");
                if (parts.length != 2) {
                    logDirect("Ungültige Zeile übersprungen: " + line);
                    continue;
                }

                String blockName = parts[0].trim();
                int amount;
                try {
                    amount = Integer.parseInt(parts[1].trim());
                } catch (NumberFormatException e) {
                    logDirect("Ungültige Menge übersprungen: " + line);
                    continue;
                }

                itemGoals.add(new ItemGoal(blockName, amount));
                logDirect("Geladen: " + blockName + " x" + amount);
            }
        } catch (IOException e) {
            logDirect("Fehler beim Lesen der CSV: " + e.getMessage());
        }
    }
    public ChestManager getChestManager() {
        return chestManager;
    }

}