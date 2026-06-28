package baritone.command.defaults;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.process.ChestManager;
import baritone.process.CsvMineProcess;
import net.minecraft.core.BlockPos;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public class SaveChestCommand extends Command {

    public SaveChestCommand(IBaritone baritone) {
        super(baritone, "savechest");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        args.requireMin(1);
        String typeStr = args.getString();

        ChestManager.ChestType type;
        if (typeStr.equalsIgnoreCase("storage")) {
            type = ChestManager.ChestType.STORAGE;
        } else if (typeStr.equalsIgnoreCase("discard")) {
            type = ChestManager.ChestType.DISCARD;
        } else {
            logDirect("Unbekannter Typ: " + typeStr + " — benutze 'storage' oder 'discard'");
            return;
        }

        BlockPos pos = BlockPos.containing(
                ctx.player().getX(),
                ctx.player().getY(),
                ctx.player().getZ()
        );

        CsvMineProcess process = ((Baritone) baritone).getCsvMineProcess();
        process.getChestManager().addChest(type, pos);

        logDirect("Kiste gespeichert als " + typeStr + " bei " + pos.getX() + " " + pos.getY() + " " + pos.getZ());
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        return Stream.of("storage", "discard");
    }

    @Override
    public String getShortDesc() {
        return "Speichert eine Kiste als Storage oder Discard";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Speichert deine aktuelle Position als Kisten-Standort.",
                "",
                "Usage:",
                "> #savechest storage - Speichert eine Storage-Kiste",
                "> #savechest discard - Speichert eine Discard-Kiste"
        );
    }
}