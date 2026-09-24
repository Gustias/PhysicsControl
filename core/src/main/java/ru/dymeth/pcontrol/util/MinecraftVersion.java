package ru.dymeth.pcontrol.util;

import org.bukkit.Server;
import org.bukkit.plugin.Plugin;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.Arrays;

public class MinecraftVersion {
    private final short serverMajorVersion;
    private final short serverMinorVersion;
    private final short serverPatchVersion;

    public MinecraftVersion(@Nonnull Plugin plugin) {
        try {
            String[] sections = this.getMinecraftVersion(plugin.getServer()).split("\\.");

            if (sections.length < 2 || sections.length > 3) {
                throw new IllegalArgumentException("Wrong sections amount: " + Arrays.toString(sections));
            }

            this.serverMajorVersion = this.parseVersionSection(sections[0], "major");
            this.serverMinorVersion = this.parseVersionSection(sections[1], "minor");
            this.serverPatchVersion = sections.length == 2 ? 0 : this.parseVersionSection(sections[2], "patch");
        } catch (Exception e) {
            throw new RuntimeException("Unsupported server version", e);
        }
    }

    @Nonnull
    private String getMinecraftVersion(@Nonnull Server server) {
        String result = this.getMinecraftVersionModern(server);
        return result != null ? result : this.getMinecraftVersionLegacy(server);
    }

    @Nullable
    private String getMinecraftVersionModern(@Nonnull Server server) {
        try {
            // Paper 1.15+ (including the 26.x versioning scheme)
            Method method = server.getClass().getDeclaredMethod("getMinecraftVersion");
            return ((String) method.invoke(server));
        } catch (Throwable t) {
            return null;
        }
    }

    @Nonnull
    private String getMinecraftVersionLegacy(@Nonnull Server server) {
        String result = server.getVersion();
        result = result.substring(
            result.lastIndexOf("(MC: ") + "(MC: ".length(),
            result.length() - ")".length()
        );

        return result;
    }

    @Nonnull
    private Short parseVersionSection(@Nonnull String in, @Nonnull String sectionName) {
        short result;
        try {
            result = Short.parseShort(in);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Unable to parse server " + sectionName + " section: " + in);
        }
        if (result < 0) {
            throw new IllegalArgumentException("Negative value: " + in);
        }
        return result;
    }

    @Override
    public String toString() {
        return this.serverMajorVersion + "." + this.serverMinorVersion + "." + this.serverPatchVersion;
    }

    @SuppressWarnings("RedundantIfStatement")
    public boolean hasVersion(int majorVersion, int minorVersion, int patchVersion) {
        if (this.serverMajorVersion > majorVersion) return true;
        if (this.serverMajorVersion < majorVersion) return false;

        if (this.serverMinorVersion > minorVersion) return true;
        if (this.serverMinorVersion < minorVersion) return false;

        if (this.serverPatchVersion > patchVersion) return true;
        if (this.serverPatchVersion < patchVersion) return false;

        return true;
    }

    @SuppressWarnings("RedundantIfStatement")
    public boolean isVersion(int majorVersion, int minorVersion, int patchVersion) {
        if (this.serverMajorVersion != majorVersion) return false;
        if (this.serverMinorVersion != minorVersion) return false;
        if (this.serverPatchVersion != patchVersion) return false;

        return true;
    }
}
