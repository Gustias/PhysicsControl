package ru.dymeth.pcontrol.set.parser;

import org.bukkit.*;
import org.bukkit.entity.EntityType;
import ru.dymeth.pcontrol.data.CustomTags;
import ru.dymeth.pcontrol.data.PControlData;
import ru.dymeth.pcontrol.set.CustomEnumSet;
import ru.dymeth.pcontrol.set.EntityTypesSet;
import ru.dymeth.pcontrol.set.TreeTypesSet;
import ru.dymeth.pcontrol.set.material.BlockTypesSet;
import ru.dymeth.pcontrol.set.material.ItemTypesSet;
import ru.dymeth.pcontrol.util.ReflectionUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class TypesSetsParser {
    public static final String CUSTOM_TAG_PREFIX = "physicscontrol_";

    private final @Nonnull PControlData data;
    private final @Nonnull CustomTags tags;
    private final boolean bukkitTagsSupported;

    public TypesSetsParser(@Nonnull PControlData data) {
        this.data = data;
        this.tags = data.getCustomTags();
        this.bukkitTagsSupported = ReflectionUtils.isClassPresent("org.bukkit.Tag");
    }

    @Nonnull
    public Consumer<BlockTypesSet> createBlockTypesParser(@Nonnull List<String> elements, boolean parseVersion) {
        return this.createSetConsumer(elements, "blocks", Material.class, parseVersion);
    }

    @Nonnull
    public Consumer<EntityTypesSet> createEntityTypesParser(@Nonnull List<String> elements, boolean parseVersion) {
        return this.createSetConsumer(elements, "entity_types", EntityType.class, parseVersion);
    }

    @Nonnull
    public Consumer<ItemTypesSet> createItemTypesParser(@Nonnull List<String> elements, boolean parseVersion) {
        return this.createSetConsumer(elements, "items", Material.class, parseVersion);
    }

    @Nonnull
    public Consumer<TreeTypesSet> createTreeTypesParser(@Nonnull List<String> elements, boolean parseVersion) {
        return this.createSetConsumer(elements, "tree_types", TreeType.class, parseVersion);
    }

    private final Map<String, Boolean> supportedVersionsCache = new HashMap<>();

    @Nonnull
    private <E extends Enum<E>, T extends CustomEnumSet<E, ?>> Consumer<T> createSetConsumer(
        @Nonnull List<String> elements,
        @Nonnull String tagRegistryName,
        @Nonnull Class<E> typeClass,
        boolean parseVersion
    ) {
        return set -> {
            for (String elementRaw : elements) {
                try {
                    String[] args = elementRaw.split(" ");
                    if (args.length < 1 || args.length > (parseVersion ? 2 : 1)) {
                        throw new IllegalArgumentException("Wrong element format");
                    }

                    if (parseVersion && args.length > 1 && !this.isVersionSupported(args[0])) continue;

                    String elementOrTagName = args[args.length - 1];

                    if (!elementOrTagName.startsWith("#")) {
                        try {
                            set.add(elementOrTagName.toUpperCase());
                            continue;
                        } catch (IllegalArgumentException enumError) {
                            // Minecraft 26.x split some former enum values (for example EntityType.BOAT)
                            // into multiple registry entries. Keep old PhysicsControl configs working by
                            // falling back to a vanilla tag with the same name when it exists.
                            if (this.tryAddBukkitTag(set, tagRegistryName, typeClass, elementOrTagName)) {
                                continue;
                            }
                            throw enumError;
                        }
                    }

                    elementOrTagName = elementOrTagName.substring("#".length()).toLowerCase();
                    if (elementOrTagName.startsWith(CUSTOM_TAG_PREFIX)) {
                        // plugin tags

                        elementOrTagName = elementOrTagName.substring(CUSTOM_TAG_PREFIX.length());

                        set.addPrimitive(this.tags.getTag(elementOrTagName, typeClass));
                        continue;
                    }

                    // bukkit tags
                    if (!this.tryAddBukkitTag(set, tagRegistryName, typeClass, elementOrTagName)) {
                        throw new IllegalArgumentException("Bukkit tag not found: " + elementOrTagName);
                    }
                } catch (Throwable t) {
                    throw new RuntimeException("Unable to parse element \"" + elementRaw + "\"", t);
                }
            }
        };
    }


    private <E extends Enum<E>, T extends CustomEnumSet<E, ?>> boolean tryAddBukkitTag(
        @Nonnull T set,
        @Nonnull String tagRegistryName,
        @Nonnull Class<E> typeClass,
        @Nonnull String tagName
    ) {
        if (!this.bukkitTagsSupported || !Keyed.class.isAssignableFrom(typeClass)) {
            return false;
        }

        //noinspection unchecked
        Class<? extends Keyed> keyedClass = (Class<? extends Keyed>) typeClass;
        Tag<? extends Keyed> tag = Bukkit.getTag(
            tagRegistryName,
            NamespacedKey.minecraft(tagName.toLowerCase()),
            keyedClass
        );
        if (tag == null) {
            return false;
        }

        //noinspection unchecked
        set.addPrimitive(tag.getValues().stream()
            .map(keyed -> (E) keyed)
            .collect(Collectors.toList()));
        return true;
    }

    @SuppressWarnings("RedundantIfStatement")
    private boolean isVersionSupported(@Nonnull String version) {
        Boolean supported = this.supportedVersionsCache.get(version);
        if (supported != null) return supported;

        String[] args = version.split("-");

        String min;
        String max;
        if (args.length == 1) {
            min = args[0];
            if (!min.endsWith("+")) {
                throw new IllegalArgumentException("Wrong version format (#1): " + version);
            }
            min = min.substring(0, min.length() - "+".length());
            max = "*";
        } else if (args.length == 2) {
            min = args[0];
            max = args[1];
        } else {
            throw new IllegalArgumentException("Wrong version format (#2): " + version);
        }

        if (!min.equals("*") && !this.hasVersion(min, true)) supported = false;
        else if (!max.equals("*") && this.hasVersion(max, false)) supported = false;
        else supported = true;

        this.supportedVersionsCache.put(version, supported);

        return supported;
    }

    private boolean hasVersion(@Nonnull String version, @Nullable Boolean resultIfCurrentVersion) {
        String[] args = version.split("\\.");
        if (args.length < 2 || args.length > 3) {
            throw new IllegalArgumentException("Wrong version format (#3): " + version);
        }
        try {
            int majorVersion = Integer.parseInt(args[0]);
            int minorVersion = Integer.parseInt(args[1]);
            int patchVersion = args.length < 3 ? 0 : Integer.parseInt(args[2]);

            if (resultIfCurrentVersion != null && this.data.isVersion(majorVersion, minorVersion, patchVersion)) {
                return resultIfCurrentVersion;
            }
            return this.data.hasVersion(majorVersion, minorVersion, patchVersion);
        } catch (Exception e) {
            throw new IllegalArgumentException("Wrong version format (#4): " + version);
        }
    }
}
