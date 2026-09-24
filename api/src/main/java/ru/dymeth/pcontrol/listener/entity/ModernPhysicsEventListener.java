package ru.dymeth.pcontrol.listener.entity;

import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.PluginManager;
import ru.dymeth.pcontrol.PhysicsListener;
import ru.dymeth.pcontrol.data.PControlData;
import ru.dymeth.pcontrol.data.trigger.PControlTrigger;

import javax.annotation.Nonnull;
import java.lang.reflect.Method;
import java.util.logging.Level;

/**
 * Physics controls for mechanics added in Minecraft 26.1/26.2.
 *
 * New Paper events are registered reflectively on purpose. Paper 26.2 server
 * builds do not all expose the same set of API events. Compiling against a
 * newer API and linking those event classes directly makes the whole plugin
 * fail with NoClassDefFoundError on an older stable build (for example build
 * 87). Reflective registration keeps the plugin binary-compatible and enables
 * the native event path automatically when the running Paper build provides it.
 */
public final class ModernPhysicsEventListener extends PhysicsListener {

    private static final String AGE_LOCK_EVENT
        = "io.papermc.paper.event.player.PlayerToggleEntityAgeLockEvent";
    private static final String LUNGE_EVENT
        = "io.papermc.paper.event.entity.EntityLungeEvent";
    private static final String SULFUR_SWALLOW_EVENT
        = "io.papermc.paper.event.entity.SulfurCubeSwallowItemEvent";
    private static final String ENTITY_IGNITE_EVENT
        = "io.papermc.paper.event.entity.EntityIgniteEvent";

    private final PControlTrigger triggerMobAgeLockToggling;
    private final PControlTrigger triggerSpearLunging;
    private final PControlTrigger triggerSulfurCubeSwallowing;
    private final PControlTrigger triggerSulfurCubeTntSwallowing;
    private final PControlTrigger triggerSulfurCubeIgnition;
    private final PControlTrigger triggerSulfurCubeExplosions;
    private final PControlTrigger triggerSulfurCubeExplosionBlockDamage;

    private final boolean minecraft26_2;
    private final boolean nativeSulfurSwallowEvent;

    public ModernPhysicsEventListener(@Nonnull PControlData data) {
        super(data);

        // Do not mark version-specific triggers available just by looking them up.
        // Availability is determined by the actual server version/API below.
        this.triggerMobAgeLockToggling = data.getTriggersRegisty().valueOf("MOB_AGE_LOCK_TOGGLING", false);
        this.triggerSpearLunging = data.getTriggersRegisty().valueOf("SPEAR_LUNGING", false);
        this.triggerSulfurCubeSwallowing = data.getTriggersRegisty().valueOf("SULFUR_CUBE_SWALLOWING", false);
        this.triggerSulfurCubeTntSwallowing = data.getTriggersRegisty().valueOf("SULFUR_CUBE_TNT_SWALLOWING", false);
        this.triggerSulfurCubeIgnition = data.getTriggersRegisty().valueOf("SULFUR_CUBE_IGNITION", false);
        this.triggerSulfurCubeExplosions = data.getTriggersRegisty().valueOf("SULFUR_CUBE_EXPLOSIONS", false);
        this.triggerSulfurCubeExplosionBlockDamage = data.getTriggersRegisty().valueOf("SULFUR_CUBE_EXPLOSION_BLOCK_DAMAGE", false);

        boolean minecraft26_1 = data.hasVersion(26, 1, 0);
        this.minecraft26_2 = data.hasVersion(26, 2, 0);

        if (minecraft26_1 && this.registerOptionalEvent(AGE_LOCK_EVENT, this::handleAgeLockEvent)) {
            this.triggerMobAgeLockToggling.markAvailable();
        }
        if (minecraft26_1 && this.registerOptionalEvent(LUNGE_EVENT, this::handleLungeEvent)) {
            this.triggerSpearLunging.markAvailable();
        }

        boolean swallowNative = false;
        if (this.minecraft26_2) {
            swallowNative = this.registerOptionalEvent(SULFUR_SWALLOW_EVENT, this::handleSulfurSwallowEvent);

            // Build 87 predates SulfurCubeSwallowItemEvent, so player interaction
            // and entity pickup handlers below provide a compatibility fallback.
            this.triggerSulfurCubeSwallowing.markAvailable();
            this.triggerSulfurCubeTntSwallowing.markAvailable();
            this.triggerSulfurCubeExplosions.markAvailable();
            this.triggerSulfurCubeExplosionBlockDamage.markAvailable();

            if (this.registerOptionalEvent(ENTITY_IGNITE_EVENT, this::handleSulfurIgniteEvent)) {
                this.triggerSulfurCubeIgnition.markAvailable();
            } else {
                data.log().info("Paper API does not expose EntityIgniteEvent on this build; "
                    + "SULFUR_CUBE_IGNITION will stay hidden until the server is updated.");
            }

            if (!swallowNative) {
                data.log().info("Paper API does not expose SulfurCubeSwallowItemEvent on this build; "
                    + "using Bukkit interaction/pickup fallback for Sulfur Cube swallowing controls.");
            }
        }
        this.nativeSulfurSwallowEvent = swallowNative;
    }

