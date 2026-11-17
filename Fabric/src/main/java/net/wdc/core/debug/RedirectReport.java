package net.wdc.core.debug;

import net.wdc.core.redirect.ReadRedirect;
import net.wdc.core.redirect.RedirectCounts;

import java.util.List;

public final class RedirectReport {

    private RedirectReport() {}

    public static String f3Line() {
        return f3Line(ReadRedirect.enabled(), ReadRedirect.counts());
    }

    public static String f3Line(boolean enabled, RedirectCounts counts) {
        return "§7WDC Redirect: " + (enabled ? "§aON" : "§cOFF") + " §7served=§f" + counts.served()
                + " §7fallback=§e" + counts.fallback();
    }

    public static List<String> statusLines(boolean enabled, boolean configDefault, RedirectCounts counts) {
        return List.of(
                "§7Hot-read redirect (experimental): " + (enabled ? "§aON" : "§cOFF") + " §7— config default "
                        + (configDefault ? "on" : "off"),
                "§7served §f" + counts.served() + " §7(getBlockState §f" + counts.blockServed() + "§7, getFluidState §f"
                        + counts.fluidServed() + "§7)",
                "§7fell back to vanilla §f" + counts.fallback() + " §7(getBlockState §f" + counts.blockFallback()
                        + "§7, getFluidState §f" + counts.fluidFallback() + "§7)",
                "§7Counts start again whenever the redirect is turned on or off or on /worlddata redirect reset."
                        + " They are read counts, not a time saving: compare MSPT with /worlddata session and Spark.");
    }

    public static String finalLine(RedirectCounts counts) {
        return "§7That run: served §f" + counts.served() + " §7(getBlockState §f" + counts.blockServed()
                + "§7, getFluidState §f" + counts.fluidServed() + "§7), fell back §f" + counts.fallback()
                + " §7(getBlockState §f" + counts.blockFallback() + "§7, getFluidState §f" + counts.fluidFallback() + "§7).";
    }
}
