package ru.dymeth.pcontrol;

import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.event.Cancellable;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import ru.dymeth.pcontrol.data.CustomTags;
import ru.dymeth.pcontrol.data.PControlData;
import ru.dymeth.pcontrol.data.category.CategoriesRegistry;
import ru.dymeth.pcontrol.data.category.PControlCategory;
import ru.dymeth.pcontrol.data.trigger.PControlTrigger;
import ru.dymeth.pcontrol.data.trigger.TriggersRegistry;
import ru.dymeth.pcontrol.inventory.PControlInventory;
import ru.dymeth.pcontrol.inventory.PControlTriggerInventory;
import ru.dymeth.pcontrol.set.parser.TypesSetsParser;
import ru.dymeth.pcontrol.text.CommonColor;
import ru.dymeth.pcontrol.text.NullText;
import ru.dymeth.pcontrol.text.Text;
import ru.dymeth.pcontrol.text.TextHelper;
import ru.dymeth.pcontrol.text.adventure.AdventureTextHelper;
import ru.dymeth.pcontrol.text.bungee.BungeeTextHelper;
import ru.dymeth.pcontrol.util.EntityTypeUtils;
import ru.dymeth.pcontrol.util.FileUtils;
import ru.dymeth.pcontrol.util.LocaleUtils;
import ru.dymeth.pcontrol.util.MinecraftVersion;
import ru.dymeth.pcontrol.util.metrics.Metrics;
import ru.dymeth.pcontrol.util.update.data.PluginDataUpdater;
import ru.dymeth.pcontrol.util.update.jar.PaperPluginUpdater;
import ru.dymeth.pcontrol.util.update.jar.PluginUpdater;
import ru.dymeth.pcontrol.util.update.jar.SpigotPluginUpdater;
import ru.dymeth.pcontrol.versionsadapter.VersionsAdapter_1_13_to_1_20_4;
import ru.dymeth.pcontrol.versionsadapter.VersionsAdapter_1_20_5_and_more;
import ru.dymeth.pcontrol.versionsadapter.VersionsAdapter_1_8_to_1_12_2;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.File;
import java.util.*;
import java.util.function.Function;
import java.util.logging.Level;

public final class PControlDataBukkit implements PControlData {
    private final JavaPlugin plugin;
    private final PluginUpdater pluginUpdater;
    private final int metricsServiceId;
    private final MinecraftVersion serverVersion;
    private final Set<EntityType> removableProjectileTypes;

    private final Map<String, String> messages = new HashMap<>();
    private final Map<PControlCategory, String> categoriesNames = new HashMap<>();
    private final Map<PControlTrigger, String> triggersNames = new HashMap<>();

    private final Map<World, Map<PControlTrigger, Boolean>> states = new HashMap<>();
    private final Map<World, Map<PControlCategory, PControlTriggerInventory>> inventories = new HashMap<>();
    private Metrics metrics = null;
    private String langKey = null;
    private final TypesSetsParser typesSetsParser;
    private final CustomTags customTags;
    private final CategoriesRegistry categories;
    private final TriggersRegistry triggers;
    private final VersionsAdapter versionsAdapter;
    private final TextHelper textHelper;

    PControlDataBukkit(@Nonnull JavaPlugin plugin,
                       int spigotResourceId,
                       @Nonnull String hangarResourceOwner, @Nonnull String hangarResourceSlug,
                       int metricsServiceId
    ) {
        this.plugin = plugin;
        this.pluginUpdater = this.initPluginUpdater(spigotResourceId, hangarResourceOwner, hangarResourceSlug);
        this.metricsServiceId = metricsServiceId;

        try {
            new PluginDataUpdater(plugin);
        } catch (Throwable t) {
            this.plugin.getLogger().log(Level.WARNING, "Unable to update config from previous plugin version", t);
        }

        this.serverVersion = new MinecraftVersion(plugin);
        this.validateServerVersions();

        this.removableProjectileTypes = EntityTypeUtils.matchEntityTypes(null,
            "ARROW",
            "SPECTRAL_ARROW",
            "TIPPED_ARROW",
            "TRIDENT"
        );

        FileUtils.createConfigFileIfNotExist(plugin,
            "logics/_READ_ME_.txt", "logics/_READ_ME_.txt");

        this.customTags = new CustomTags(this);
        this.typesSetsParser = new TypesSetsParser(this);
        this.customTags.parseTags();
        this.categories = new CategoriesRegistry(this);
        this.triggers = new TriggersRegistry(this);
        this.versionsAdapter = this.createVersionsAdapter();

        if (TextHelper.isAdventureFullySupported()) {
            this.textHelper = new AdventureTextHelper();
        } else {
            this.textHelper = new BungeeTextHelper();
        }
    }