    /**
     * Compatibility fallback for Paper builds that predate
     * SulfurCubeSwallowItemEvent. Vanilla 26.2 swallowing is initiated by a
     * player interacting with a Sulfur Cube while holding a block.
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    private void on(PlayerInteractEntityEvent event) {
        if (!this.minecraft26_2 || this.nativeSulfurSwallowEvent) return;
        if (!this.isSulfurCube(event.getRightClicked())) return;

        ItemStack item = event.getPlayer().getInventory().getItem(event.getHand());
        if (!this.isPotentialSulfurCubeSwallowItem(item)) return;

        if (!this.data.isActionAllowed(event.getRightClicked().getWorld(), this.triggerSulfurCubeSwallowing)
            || (item.getType() == Material.TNT
                && !this.data.isActionAllowed(event.getRightClicked().getWorld(), this.triggerSulfurCubeTntSwallowing))) {
            event.setCancelled(true);
        }
    }

    /**
     * Covers Sulfur Cubes absorbing dropped block items on builds where that
     * action is exposed through Bukkit's pickup event.
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    private void on(EntityPickupItemEvent event) {
        if (!this.minecraft26_2) return;
        if (!this.isSulfurCube(event.getEntity())) return;

        ItemStack item = event.getItem().getItemStack();
        if (!this.isPotentialSulfurCubeSwallowItem(item)) return;

        if (!this.data.isActionAllowed(event.getEntity().getWorld(), this.triggerSulfurCubeSwallowing)
            || (item.getType() == Material.TNT
                && !this.data.isActionAllowed(event.getEntity().getWorld(), this.triggerSulfurCubeTntSwallowing))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    private void on(ExplosionPrimeEvent event) {
        if (!this.minecraft26_2 || !this.isSulfurCube(event.getEntity())) return;
        this.data.cancelIfDisabled(event, this.triggerSulfurCubeExplosions);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    private void on(EntityExplodeEvent event) {
        if (!this.minecraft26_2 || !this.isSulfurCube(event.getEntity())) return;
        if (!this.data.isActionAllowed(event.getEntity().getWorld(), this.triggerSulfurCubeExplosionBlockDamage)) {
            event.blockList().clear();
        }
    }

    private void handleAgeLockEvent(@Nonnull Event event) {
        if (!(event instanceof Cancellable cancellable)) return;
        Entity entity = this.getEntity(event);
        if (entity == null) return;
        this.data.cancelIfDisabled(cancellable, entity.getWorld(), this.triggerMobAgeLockToggling);
    }

    private void handleLungeEvent(@Nonnull Event event) {
        if (!(event instanceof Cancellable cancellable)) return;
        Entity entity = this.getEntity(event);
        if (entity == null) return;
        this.data.cancelIfDisabled(cancellable, entity.getWorld(), this.triggerSpearLunging);
    }

    private void handleSulfurSwallowEvent(@Nonnull Event event) {
        if (!(event instanceof Cancellable cancellable)) return;
        Entity entity = this.getEntity(event);
        if (entity == null || !this.isSulfurCube(entity)) return;

        if (!this.data.isActionAllowed(entity.getWorld(), this.triggerSulfurCubeSwallowing)) {
            cancellable.setCancelled(true);
            return;
        }

        Object rawItem = this.invokeNoArgs(event, "getNewItem");
        if (rawItem instanceof ItemStack item
            && item.getType() == Material.TNT
            && !this.data.isActionAllowed(entity.getWorld(), this.triggerSulfurCubeTntSwallowing)) {
            cancellable.setCancelled(true);
        }
    }

    private void handleSulfurIgniteEvent(@Nonnull Event event) {
        if (!(event instanceof Cancellable cancellable)) return;
        Entity entity = this.getEntity(event);
        if (entity == null || !this.isSulfurCube(entity)) return;
        this.data.cancelIfDisabled(cancellable, entity.getWorld(), this.triggerSulfurCubeIgnition);
    }

    @SuppressWarnings("unchecked")
    private boolean registerOptionalEvent(@Nonnull String className, @Nonnull OptionalEventHandler handler) {
        try {
            Class<?> rawClass = Class.forName(className, false, this.getClass().getClassLoader());
            if (!Event.class.isAssignableFrom(rawClass)) {
                this.data.log().warning("Optional Paper class is not an Event: " + className);
                return false;
            }

            Class<? extends Event> eventClass = (Class<? extends Event>) rawClass;
            PluginManager pluginManager = this.data.server().getPluginManager();
            pluginManager.registerEvent(
                eventClass,
                this,
                EventPriority.HIGH,
                (listener, event) -> {
                    if (event instanceof Cancellable cancellable && cancellable.isCancelled()) return;
                    try {
                        handler.handle(event);
                    } catch (Throwable t) {
                        this.data.log().log(Level.SEVERE,
                            "Unable to handle optional Paper event " + event.getClass().getName(), t);
                    }
                },
                this.data.getPlugin(),
                true
            );
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        } catch (Throwable t) {
            this.data.log().log(Level.WARNING, "Unable to register optional Paper event " + className, t);
            return false;
        }
    }

    private Entity getEntity(@Nonnull Event event) {
        Object value = this.invokeNoArgs(event, "getEntity");
        return value instanceof Entity ? (Entity) value : null;
    }

    private Object invokeNoArgs(@Nonnull Object target, @Nonnull String methodName) {
        try {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(
                "Unable to call " + target.getClass().getName() + "#" + methodName + "()", e
            );
        }
    }

    private boolean isSulfurCube(@Nonnull Entity entity) {
        return "SULFUR_CUBE".equals(entity.getType().name());
    }

    private boolean isPotentialSulfurCubeSwallowItem(@Nonnull ItemStack item) {
        return !item.getType().isAir() && item.getType().isBlock();
    }

    @FunctionalInterface
    private interface OptionalEventHandler {
        void handle(@Nonnull Event event);
    }
}
