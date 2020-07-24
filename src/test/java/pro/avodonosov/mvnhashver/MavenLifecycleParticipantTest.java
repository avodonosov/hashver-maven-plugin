package pro.avodonosov.mvnhashver;

import org.junit.jupiter.api.Test;
import pro.avodonosov.mvnhashver.MavenLifecycleParticipant.SysPropFile;

import java.util.Arrays;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static pro.avodonosov.mvnhashver.MavenLifecycleParticipant.parseSysPropFilesSpec;

public class MavenLifecycleParticipantTest {

    @Test
    public void parseSysPropFilesSpecTest() {
        assertResults("", null);
        assertResults("required x", "x");
        assertResults("required x; required y", "x,y");
        assertResults("required x; optional y; required z", "x,opt:y,z");
    }

    private void assertResults(String expectedRepresentation, String filesSpec) {
        assertEquals(
                expectedRepresentation,
                print(parseSysPropFilesSpec(filesSpec)));
    }

    private static String print(SysPropFile[] specs) {
        return Arrays.stream(specs)
                .map(s -> (s.required ? "required " : "optional ") + s.file)
                .collect(Collectors.joining("; "));
    }
}
