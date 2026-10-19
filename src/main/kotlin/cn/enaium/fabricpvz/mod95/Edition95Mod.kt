package cn.enaium.fabricpvz.mod95

import net.fabricmc.api.ModInitializer
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * The 95 Edition mod: the 95 remix of Plants vs Zombies, ported onto this engine.
 *
 * <p>What the remix actually changes is the rebalance its author patched into
 * {@code PlantsVsZombies.exe} - a binary patch on top of the retail executable, not data - and that is
 * what this mod ported, rule by rule, from the reverse engineering in
 * {@code ../verification/research/95-edition-exe-patch.md}: see {@link Edition95Rules}.
 *
 * <p>What it deliberately does <em>not</em> change is the art. The remix ships a set of loose files
 * next to {@code main.pak}, but every one of them is the Chinese localisation of the retail art - the
 * menu buttons, the almanac, the shop sign, the "final wave" banner, the onomatopoeia particles, the
 * zombie notes - rather than art of its own, and the decision here is to keep the game's own
 * resources: the mod registers no resource root and the engine reads everything out of the retail
 * {@code main.pak}. (The API keeps that capability - {@code api-resource}'s {@code ResourceOverrides} -
 * for mods whose art really is their own; this one simply has no art to contribute.)
 *
 * <p>The mod therefore needs nothing but a retail installation: no copy of the remix, no configuration
 * file, no loose files.
 *
 * <p>The mod is a Kotlin mod, so its entry point is a `class` with a no-argument constructor and not
 * an `object`: Fabric Loader instantiates the entry point named in `fabric.mod.json` reflectively
 * through that constructor, and a Kotlin `object` has only a private one - it would have to be named
 * through the `"kotlin"` language adapter of fabric-language-kotlin instead, which nothing here needs.
 * The language module is required all the same (see `fabric.mod.json`), because it is what carries the
 * `kotlin-stdlib` this mod compiles against and does not bundle.
 */
class Edition95Mod : ModInitializer {

    override fun onInitialize() {
        Edition95Rules.apply()
    }

    companion object {

        /**
         * Mod id, matching {@code fabric.mod.json}. A `const val` of the companion: the Java side of
         * this workspace (the end-to-end harness) reads it as {@code Edition95Mod.MOD_ID}, exactly as
         * it read the `static final` field of the Java class this file used to be.
         */
        const val MOD_ID: String = "pvz-95-edition"

        /** The mod's log. */
        val LOGGER: Logger = LoggerFactory.getLogger(MOD_ID)
    }
}
