package tacz.aos.test;

import com.electronwill.nightconfig.toml.TomlParser;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import tacz.aos.TaczArtOfSniping;
import tacz.aos.config.ConfigFileBootstrap;
import tacz.aos.config.ServerConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@GameTestHolder(TaczArtOfSniping.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ConfigFileGameTests {
    @GameTest(setupTicks = 20, template = "empty", batch = "config_bootstrap")
    public static void createsEditableDefaultsWithoutChangingActiveConfig(GameTestHelper helper) throws IOException {
        Path root = scratch();
        var before = ServerConfig.current();
        ConfigFileBootstrap.ensureExists(root.resolve("config"), root.resolve("defaults"));
        String text = Files.readString(root.resolve("config").resolve(ConfigFileBootstrap.FILE_NAME));
        helper.assertTrue(ServerConfig.SPEC.isCorrect(new TomlParser().parse(text)), "Generated TOML does not match server spec");
        helper.assertTrue(text.contains("#") && text.contains("newRegionIntervalTicks"), "Missing comments or latest settings");
        helper.assertTrue(before == ServerConfig.current(), "Creating the file changed active server settings");
        helper.succeed();
    }

    @GameTest(setupTicks = 20, template = "empty", batch = "config_bootstrap")
    public static void preservesExistingUserFileAndPackTemplate(GameTestHelper helper) throws IOException {
        Path root = scratch(), config = root.resolve("config"), defaults = root.resolve("defaults");
        Files.createDirectories(defaults);
        String template = "# Modpack defaults\n[targeting]\nnewRegionIntervalTicks = 37\n";
        Files.writeString(defaults.resolve(ConfigFileBootstrap.FILE_NAME), template);
        ConfigFileBootstrap.ensureExists(config, defaults);
        Path target = config.resolve(ConfigFileBootstrap.FILE_NAME);
        helper.assertTrue(Files.readString(target).equals(template), "Modpack template was replaced with hardcoded defaults");
        String user = "# User settings\n[targeting]\nnewRegionIntervalTicks = 42\n";
        Files.writeString(target, user);
        ConfigFileBootstrap.ensureExists(config, defaults);
        helper.assertTrue(Files.readString(target).equals(user), "Existing user settings were overwritten");
        helper.succeed();
    }

    private static Path scratch() throws IOException {
        return Files.createTempDirectory(Files.createDirectories(Path.of("config-bootstrap-tests")), "case-");
    }
}
