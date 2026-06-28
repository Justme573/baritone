package baritone.process;

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

public class CsvMineProcess extends BaritoneProcessHelper {
    private enum State {
        IDLE,
        MINING,
        GOING_TO_CHEST,
        DEPOSITING
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

    public CsvMineProcess(Baritone baritone) {
        super(baritone);
    }

    @Override
    public boolean isActive() {
        // Nur aktiv wenn wir selbst was zu tun haben, nicht während MineProcess läuft
        if (state == State.IDLE) return false;
        if (waitingForMine) {
            // Prüfen ob MineProcess fertig ist
            if (!baritone.getMineProcess().isActive()) {
                waitingForMine = false;
                currentItemIndex++;
                logDirect("Item fertig, nächstes...");
            }
            return false; // MineProcess soll laufen
        }
        return true;
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        if (currentItemIndex >= itemGoals.size()) {
            logDirect("Alle Items aus CSV fertig gemined!");
            onLostControl();
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

}