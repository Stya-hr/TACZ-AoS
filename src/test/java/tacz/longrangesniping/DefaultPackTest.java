package tacz.longrangesniping;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import tacz.longrangesniping.config.EligibilityPolicy;
import tacz.longrangesniping.config.ScopeRule;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** Checks the pinned dependency itself, including index-to-display indirection. */
class DefaultPackTest {
    private static final String ROOT = "assets/tacz/custom/tacz_default_gun/";

    @Test void defaultGunsAreSnipersInTheActualPack() throws IOException {
        for (String id : EligibilityPolicy.DEFAULT_GUNS) {
            var index = read(ROOT + "data/tacz/index/guns/" + id.split(":")[1] + ".json");
            assertEquals("sniper", index.get("type").getAsString(), id);
        }
    }

    @Test void scopeLayoutsMatchReferencedDisplaysRatherThanItemNames() throws IOException {
        for (String entry : EligibilityPolicy.DEFAULT_SCOPES) {
            ScopeRule rule = ScopeRule.parse(entry);
            var index = read(ROOT + "data/tacz/index/attachments/" + rule.scopeId().split(":")[1] + ".json");
            assertEquals("scope", index.get("type").getAsString(), rule.scopeId());
            String[] display = index.get("display").getAsString().split(":");
            var zooms = read(ROOT + "assets/" + display[0] + "/display/attachments/" + display[1] + ".json").getAsJsonArray("zoom");
            float[] actual = new float[zooms.size()];
            for (int i = 0; i < actual.length; i++) actual[i] = zooms.get(i).getAsFloat();
            assertTrue(rule.matchesLayout(actual), rule.scopeId());
        }
    }

    private JsonObject read(String path) throws IOException {
        var stream = getClass().getClassLoader().getResourceAsStream(path);
        assertNotNull(stream, path);
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }
}
