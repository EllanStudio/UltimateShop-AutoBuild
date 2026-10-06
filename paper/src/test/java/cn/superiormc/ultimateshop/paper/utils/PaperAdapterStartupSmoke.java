package cn.superiormc.ultimateshop.paper.utils;

/**
 * Class-link smoke: loading the Paper adapters must not resolve a removed
 * 26.2/26.3 DataComponentTypes field during plugin startup.
 */
public final class PaperAdapterStartupSmoke {
    public static void main(String[] args) throws Exception {
        Class.forName("cn.superiormc.ultimateshop.paper.methods.BuildItemPaper");
        Class.forName("cn.superiormc.ultimateshop.paper.methods.DebuildItemPaper");
        Class.forName(SwingAnimationResolver.class.getName());
        System.out.println("PaperAdapterStartupSmoke passed");
    }
}