    @Nonnull
    private VersionsAdapter createVersionsAdapter() {
        if (!this.hasVersion(1, 13, 0)) {
            return new VersionsAdapter_1_8_to_1_12_2(this);
        } else if (!this.hasVersion(1, 20, 5)) {
            return new VersionsAdapter_1_13_to_1_20_4(this);
        } else {
            return new VersionsAdapter_1_20_5_and_more(this);
        }
    }

    private void validateServerVersions() {
        try {
            // Legacy versions use 1.x; modern releases use calendar-style versions such as 26.2.
            if (this.serverVersion.hasVersion(2, 0, 0) && !this.serverVersion.hasVersion(26, 0, 0)) {
                throw new IllegalArgumentException("Unsupported version gap");
            }
            if (!this.serverVersion.hasVersion(1, 8, 0)) {
                throw new IllegalArgumentException("Too old version. Minimal is 1.8");
            }
            // Legacy complex materials like WOOL no more present in 1.13-1.13.1, but Spigot still have no Tag class.
            // Therefore, there is no easy way to get material sets in 1.13-1.13.1
            if (this.serverVersion.hasVersion(1, 13, 0)
                && !this.serverVersion.hasVersion(1, 13, 2)
            ) {
                throw new IllegalArgumentException("1.13(.0) and 1.13.1 are unsupported");
            }
        } catch (Exception e) {
            throw new RuntimeException("Unsupported server version (" + this.serverVersion + "). "
                + "It must be 1.8-1.12.2, 1.13.2+, or 26.x+", e);
        }
    }

    @Override
    @Nonnull
    public Plugin getPlugin() {
        return this.plugin;
    }

    @Override
    @Nonnull
    public Set<EntityType> getRemovableProjectileTypes() {
        return this.removableProjectileTypes;
    }

