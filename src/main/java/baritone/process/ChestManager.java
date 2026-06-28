package baritone.process;

import net.minecraft.core.BlockPos;

import java.io.*;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

public class ChestManager {

    public enum ChestType {
        STORAGE, DISCARD
    }

    public static class ChestEntry {
        public final ChestType type;
        public final BlockPos pos;

        public ChestEntry(ChestType type, BlockPos pos) {
            this.type = type;
            this.pos = pos;
        }
    }

    private final Path csvPath;
    private final List<ChestEntry> chests = new ArrayList<>();

    public ChestManager(Path baritoneDirectory) {
        this.csvPath = baritoneDirectory.resolve("chests.csv");
        load();
    }

    private void load() {
        chests.clear();
        if (!Files.exists(csvPath)) return;

        try (BufferedReader reader = Files.newBufferedReader(csvPath)) {
            String line;
            boolean firstLine = true;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (firstLine) { firstLine = false; continue; } // Header überspringen
                if (line.isEmpty() || line.startsWith("#")) continue;

                String[] parts = line.split(",");
                if (parts.length != 4) continue;

                ChestType type = parts[0].trim().equalsIgnoreCase("storage")
                        ? ChestType.STORAGE : ChestType.DISCARD;
                int x = Integer.parseInt(parts[1].trim());
                int y = Integer.parseInt(parts[2].trim());
                int z = Integer.parseInt(parts[3].trim());

                chests.add(new ChestEntry(type, new BlockPos(x, y, z)));
            }
        } catch (IOException e) {
            System.err.println("Fehler beim Laden der chests.csv: " + e.getMessage());
        }
    }

    public void addChest(ChestType type, BlockPos pos) {
        chests.add(new ChestEntry(type, pos));
        save();
    }

    private void save() {
        try (BufferedWriter writer = Files.newBufferedWriter(csvPath)) {
            writer.write("type,x,y,z");
            writer.newLine();
            for (ChestEntry entry : chests) {
                writer.write(entry.type.name().toLowerCase()
                        + "," + entry.pos.getX()
                        + "," + entry.pos.getY()
                        + "," + entry.pos.getZ());
                writer.newLine();
            }
        } catch (IOException e) {
            System.err.println("Fehler beim Speichern der chests.csv: " + e.getMessage());
        }
    }

    public List<ChestEntry> getChestsByType(ChestType type) {
        List<ChestEntry> result = new ArrayList<>();
        for (ChestEntry entry : chests) {
            if (entry.type == type) result.add(entry);
        }
        return result;
    }
}