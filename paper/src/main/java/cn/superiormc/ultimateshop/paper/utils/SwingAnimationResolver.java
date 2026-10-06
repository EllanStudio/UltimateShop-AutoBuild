package cn.superiormc.ultimateshop.paper.utils;

import io.papermc.paper.datacomponent.DataComponentType;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.SwingAnimation;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Runtime-safe bridge for Minecraft 26.2's SWING_ANIMATION and 26.3's
 * ATTACK_ANIMATION/INTERACT_ANIMATION component split.
 *
 * <p>Do not reference the version-specific fields directly: JVM field
 * resolution would throw NoSuchFieldError before a version check could run.
 * Resolution is lazy because the Paper API's DataComponentTypes class needs a
 * live registry implementation when it initializes outside a server.</p>
 */
public final class SwingAnimationResolver {
    private static volatile RuntimeTypes runtimeTypes;

    private SwingAnimationResolver() {
    }

    public record ComponentNames(boolean legacy, boolean split) {
    }

    private record RuntimeTypes(
            ComponentNames names,
            DataComponentType.Valued<SwingAnimation> legacy,
            DataComponentType.Valued<SwingAnimation> attack,
            DataComponentType.Valued<SwingAnimation> interact) {
    }

    /** Pure resolver used by unit tests with fake old/split field sets. */
    public static ComponentNames classify(Iterable<String> fieldNames) {
        Set<String> names = new LinkedHashSet<>();
        for (String name : fieldNames) {
            names.add(name);
        }
        return new ComponentNames(names.contains("SWING_ANIMATION"),
                names.contains("ATTACK_ANIMATION") && names.contains("INTERACT_ANIMATION"));
    }

    /** Returns the detected layout; call this only while a Paper server is running. */
    public static ComponentNames componentNames() {
        return runtime().names();
    }

    public static void setUnified(ItemStack item, SwingAnimation animation) {
        RuntimeTypes types = runtime();
        if (types.names().split() && types.attack() != null && types.interact() != null) {
            item.setData(types.attack(), animation);
            item.setData(types.interact(), animation);
        } else if (types.legacy() != null) {
            item.setData(types.legacy(), animation);
        }
    }

    public static void setAttack(ItemStack item, SwingAnimation animation) {
        RuntimeTypes types = runtime();
        if (types.names().split() && types.attack() != null) {
            item.setData(types.attack(), animation);
        } else if (types.legacy() != null) {
            item.setData(types.legacy(), animation);
        }
    }

    public static void setInteract(ItemStack item, SwingAnimation animation) {
        RuntimeTypes types = runtime();
        if (types.names().split() && types.interact() != null) {
            item.setData(types.interact(), animation);
        } else if (types.legacy() != null) {
            item.setData(types.legacy(), animation);
        }
    }

    public static SwingAnimation attack(ItemStack item) {
        RuntimeTypes types = runtime();
        return types.names().split() && types.attack() != null
                ? value(item, types.attack()) : value(item, types.legacy());
    }

    public static SwingAnimation interact(ItemStack item) {
        RuntimeTypes types = runtime();
        return types.names().split() && types.interact() != null
                ? value(item, types.interact()) : value(item, types.legacy());
    }

    private static SwingAnimation value(ItemStack item, DataComponentType.Valued<SwingAnimation> type) {
        return type != null && item.isDataOverridden(type) ? item.getData(type) : null;
    }

    private static RuntimeTypes runtime() {
        RuntimeTypes result = runtimeTypes;
        if (result == null) {
            synchronized (SwingAnimationResolver.class) {
                result = runtimeTypes;
                if (result == null) {
                    Set<String> names = new LinkedHashSet<>(Arrays.stream(DataComponentTypes.class.getFields())
                            .map(Field::getName).toList());
                    result = new RuntimeTypes(
                            classify(names),
                            resolve("SWING_ANIMATION"),
                            resolve("ATTACK_ANIMATION"),
                            resolve("INTERACT_ANIMATION"));
                    runtimeTypes = result;
                }
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static DataComponentType.Valued<SwingAnimation> resolve(String fieldName) {
        try {
            Field field = DataComponentTypes.class.getField(fieldName);
            return (DataComponentType.Valued<SwingAnimation>) field.get(null);
        } catch (ReflectiveOperationException | ClassCastException ignored) {
            return null;
        }
    }
}
