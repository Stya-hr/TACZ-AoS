package tacz.aos.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.toml.TomlWriter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Makes SERVER settings editable before opening a world, without loading them into the active spec. */
public final class ConfigFileBootstrap {
    public static final String FILE_NAME = "tacz_aos-server.toml";

    private ConfigFileBootstrap() {}

    public static void ensureExists(Path configDirectory, Path defaultDirectory) throws IOException {
        Path target = configDirectory.resolve(FILE_NAME);
        if (Files.exists(target)) return;
        Files.createDirectories(configDirectory);
        try {
            Path template = defaultDirectory.resolve(FILE_NAME);
            if (Files.exists(template)) {
                // Preserve modpack defaults just as NeoForge's normal first-world creation does.
                Files.copy(template, target);
            } else {
                var defaults = CommentedConfig.inMemory();
                ServerConfig.SPEC.correct(defaults);
                Files.writeString(target, new TomlWriter().writeToString(defaults), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            }
        } catch (FileAlreadyExistsException ignored) {
            // Another creator won the race. Never overwrite an existing user configuration.
        }
    }
}
