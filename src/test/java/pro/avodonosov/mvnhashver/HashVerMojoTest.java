package pro.avodonosov.mvnhashver;

import org.junit.jupiter.api.Test;

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
}
