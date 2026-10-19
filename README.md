# pvz-mod-95-edition

## 1. Project description

A port of the **95 Edition** (95 版, a Chinese hard-mode remix of *Plants vs. Zombies*) onto this engine - [PvZ-Portable](https://github.com/FabricPvZ/pvz-portable).
The remix itself is the work of **冥谷川恋**; this project only carries its changes over to this engine.
The remix is not a data mod: its author patched the shipped `PlantsVsZombies.exe` - a set of initialisation
constants, definition-table constants and behaviour branches - and this mod carries that patch over, change
by change, as rules the engine runs.

- **Entry point**: `cn.enaium.fabricpvz.mod95.Edition95Mod` (a Kotlin `class`, so Fabric Loader can
  instantiate it through its no-argument constructor) → `Edition95Rules.apply()`.
- **Rules**: `src/main/kotlin/cn/enaium/fabricpvz/mod95/Edition95Rules.kt` (a Kotlin `object`), one rule per
  thing the remix changes.
- **API**: `cn.enaium.fabricpvz:fabric-api:1.2.1`, declared by hand in `build.gradle.kts` - resolved
  transitively at compile time (a compiler reads jar files, not jars inside a jar) and as the aggregate jar
  alone at runtime.
- **End-to-end test**: `src/test/java/.../Edition95EndToEndTest.java` - the real Fabric Loader, the real
  game and a real retail `main.pak`, observing the rules on a real board through the game's own code paths.

The mod **ships no art**: the loose files the 95 Edition is distributed with are a Chinese localisation of
the retail art (menu buttons, almanac, store signs, the final-wave banner, onomatopoeia particles, the
zombie's diary), not art of its own. This mod therefore registers no `api-resource` override and lets the
engine read the retail `main.pak`.

## 2. What it ports

The rebalance falls into a handful of kinds, each landing on the engine's own seam for that kind - a value
the game reads, or a branch it takes:

- **Zombie numbers** - body, helmet and shield health, the type a zombie spawns as, and the body art.
- **Plant numbers** - seed cost, recharge and firing cadence in the plant definition table, plus the
  per-seed spawn numbers.
- **Projectile numbers** - the damage table and the torchwood's conversion table (a pea becomes a snow pea,
  a snow pea a fireball).
- **Board and wave rules** - the zombies a game mode allows, the allowed-levels table, the two spawn
  vetoes, the wave counts and the zombie-point budget every level's waves are composed with, the modes'
  backgrounds, the sun values and their ceiling, the conveyor-belt seed tables, the grave-stone rules, the
  whack-a-zombie wave, the Beghouled winning score and the instant-kill damage.
- **Behaviour rules** - the ones that rewrite a decision rather than a number: the mind-control boost and
  its burn clamp, the cold-effect rewrite, the shield/ladder damage path, the hiding scary-shroom that
  cannot be targeted, the extra shot that moved from the cattail to the gatling pea, the tall nut's ram,
  the explode-o-nut and tall-nut transformations, the bite's damage, the yeti's per-type update moving to
  the flag zombie, and the super-mower toggle that triggers every mower.
- **Attack geometry** - the starfruit's stars fly on their own headings until they reach their rows and
  then sweep forward (five rows in all), the squash and the chomper reach two cells, and the fume-shroom's
  puff covers the whole row ahead.

## 3. About PvZ-Portable

`PvZ-Portable` (the sibling project of this repository) is **the game**: a desktop Java port of *Plants vs.
Zombies* and its framework, running on LWJGL (SDL, OpenGL, OpenAL, stb) - it is the program a player
launches.

**This project is a mod for it and nothing else.** It contains no game code and no assets: it is compiled
against the API the game publishes, it is loaded by Fabric Loader into the running game, and it needs an
installed copy of the game - including a retail installation, whose `main.pak` the engine reads for the
art, the levels and the text. Without the game this jar does nothing.

## Prerequisites

This mod runs on [PvZ-Portable](https://github.com/FabricPvZ/pvz-portable) and needs two more mods next to it:

- **PvZ-Portable** - the engine (the game itself). The repository: <https://github.com/FabricPvZ/pvz-portable>.
- **fabric-language-kotlin** - this mod is written in Kotlin and carries no Kotlin runtime of its own.
  Download: [Modrinth](https://modrinth.com/mod/fabric-language-kotlin) ·
  [CurseForge](https://www.curseforge.com/minecraft/mc-mods/fabric-language-kotlin) ·
  [Fabric's maven](https://maven.fabricmc.net/net/fabricmc/fabric-language-kotlin/).
- **fabric-api** (this workspace's API) - the seams the rules are registered with.
  Download: the [GitHub releases](https://github.com/FabricPvZ/fabric-api/releases).

## License

[MIT](./LICENSE).
