/*
    Copyright 2020 Anton Vodonosov (avodonosov@yandex.ru).

    This file is part of hashver-maven-plugin.

    hashver-maven-plugin is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    hashver-maven-plugin is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with hashver-maven-plugin.  If not, see <https://www.gnu.org/licenses/>.
*/

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
