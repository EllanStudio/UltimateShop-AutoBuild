package cn.superiormc.ultimateshop.paper.utils;

import java.util.Set;

/** Small no-framework regression test for old/split component field resolution. */
public final class SwingAnimationResolverTest {
    public static void main(String[] args) {
        SwingAnimationResolver.ComponentNames old = SwingAnimationResolver.classify(Set.of("SWING_ANIMATION"));
        require(old.legacy() && !old.split(), "old SWING_ANIMATION layout must be detected");

        SwingAnimationResolver.ComponentNames split = SwingAnimationResolver.classify(
                Set.of("ATTACK_ANIMATION", "INTERACT_ANIMATION"));
        require(!split.legacy() && split.split(), "26.3 split animation layout must be detected");

        SwingAnimationResolver.ComponentNames partial = SwingAnimationResolver.classify(
                Set.of("ATTACK_ANIMATION"));
        require(!partial.split(), "one split field must not activate split mode");

        // Runtime field access is intentionally not performed here: Paper's
        // DataComponentTypes needs a live registry implementation outside a server.
        System.out.println("SwingAnimationResolverTest passed: fake old/split layouts");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
