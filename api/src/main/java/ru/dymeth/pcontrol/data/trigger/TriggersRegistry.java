package ru.dymeth.pcontrol.data.trigger;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import ru.dymeth.pcontrol.data.PControlData;
import ru.dymeth.pcontrol.data.category.CategoriesRegistry;
import ru.dymeth.pcontrol.data.category.PControlCategory;
import ru.dymeth.pcontrol.set.material.ItemTypesSet;
import ru.dymeth.pcontrol.util.FileUtils;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.InputStreamReader;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;

public class TriggersRegistry {

    private final @Nonnull PControlData data;
    private final @Nonnull Map<String, PControlTrigger> valuesByName = new LinkedHashMap<>();
    private final @Nonnull PControlTrigger[] allValues;
    private final @Nonnull PControlTrigger ignoredState;

    public TriggersRegistry(@Nonnull PControlData data) {
        this.data = data;

        this.parseTriggers(data.getCategoriesRegistry());

        this.allValues = new PControlTrigger[this.valuesByName.values().size()];
        int i = 0;
        for (PControlTrigger element : this.valuesByName.values()) {
            this.allValues[i++] = element;
        }

        this.ignoredState = this.valueOf("IGNORED_STATE");

        for (PControlTrigger trigger : this.values()) {
            try {
                //noinspection ResultOfMethodCallIgnored
                trigger.getIcon();
            } catch (Throwable t) {
                this.data.log().severe("Unable to find icon of trigger " + trigger.name());
            }
        }
    }

    private void parseTriggers(@Nonnull CategoriesRegistry categories) {
        File configFile = FileUtils.createConfigFileIfNotExist(this.data.getPlugin(),
            "logics/triggers.yml", "logics/triggers.yml");
        YamlConfiguration rootSection = YamlConfiguration.loadConfiguration(configFile);
        this.mergeMissingDefaultTriggers(rootSection, configFile);

        for (String categoryName : rootSection.getKeys(false)) {
            ConfigurationSection categorySection = rootSection.getConfigurationSection(categoryName);
            if (categorySection == null) {
                throw new IllegalArgumentException("Not a section: " + categoryName);
            }
            PControlCategory category;
            try {
                category = categories.valueOf(categoryName);
            } catch (Throwable t) {
                this.data.log().log(Level.SEVERE, "Unable to load triggers category \"" + categoryName + "\"", t);
                continue;
            }
            for (String triggerName : categorySection.getKeys(false)) {
                try {
                    ConfigurationSection triggerSection = categorySection.getConfigurationSection(triggerName);
                    if (triggerSection == null) {
                        throw new IllegalArgumentException("Not a section: " + triggerName);
                    }

                    boolean realtime = triggerSection.getBoolean("realtime", true);
                    boolean defaults = triggerSection.getBoolean("defaults", false);
                    List<String> iconNames = triggerSection.isString("icon")
                        ? Collections.singletonList(triggerSection.getString("icon"))
                        : triggerSection.getStringList("icon");

                    PControlTrigger result = new PControlTrigger(
                        triggerName,
                        category, realtime, defaults,
                        ItemTypesSet.create(false, triggerName + " trigger icon", this.data.log(),
                            this.data.getTypesSetsParser().createItemTypesParser(iconNames, true))
                    );
                    category.addTrigger(result);
                    this.valuesByName.put(triggerName, result);
                } catch (Throwable t) {
                    this.data.log().log(Level.SEVERE, "Unable to load trigger \"" + triggerName + "\"", t);
                }
            }
        }
    }

    /**
     * Adds trigger definitions introduced by newer PhysicsControl builds without
     * overwriting any user-customized trigger metadata already present on disk.
     *
     * Older releases copied logics/triggers.yml only once, which meant newly
     * added triggers never appeared for existing installations.
     */
    private void mergeMissingDefaultTriggers(@Nonnull YamlConfiguration actualConfig, @Nonnull File actualFile) {
        YamlConfiguration defaultConfig = YamlConfiguration.loadConfiguration(
            new InputStreamReader(Objects.requireNonNull(
                this.data.getPlugin().getResource("logics/triggers.yml"),
                "Bundled logics/triggers.yml is missing"
            ))
        );

        boolean changed = this.copyMissingValues(defaultConfig, actualConfig);
        if (!changed) return;

        try {
            actualConfig.save(actualFile);
            this.data.log().info("Added new PhysicsControl trigger definitions to logics/triggers.yml");
        } catch (Exception e) {
            throw new RuntimeException("Unable to update " + actualFile.getAbsolutePath(), e);
        }
    }

    private boolean copyMissingValues(@Nonnull ConfigurationSection source, @Nonnull ConfigurationSection target) {
        boolean changed = false;

        for (String key : source.getKeys(false)) {
            ConfigurationSection sourceSection = source.getConfigurationSection(key);
            if (sourceSection != null) {
                ConfigurationSection targetSection = target.getConfigurationSection(key);
                if (targetSection == null) {
                    targetSection = target.createSection(key);
                    changed = true;
                }
                changed = this.copyMissingValues(sourceSection, targetSection) || changed;
                continue;
            }

            if (!target.contains(key)) {
                target.set(key, source.get(key));
                changed = true;
            }
        }

        return changed;
    }

    @Nonnull
    public PControlTrigger[] values() {
        return this.allValues;
    }

    @Nonnull
    public PControlTrigger valueOf(@Nonnull String name) {
        return this.valueOf(name, true);
    }

    @Nonnull
    public PControlTrigger valueOf(@Nonnull String name, boolean markAvailable) {
        PControlTrigger trigger = this.valuesByName.get(name);
        if (trigger == null) throw new IllegalArgumentException(name);
        if (markAvailable) trigger.markAvailable();
        return trigger;
    }

    @Nonnull
    public PControlTrigger getIgnoredState() {
        return this.ignoredState;
    }
}
