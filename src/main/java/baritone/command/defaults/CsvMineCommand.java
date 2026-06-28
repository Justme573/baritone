package baritone.command.defaults;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.process.CsvMineProcess;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public class CsvMineCommand extends Command {
    public CsvMineCommand(IBaritone baritone) {
        super(baritone, "csvmine");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        CsvMineProcess process = ((Baritone) baritone).getCsvMineProcess();
        process.start();
        logDirect("CSV Mine gestartet!");
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Mined Items aus CSV-Datei";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Liest Items aus der Datei baritone/mine.csv und mined sie.",
                "",
                "Format der CSV:",
                "  diamond_ore,64",
                "  iron_ore,128",
                "",
                "Usage:",
                "> #csvmine - Startet den Prozess"
        );
    }
}