    public void reloadConfigs() {
        this.unloadData();

        File configFile = FileUtils.createConfigFileIfNotExist(this.plugin,
            "config.yml", "config.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(configFile);

        if (config.getBoolean("check-for-updates", true)) {
            this.pluginUpdater.startRegularChecking();
        }
        if (config.getBoolean("metrics", true)) {
            this.initMetrics();
        }
        this.initLangKey(config.getString("language"));

        this.reloadLocale();
        this.reloadTriggers();
    }

    public void updatePlugin() {
        if (this.pluginUpdater == null) {
            this.plugin.getLogger().severe("Plugin updates checking disabled in config");
            return;
        }
        this.pluginUpdater.startPluginUpdating();
    }

    @Nonnull
    private PluginUpdater initPluginUpdater(int spigotResourceId,
                                            @Nonnull String hangarResourceOwner, @Nonnull String hangarResourceSlug
    ) {
        String serverVersion = this.plugin.getServer().getVersion();
        if (serverVersion.contains("-Spigot-")) {
            return new SpigotPluginUpdater(this.plugin, spigotResourceId);
        } else {
            return new PaperPluginUpdater(this.plugin, hangarResourceOwner, hangarResourceSlug);
        }
    }

    private void initMetrics() {
        try {
            this.metrics = new Metrics(this.plugin, this.metricsServiceId);
        } catch (Throwable t) {
            this.plugin.getLogger().log(Level.WARNING, "Unable to init metrics", t);
        }
    }

    private void initLangKey(@Nullable String rawLangKey) {
        this.langKey = LocaleUtils.prepareLangKey(this.getClass(), this.plugin.getLogger(), rawLangKey);
    }

    private void reloadLocale() {

        Function<String, String> messageProcessor = msg ->
            msg.replace("%plugin%", this.plugin.getName());

        LocaleUtils.reloadLocale(this.plugin, this.langKey,
            this.categories::valueOf, messageProcessor,
            "categories.yml", this.categoriesNames);

        LocaleUtils.reloadLocale(this.plugin, this.langKey,
            key -> key, messageProcessor,
            "messages.yml", this.messages);

        LocaleUtils.reloadLocale(this.plugin, this.langKey,
            key -> this.triggers.valueOf(key, false), messageProcessor,
            "triggers.yml", this.triggersNames);

        PControlCategory testCategory = this.categories.getTestCategory();
        for (PControlCategory category : this.categories.values()) {
            if (this.categoriesNames.containsKey(category)) continue;
            this.categoriesNames.put(category, category.name());
            if (category == testCategory) continue;
            this.plugin.getLogger().warning("Unable to load name of category " + category);
        }
        for (PControlTrigger trigger : this.triggers.values()) {
            if (this.triggersNames.containsKey(trigger)) continue;
            if (trigger == this.triggers.getIgnoredState()) continue;
            this.triggersNames.put(trigger, trigger.name());
            this.plugin.getLogger().warning("Unable to load name of trigger " + trigger);
        }
    }

    private void reloadTriggers() {
        for (World world : this.plugin.getServer().getWorlds()) {
            this.updateWorldData(world, true);
        }
    }

    void unloadData() {
        this.pluginUpdater.stopRegularChecking();
        if (this.metrics != null) {
            this.metrics.shutdown();
            this.metrics = null;
        }
        this.messages.clear();
        this.states.clear();
        this.plugin.getServer().getOnlinePlayers().forEach(player -> {
            InventoryHolder holder = player.getOpenInventory().getTopInventory().getHolder();
            if (!(holder instanceof PControlInventory)) return;
            player.closeInventory();
        });
        this.inventories.clear();
    }

    @Override
    @Nonnull
    public Text getMessage(@Nonnull String key, @Nonnull String... placeholders) {
        String result = this.messages.get(key);
        if (result == null) {
            if (this.messages.containsKey(key)) return NullText.INSTANCE;
            return this.textHelper.create(key + " " + Arrays.toString(placeholders), CommonColor.RED);
        }
        for (int i = 0; i < placeholders.length; i++) {
            result = result.replace(placeholders[i], placeholders[++i]);
        }
        return this.textHelper.fromAmpersandFormat(result);
    }

    @Override
    @Nonnull
    public String getTriggerName(@Nonnull PControlTrigger trigger) {
        return this.triggersNames.get(trigger);
    }

    @Override
    @Nonnull
    public String getCategoryName(@Nonnull PControlCategory category) {
        return this.categoriesNames.get(category);
    }

    @Nonnull
    public PControlTriggerInventory getInventory(@Nonnull PControlCategory category, @Nonnull World world) {
        return this.inventories.get(world).get(category);
    }

    void updateWorldData(@Nonnull World world, boolean configPriority) {
        File file = FileUtils.createConfigFileIfNotExist(this.plugin,
            "triggers" + File.separator + world.getName() + ".yml",
            null
        );
        YamlConfiguration worldConfig = YamlConfiguration.loadConfiguration(file);

        Map<PControlTrigger, Boolean> configTriggers = new HashMap<>();
        for (String key : worldConfig.getKeys(false)) {
            try {
                PControlTrigger trigger;
                try {
                    trigger = this.triggers.valueOf(key.toUpperCase().replace(" ", "_"), false);
                    if (trigger == this.triggers.getIgnoredState()) throw new IllegalArgumentException();
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("Unknown trigger type");
                }
                if (!worldConfig.isBoolean(key)) {
                    throw new IllegalArgumentException("Is not a boolean value, "
                        + "but \"" + worldConfig.get(key).getClass().getSimpleName() + "\"");
                }
                boolean value = worldConfig.getBoolean(key);
                configTriggers.put(trigger, value);
            } catch (Exception ex) {
                if (configPriority) {
                    this.plugin.getLogger().warning("Unable to load trigger \"" + key + "\" "
                        + "of world \"" + world.getName() + "\": " + ex.getMessage());
                }
            }
        }

        Map<PControlTrigger, Boolean> memoryTriggers = this.states.computeIfAbsent(world, k -> new HashMap<>());
        Map<PControlCategory, PControlTriggerInventory> inventories = this.inventories.computeIfAbsent(world, world1 -> {
            Map<PControlCategory, PControlTriggerInventory> result = new HashMap<>();
            for (PControlCategory category : this.categories.values()) {
                category.prepareIcon(this);
                result.put(category, new PControlTriggerInventory(this, category, world));
            }
            return result;
        });

        boolean firstInit = configTriggers.isEmpty();
        boolean changed = false;
        for (PControlTrigger trigger : this.triggers.values()) {
            Boolean memoryValue = memoryTriggers.get(trigger);
            Boolean configValue = configTriggers.get(trigger);
            boolean currentValue;
            if (configPriority) {
                currentValue = configValue == null ? trigger.getDefaultValue() : configValue;
            } else {
                currentValue = memoryValue == null ? trigger.getDefaultValue() : memoryValue;
            }
            if ((configValue == null || configValue != currentValue) && trigger != this.triggers.getIgnoredState()) {
                worldConfig.set(trigger.name(), currentValue);
                changed = true;
                if (!firstInit) {
                    this.plugin.getLogger().info("Added trigger \"" + this.getTriggerName(trigger) + "\" "
                        + "(" + currentValue + ") for world \"" + world.getName() + "\"");
                }
            }
            if (memoryValue == null || memoryValue != currentValue) {
                memoryTriggers.put(trigger, currentValue);
                inventories.get(trigger.getCategory()).updateTriggerStack(trigger);
            }
        }
        if (!changed) return;
        try {
            worldConfig.save(file);
        } catch (Exception e) {
            this.plugin.getLogger().severe("Unable to save config file " + file);
        }
    }

    void unloadWorldData(@Nonnull World world) {
        this.states.remove(world);
        this.inventories.remove(world);
    }

    @Override
    public boolean hasVersion(int majorVersion, int minorVersion, int patchVersion) {
        return this.serverVersion.hasVersion(majorVersion, minorVersion, patchVersion);
    }

    @Override
    public boolean isVersion(int majorVersion, int minorVersion, int patchVersion) {
        return this.serverVersion.isVersion(majorVersion, minorVersion, patchVersion);
    }

    @Override
    public void cancelIfDisabled(@Nonnull Cancellable event, @Nonnull World world, @Nonnull PControlTrigger trigger) {
        if (trigger == this.triggers.getIgnoredState()) return;
        if (!this.isActionAllowed(world, trigger)) {
            event.setCancelled(true);
        }
    }

    @Override
    public boolean isActionAllowed(@Nonnull World world, @Nonnull PControlTrigger trigger) {
        if (trigger == this.triggers.getIgnoredState()) throw new IllegalArgumentException();
        return this.getWorldTriggers(world).getOrDefault(trigger, false);
    }

    public void switchTrigger(@Nonnull World world, @Nonnull PControlTrigger trigger) {
        if (trigger == this.triggers.getIgnoredState()) return;
        Map<PControlTrigger, Boolean> worldTriggers = this.getWorldTriggers(world);
        worldTriggers.put(trigger, !worldTriggers.get(trigger));
        this.updateWorldData(world, false);
    }

    @Nonnull
    private Map<PControlTrigger, Boolean> getWorldTriggers(@Nonnull World world) {
        Map<PControlTrigger, Boolean> worldTriggers = this.states.get(world);
        if (worldTriggers == null) {
            throw new IllegalArgumentException("Synchronisation error. World " + world.getName() + " not found in cache");
        }
        return worldTriggers;
    }

    @Override
    public void announce(@Nullable World world, @Nonnull Text text) {
        text.send(this.plugin.getServer().getConsoleSender());
        this.plugin.getServer().getOnlinePlayers().stream()
            .filter(player -> player.isOp() || player.hasPermission("physicscontrol.announce"))
            .filter(player -> world == null || player.getWorld() == world)
            .forEach(text::send);
    }

    @Nonnull
    @Override
    public TypesSetsParser getTypesSetsParser() {
        return Objects.requireNonNull(this.typesSetsParser, "Type sets parser not initialized yet");
    }

    @Nonnull
    @Override
    public CustomTags getCustomTags() {
        return Objects.requireNonNull(this.customTags, "Custom tags not initialized yet");
    }

    @Nonnull
    @Override
    public CategoriesRegistry getCategoriesRegistry() {
        return Objects.requireNonNull(this.categories, "Categories registry not initialized yet");
    }

    @Nonnull
    @Override
    public TriggersRegistry getTriggersRegisty() {
        return Objects.requireNonNull(this.triggers, "Triggers registry not initialized yet");
    }

    @Nonnull
    @Override
    public VersionsAdapter getVersionsAdapter() {
        return Objects.requireNonNull(this.versionsAdapter, "Versions adapter not initialized yet");
    }

    @Nonnull
    @Override
    public TextHelper getTextHelper() {
        return Objects.requireNonNull(this.textHelper, "Text helper adapter not initialized yet");
    }
}
