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
 * resolution would throw NoSuchFieldError before a version check could run.</p>
 */
public final class SwingAnimationResolver {
    private static final ComponentNames NAMES = classify(
            Arrays.stream(DataComponentTypes.class.getFields()).map(Field::getName).toList());
    private static final DataComponentType.Valued<SwingAnimation> LEGACY = resolve("SWING_ANIMATION");
    private static final DataComponentType.Valued<SwingAnimation> ATTACK = resolve("ATTACK_ANIMATION");
    private static final DataComponentType.Valued<SwingAnimation> INTERACT = resolve("INTERACT_ANIMATION");

    private SwingAnimationResolver() {
    }

    public record ComponentNames(boolean legacy, boolean split) {
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

    public static ComponentNames componentNames() {
        return NAMES;
    }

    public static void setUnified(ItemStack item, SwingAnimation animation) {
        if (NAMES.split() && ATTACK != null && INTERACT != null) {
            item.setData(ATTACK, animation);
            item.setData(INTERACT, animation);
        } else if (LEGACY != null) {
            item.setData(LEGACY, animation);
        }
    }

    public static void setAttack(ItemStack item, SwingAnimation animation) {
        if (NAMES.split() && ATTACK != null) {
            item.setData(ATTACK, animation);
        } else if (LEGACY != null) {
            item.setData(LEGACY, animation);
        }
    }

    public static void setInteract(ItemStack item, SwingAnimation animation) {
        if (NAMES.split() && INTERACT != null) {
            item.setData(INTERACT, animation);
        } else if (LEGACY != null) {
            item.setData(LEGACY, animation);
        }
    }

    public static SwingAnimation attack(ItemStack item) {
        if (NAMES.split() && ATTACK != null) {
            return value(item, ATTACK);
        }
        return value(item, LEGACY);
    }

    public static SwingAnimation interact(ItemStack item) {
        if (NAMES.split() && INTERACT != null) {
            return value(item, INTERACT);
        }
        return value(item, LEGACY);
    }

    private static SwingAnimation value(ItemStack item, DataComponentType.Valued<SwingAnimation> type) {
        return type != null && item.isDataOverridden(type) ? item.getData(type) : null;
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
