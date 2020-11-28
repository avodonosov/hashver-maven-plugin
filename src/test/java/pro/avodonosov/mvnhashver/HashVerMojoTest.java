package pro.avodonosov.mvnhashver;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HashVerMojoTest {
    @Test
    public void cvsListMemberTest() {
        assertFalse(HashVerMojo.csvListMember("a", null));
        assertFalse(HashVerMojo.csvListMember("a", ""));
        assertFalse(HashVerMojo.csvListMember("a", "b"));
        assertFalse(HashVerMojo.csvListMember("a", "b,c,d"));
        assertFalse(HashVerMojo.csvListMember("a", "aaa"));
        assertFalse(HashVerMojo.csvListMember("a", "aaa,b"));
        assertFalse(HashVerMojo.csvListMember("a", "aaa,,b"));
        assertFalse(HashVerMojo.csvListMember("a", "aaa,b,"));
        assertTrue(HashVerMojo.csvListMember("a", "a"));
        assertTrue(HashVerMojo.csvListMember("a", "a,b,c"));
        assertTrue(HashVerMojo.csvListMember("a", "c,b,a"));
        assertTrue(HashVerMojo.csvListMember("a", "c,a,b"));
        assertTrue(HashVerMojo.csvListMember("a", "c,,a,b"));
        assertTrue(HashVerMojo.csvListMember("a", "c,a,,b"));
        assertTrue(HashVerMojo.csvListMember("a", "c,a,a,b"));
        assertFalse(HashVerMojo.csvListMember("a", "c,  a, b"));
        assertTrue(HashVerMojo.csvListMember("a", "a, b, c"));
        assertTrue(HashVerMojo.csvListMember("a", "a,, b, c"));
    }

    @Test
    public void hashVerJsonTest() {
        assertEquals("{\"a\": \"b\"}\n",
                HashVerMojo.hashVerJson(map("a", "b")));

        assertEquals("{\"a\": \"b\",\n" +
                     " \"x\": \"y\"}\n",
                HashVerMojo.hashVerJson(map(
                        "a", "b",
                        "x", "y")));

        assertEquals("{\"a\": \"b\",\n" +
                     " \"com.domain.project:version\": \"-lDRryjx9THUkYTgiF5-in4BiVQ.Sk5OTMzTwxuVkz9fRk1QA1bPrvI\",\n" +
                     " \"t\\\":\\n:\": \"z23,\\\"\\t\",\n" +
                     " \"x\": \"y\"}\n",
                HashVerMojo.hashVerJson(map(
                        "x", "y",
                        "a", "b",
                        "t\":\n:", "z23,\"\t",
                        "com.domain.project:version", "-lDRryjx9THUkYTgiF5-in4BiVQ.Sk5OTMzTwxuVkz9fRk1QA1bPrvI")));
    }

    static Map<String, String> map(String... keyVals) {
        if (keyVals.length % 2 != 0) {
            throw new IllegalArgumentException("Odd number of arguments");
        }

        HashMap<String, String> map = new HashMap<>(keyVals.length / 2);
        for (int i = 0; i < keyVals.length; i += 2) {
            map.put(keyVals[i], keyVals[i+1]);
        }
        return map;
    }
}
