package cn.enaium.fabricpvz.mod95.test

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.ArrayList
import java.util.Arrays
import java.util.HashSet
import java.util.HexFormat
import java.util.Locale
import java.util.TreeSet

import cn.enaium.fabricpvz.api.PvzFabricApi
import cn.enaium.fabricpvz.api.plant.BoardOverrides
import cn.enaium.fabricpvz.api.plant.PlantOverride
import cn.enaium.fabricpvz.api.plant.PlantOverrides
import cn.enaium.fabricpvz.api.plant.ProjectileOverride
import cn.enaium.fabricpvz.api.plant.ProjectileOverrides
import cn.enaium.fabricpvz.api.resource.ResourceOverrides
import cn.enaium.fabricpvz.api.zombie.ZombieOverride
import cn.enaium.fabricpvz.api.zombie.ZombieOverrides
import cn.enaium.fabricpvz.mod95.Edition95Mod
import cn.enaium.fabricpvz.portable.lawn.BackgroundType
import cn.enaium.fabricpvz.portable.lawn.Board
import cn.enaium.fabricpvz.portable.lawn.Challenge
import cn.enaium.fabricpvz.portable.lawn.Coin
import cn.enaium.fabricpvz.portable.lawn.CoinMotion
import cn.enaium.fabricpvz.portable.lawn.CoinType
import cn.enaium.fabricpvz.portable.lawn.DamageFlags
import cn.enaium.fabricpvz.portable.lawn.GameConstants
import cn.enaium.fabricpvz.portable.lawn.GameMode
import cn.enaium.fabricpvz.portable.lawn.GameScenes
import cn.enaium.fabricpvz.portable.lawn.GridItem
import cn.enaium.fabricpvz.portable.lawn.GridItemType
import cn.enaium.fabricpvz.portable.lawn.GridSquareType
import cn.enaium.fabricpvz.portable.lawn.LawnMower
import cn.enaium.fabricpvz.portable.lawn.Main
import cn.enaium.fabricpvz.portable.lawn.Plant
import cn.enaium.fabricpvz.portable.lawn.PlantDefinition
import cn.enaium.fabricpvz.portable.lawn.PlantOnBungeeState
import cn.enaium.fabricpvz.portable.lawn.PlantRowType
import cn.enaium.fabricpvz.portable.lawn.PlantState
import cn.enaium.fabricpvz.portable.lawn.PlantWeapon
import cn.enaium.fabricpvz.portable.lawn.Projectile
import cn.enaium.fabricpvz.portable.lawn.ProjectileMotion
import cn.enaium.fabricpvz.portable.lawn.ProjectileType
import cn.enaium.fabricpvz.portable.lawn.ReanimationID
import cn.enaium.fabricpvz.portable.lawn.ReanimationType
import cn.enaium.fabricpvz.portable.lawn.Resources
import cn.enaium.fabricpvz.portable.lawn.SeedType
import cn.enaium.fabricpvz.portable.lawn.Zombie
import cn.enaium.fabricpvz.portable.lawn.ZombieAttackType
import cn.enaium.fabricpvz.portable.lawn.ZombieBehaviourA
import cn.enaium.fabricpvz.portable.lawn.ZombieDefinition
import cn.enaium.fabricpvz.portable.lawn.ZombiePhase
import cn.enaium.fabricpvz.portable.lawn.ZombieType
import cn.enaium.fabricpvz.portable.lawn.widget.SeedChooserScreen
import cn.enaium.fabricpvz.portable.lawn.LawnApp
import cn.enaium.fabricpvz.portable.lawn.system.PlayerInfo
import cn.enaium.fabricpvz.portable.pvzp.ResourceLocator
import cn.enaium.fabricpvz.portable.pvzp.reanim.Attachment
import cn.enaium.fabricpvz.portable.pvzp.reanim.Reanimation
import cn.enaium.fabricpvz.portable.pvzp.reanim.ReanimatorTrack
import cn.enaium.fabricpvz.portable.sexy.Common
import cn.enaium.fabricpvz.portable.sexy.Rect
import cn.enaium.fabricpvz.portable.sexy.graphics.Graphics
import cn.enaium.fabricpvz.portable.sexy.graphics.Image
import cn.enaium.fabricpvz.portable.sexy.graphics.MemoryImage
import cn.enaium.fabricpvz.portable.sexy.pak.PakFileHandle
import cn.enaium.fabricpvz.portable.sexy.pak.PakInterface
import net.fabricmc.loader.api.FabricLoader

/**
 * The game entry point of the 95 Edition end-to-end run: Fabric Loader starts this class instead of
 * the real {@code Main} ({@code -Dpvz.game.mainClass}), so everything below runs inside the loader's
 * class loader with the mods of a real {@code mods/} directory already resolved, their entry points
 * run and their Mixin configurations applied.
 *
 * <p>Because the loader defines its own copies of the game, the API and the mod classes, this class
 * cannot report to the test through memory: every observation is appended to the file named by
 * {@code -Dpvz.test.report} and the JUnit side asserts on it.
 *
 * <p>The point of the run is the <b>rules</b>, not the load. The harness therefore boots the real game
 * against a retail installation (no assets, no run: the test side skips), spawns the zombies the mod
 * rebalanced through the board's own spawner ({@code Board::AddZombieInRow}) and reports what the
 * zombie objects carry afterwards - type, body/helm/shield health, phase counter and the body art -
 * next to a zombie type the mod does not touch, which must keep the game's own numbers.
 *
 * <p>The second point is what the run does <em>not</em> change: the 95 Edition contributes no
 * resources, so the harness reports that {@code api-resource} has nothing registered and that the
 * engine's own {@code properties/LawnStrings.txt} read is the retail {@code main.pak} member - the
 * test side compares its SHA-256 with the member it reads itself.
 */
class Edition95GameMain private constructor() {

    companion object {

        /** System property naming the retail resource root (the directory with {@code main.pak}). */
        private const val RETAIL_RESOURCES_PROPERTY = "pvz.test.resources"

        /** The retail resource the mod deliberately does not override. */
        private const val LAWN_STRINGS = "properties/LawnStrings.txt"

        /** The retail resource of a second, unrelated kind, as a control for the override report. */
        private const val ALMANAC = "images/Almanac.png"

        /** The lane the zombies are spawned in; any lane of the level's board does. */
        private const val SPAWN_ROW = 2

        /** Lanes the plant rules are observed in: away from {@link #SPAWN_ROW} and its eight zombies. */
        private const val STAR_ROW = 0
        private const val PEA_ROW = 1
        private const val SPIKE_ROW = 3

        /** How far ahead of a shot its target is put; the game's own step covers it in ~18 frames. */
        private const val SHOT_TARGET_AHEAD = 60

        /** A shot the game carries to the right / to the left, i.e. which side of it the target goes to. */
        private const val FLIES_RIGHT = 1
        private const val FLIES_LEFT = -1

        /** The lane the blast-damage observation uses: below the zombies and the plants of the other phases. */
        private const val BLAST_ROW = 4

        /** Where the blast-damage observation parks its gargantuar: on the lawn, in the middle of the board. */
        private const val BLAST_X = 700

        /** How many raining-seeds draws the interval is sampled over (the game draws one per call). */
        private const val RAIN_SEED_DRAWS = 200

        /** How many conveyor-belt draws the seen-seed set is sampled over. */
        private const val CONVEYOR_DRAWS = 400

        private val REPORT = StringBuilder()
        private var sError = ""

        @JvmStatic
        fun main(args: Array<String>) {
            val reportPath = Path.of(requiredProperty("pvz.test.report"))

            try {
                loaderPhase()

                rulesPhase()
                flush(reportPath)

                plantRulesPhase()
                flush(reportPath)

                waveRulesPhase()
                flush(reportPath)

                spawnPhase()
                flush(reportPath)

                resourcePhase()
            } catch (aThrowable: Throwable) {
                sError = aThrowable.toString()
                aThrowable.printStackTrace()
            } finally {
                report("harnessError", if (sError.isEmpty()) "none" else sError)
                flush(reportPath)
            }

            println("[pvz-mod-95-e2e] report written to " + reportPath)
            println(REPORT)

            if (!sError.isEmpty()) {
                throw IllegalStateException("the 95 Edition harness failed: " + sError)
            }
        }

        // ---------------------------------------------------------------- phase 1

        /** What the loader resolved, and that the API entry point ran before this class. */
        private fun loaderPhase() {
            val aLoader = FabricLoader.getInstance()
            report("loaderVersion", aLoader.getModContainer("fabricloader")
                .map { container -> container.metadata.version.friendlyString }
                .orElse("<missing>"))
            report("gameVersion", aLoader.rawGameVersion)
            report("environment", aLoader.environmentType.name.lowercase(Locale.ROOT))
            report("development", aLoader.isDevelopmentEnvironment)

            val aModIds = TreeSet<String>()
            for (aContainer in aLoader.allMods) {
                aModIds.add(aContainer.metadata.id)
            }
            report("modsLoaded", aModIds.joinToString(","))
            report("modCount", aModIds.size)
            report("edition95Loaded", aLoader.isModLoaded(Edition95Mod.MOD_ID))
            report("zombieModuleLoaded", aLoader.isModLoaded("api-zombie"))
            report("resourceModuleLoaded", aLoader.isModLoaded("api-resource"))

            report("apiInitialized", PvzFabricApi.isInitialized())
            report("apiVersion", PvzFabricApi.getVersion())
            // The same version as the loader's own metadata, i.e. the API the run actually loaded.
            report("apiVersionByLoader", aLoader.getModContainer("fabric-api")
                .map { container -> container.metadata.version.friendlyString }
                .orElse("<missing>"))
            report("gameVersionByLoader", aLoader.getModContainer("pvz-portable")
                .map { container -> container.metadata.version.friendlyString }
                .orElse("<missing>"))
        }

        // ---------------------------------------------------------------- phase 2

        /** What the mod registered with {@code api-zombie}: the 95 rules, as the API sees them. */
        private fun rulesPhase() {
            val aRegistered = ZombieOverrides.registered()
            report("overrideCount", aRegistered.size)

            val aTypes = StringBuilder()
            for (aType in aRegistered.keys) {
                if (aTypes.isNotEmpty()) {
                    aTypes.append(',')
                }
                aTypes.append(aType)
            }
            report("overriddenZombieTypes", aTypes)

            for ((aKey, aValue) in aRegistered) {
                report("override_" + aKey, aValue)
            }
        }

        // ---------------------------------------------------------------- phase 2b

        /**
         * What the mod registered with {@code api-plant} and {@code ProjectileOverrides}, read back the way
         * the game reads it: every plant rule through {@code Plant.getPlantDefinition(seed)} (the table row
         * the seed packet, the recharge and the firing cadence come from) and every projectile rule through
         * the projectile's own definition row.
         *
         * <p>Nothing is booted here on purpose: those are static tables, so this phase runs before the game
         * main class even exists - the same point in time the mod entry point writes them at, and the
         * ordering the definition overrides are documented under.
         */
        private fun plantRulesPhase() {
            val aPlants = PlantOverrides.registered()
            report("plantOverrideCount", aPlants.size)
            report("plantOverriddenSeeds", joinedKeys(aPlants.keys))
            for ((aKey, aValue) in aPlants) {
                report("plantOverride_" + aKey, aValue)
            }

            val aProjectiles = ProjectileOverrides.registered()
            report("projectileOverrideCount", aProjectiles.size)
            report("projectileOverriddenTypes", joinedKeys(aProjectiles.keys))
            for ((aKey, aValue) in aProjectiles) {
                report("projectileOverride_" + aKey, aValue)
            }

            reportPlantDefinition("sunflower", SeedType.SEED_SUNFLOWER)
            reportPlantDefinition("sunshroom", SeedType.SEED_SUNSHROOM)
            reportPlantDefinition("potatomine", SeedType.SEED_POTATOMINE)
            reportPlantDefinition("gravebuster", SeedType.SEED_GRAVEBUSTER)
            reportPlantDefinition("hypnoshroom", SeedType.SEED_HYPNOSHROOM)
            reportPlantDefinition("tallnut", SeedType.SEED_TALLNUT)
            reportPlantDefinition("seashroom", SeedType.SEED_SEASHROOM)
            reportPlantDefinition("cactus", SeedType.SEED_CACTUS)
            reportPlantDefinition("blover", SeedType.SEED_BLOVER)
            reportPlantDefinition("starfruit", SeedType.SEED_STARFRUIT)
            reportPlantDefinition("marigold", SeedType.SEED_MARIGOLD)
            reportPlantDefinition("gatlingpea", SeedType.SEED_GATLINGPEA)
            reportPlantDefinition("cattail", SeedType.SEED_CATTAIL)
            reportPlantDefinition("explodeonut", SeedType.SEED_EXPLODE_O_NUT)

            // The controls: a retail plant the mod leaves alone entirely, and - for the potato mine, which
            // the mod changes in one field only - the fields the rule does not touch, which must keep the
            // retail values next to the one it does.
            reportPlantDefinition("control_peashooter", SeedType.SEED_PEASHOOTER)
            reportPlantDefinition("control_potatomine", SeedType.SEED_POTATOMINE)
            report("control_potatomine_refresh",
                Plant.getPlantDefinition(SeedType.SEED_POTATOMINE).mRefreshTime)
            report("control_sunflower_cost", Plant.getPlantDefinition(SeedType.SEED_SUNFLOWER).mSeedCost)

            report("starpult_starDamageDefinition",
                Projectile.gProjectileDefinition[ProjectileType.PROJECTILE_STAR].mDamage)
            report("cactus_spikeDamageDefinition",
                Projectile.gProjectileDefinition[ProjectileType.PROJECTILE_SPIKE].mDamage)
            report("control_peaDamageDefinition",
                Projectile.gProjectileDefinition[ProjectileType.PROJECTILE_PEA].mDamage)
        }

        /** One plant rule target's definition row, as the game's own accessor answers it. */
        private fun reportPlantDefinition(theLabel: String, theSeedType: Int) {
            val aDefinition: PlantDefinition? = Plant.getPlantDefinition(theSeedType)
            report(theLabel + "_present", aDefinition != null)
            if (aDefinition == null) {
                return
            }

            report(theLabel + "_cost", aDefinition.mSeedCost)
            report(theLabel + "_refreshTime", aDefinition.mRefreshTime)
            report(theLabel + "_launchRate", aDefinition.mLaunchRate)
        }

        private fun joinedKeys(theKeys: Collection<Int>): String {
            val aText = StringBuilder()
            for (aKey in theKeys) {
                if (aText.isNotEmpty()) {
                    aText.append(',')
                }
                aText.append(aKey)
            }
            return aText.toString()
        }

        // ---------------------------------------------------------------- phase 2c

        /**
         * The batch-three rules that are pure table reads, i.e. everything about "which zombie may appear
         * where" and the global numbers: the registration the mod left on the API, the five cells it wrote
         * into {@code Challenge.gZombieAllowedLevels} (read both raw and through the game's own
         * {@code Board::CanZombieSpawnOnLevel}), the relaxed spawn vetoes, and the global values.
         *
         * <p>No game is booted here on purpose: these are static tables and static methods, the same point
         * in time the mod entry point writes them at.
         */
        private fun waveRulesPhase() {
            val aRegistered = BoardOverrides.registered()
            report("boardRuleCount", aRegistered.size)
            report("boardRuleKeys", aRegistered.keys.joinToString(","))
            report("boardRule_relaxed", aRegistered["relaxZombieSpawnRules"])

            report("beghouled_winScore", BoardOverrides.beghouledWinningScore())
            report("beghouled_winScoreDefault", BoardOverrides.DEFAULT_BEGHOULED_WINNING_SCORE)
            report("killAllZombies_damage", BoardOverrides.killAllZombiesDamage())
            report("rainSeed_min", BoardOverrides.rainSeedIntervalMin())
            report("rainSeed_max", BoardOverrides.rainSeedIntervalMax())
            report("sunMoney_cap", BoardOverrides.sunMoneyCap())
            report("sunSpawning_mode", BoardOverrides.sunSpawningMode())
            report("sunSpawning_coin", BoardOverrides.sunSpawningCoinType())
            report("graveStones_forced", BoardOverrides.stageHasGraveStonesOverride())
            report("graveBuster_inverted", BoardOverrides.graveBusterNeedsGravesInverted())
            report("sun_monetaryValues", joinedSunValues())

            // The fourth batch's knobs, as the mod registered them (this phase runs before the game's main
            // class exists, i.e. at the point in time the mod entry point writes them at).
            report("skyDrop_coin", BoardOverrides.skyDropCoin())
            report("dropLoot_sunBranch", BoardOverrides.dropLootSunBranch())
            report("dropLoot_coins", BoardOverrides.dropLootCoin(0).toString() + "," + BoardOverrides.dropLootCoin(1) + ","
                + BoardOverrides.dropLootCoin(2))
            report("lastStand_initialSun", BoardOverrides.lastStandInitialSun())
            report("coin_usableSeedType", BoardOverrides.coinUsableSeedType())
            report("numWaves_registered", joinedNumWaves())

            // The five table cells, raw ...
            reportTableCell("poleVaulter31", ZombieType.ZOMBIE_POLEVAULTER, 31)
            reportTableCell("poleVaulter32", ZombieType.ZOMBIE_POLEVAULTER, 32)
            reportTableCell("newspaper10", ZombieType.ZOMBIE_NEWSPAPER, 10)
            reportTableCell("newspaper11", ZombieType.ZOMBIE_NEWSPAPER, 11)
            reportTableCell("newspaper12", ZombieType.ZOMBIE_NEWSPAPER, 12)
            reportTableCell("newspaper16", ZombieType.ZOMBIE_NEWSPAPER, 16)
            reportTableCell("football31", ZombieType.ZOMBIE_FOOTBALL, 31)
            reportTableCell("football32", ZombieType.ZOMBIE_FOOTBALL, 32)

            // ... and through the game's own "may this zombie appear on this level", which is the read
            // point the wave picker uses.
            report("spawn_poleVaulterLevel32", Board.canZombieSpawnOnLevel(ZombieType.ZOMBIE_POLEVAULTER, 32))
            report("spawn_poleVaulterLevel31", Board.canZombieSpawnOnLevel(ZombieType.ZOMBIE_POLEVAULTER, 31))
            report("spawn_newspaperLevel11", Board.canZombieSpawnOnLevel(ZombieType.ZOMBIE_NEWSPAPER, 11))
            report("spawn_newspaperLevel12", Board.canZombieSpawnOnLevel(ZombieType.ZOMBIE_NEWSPAPER, 12))
            report("spawn_newspaperLevel16", Board.canZombieSpawnOnLevel(ZombieType.ZOMBIE_NEWSPAPER, 16))
            report("spawn_footballLevel32", Board.canZombieSpawnOnLevel(ZombieType.ZOMBIE_FOOTBALL, 32))
            report("spawn_footballLevel31", Board.canZombieSpawnOnLevel(ZombieType.ZOMBIE_FOOTBALL, 31))

            // The two dropped vetoes. The flag zombie is the cleanest witness: its definition row weighs 0
            // - i.e. the retail rule refuses it on every level - while the allowed-levels table says yes
            // everywhere.
            val aFlag = Zombie.getZombieDefinition(ZombieType.ZOMBIE_FLAG)
            report("flag_pickWeight", aFlag.mPickWeight)
            report("flag_startingLevel", aFlag.mStartingLevel)
            report("spawn_flagLevel1", Board.canZombieSpawnOnLevel(ZombieType.ZOMBIE_FLAG, 1))

            // The control: a zombie whose table row says no stays no even with the vetoes dropped.
            val aGargantuar = Zombie.getZombieDefinition(ZombieType.ZOMBIE_GARGANTUAR)
            report("gargantuar_startingLevel", aGargantuar.mStartingLevel)
            report("spawn_gargantuarLevel1", Board.canZombieSpawnOnLevel(ZombieType.ZOMBIE_GARGANTUAR, 1))
        }

        /** One allowed-levels cell, as the table itself holds it. */
        private fun reportTableCell(theLabel: String, theZombieType: Int, theLevel: Int) {
            report("table_" + theLabel,
                Challenge.gZombieAllowedLevels[theZombieType].mAllowedOnLevel[theLevel - 1])
        }

        /** The three sun values the override answers, keyed by coin type. */
        private fun joinedSunValues(): String {
            return CoinType.COIN_SUN.toString() + "=" + BoardOverrides.sunValue(CoinType.COIN_SUN) + ',' +
                CoinType.COIN_SMALLSUN + "=" + BoardOverrides.sunValue(CoinType.COIN_SMALLSUN) + ',' +
                CoinType.COIN_LARGESUN + "=" + BoardOverrides.sunValue(CoinType.COIN_LARGESUN) + ',' +
                CoinType.COIN_GOLD + "=" + BoardOverrides.sunValue(CoinType.COIN_GOLD)
        }

        // ---------------------------------------------------------------- phase 3

        /**
         * The rules, observed on zombies that were really born: the board's own spawner creates one zombie
         * of every rebalanced type (and one the mod leaves alone), and what the zombie objects carry is
         * reported. The plant and projectile rules are observed on the same board, through the game's own
         * planter and firing paths: see {@link #plantPhase(Board)}.
         */
        private fun spawnPhase() {
            val aRetailRoot = retailResourceRoot()
            report("assetsPresent", aRetailRoot != null)
            if (aRetailRoot == null) {
                report("gameBooted", false)
                return
            }

            val aSaveDir = Files.createDirectories(Path.of(requiredProperty("pvz.gameDir")).resolve("userdata"))

            // The entry point installs the resource extractor (upstream main.cpp); the game's own tests do
            // the same before constructing the app.
            Main.installHooks()

            val aApp = LawnApp()
            LawnApp.gLawnApp = aApp

            try {
                aApp.setArgs(arrayOf(
                    "pvz-portable",
                    "-resdir", aRetailRoot.toString(),
                    "-savedir", aSaveDir.toString()
                ))
                aApp.init()

                // start() would run the whole blocking main loop; drive its two pieces by hand instead.
                aApp.startLoadingThread()
                val aDeadline = System.nanoTime() + 180_000_000_000L
                while (!aApp.mLoadingThreadCompleted.get() && System.nanoTime() < aDeadline) {
                    aApp.updateFrames()
                    Thread.sleep(2)
                }
                report("assetsLoaded", aApp.mLoadingThreadCompleted.get())
                if (!aApp.mLoadingThreadCompleted.get()) {
                    report("gameBooted", false)
                    return
                }

                val aProfile: PlayerInfo = aApp.mProfileMgr.addProfile("Edition95")
                aApp.mPlayerInfo = aProfile
                aApp.mGameMode = GameMode.GAMEMODE_ADVENTURE
                aApp.preNewGame(GameMode.GAMEMODE_ADVENTURE, false)
                aApp.startPlaying()

                report("gameBooted", true)
                report("scenePlaying", aApp.mGameScene == GameScenes.SCENE_PLAYING)
                report("scene", aApp.mGameScene)

                val aBoard = aApp.mBoard
                report("boardPresent", aBoard != null)
                if (aBoard == null) {
                    return
                }
                report("boardLevel", aBoard.mLevel)
                report("boardWaves", aBoard.mNumWaves)

                spawnZombie(aBoard, "normal", ZombieType.ZOMBIE_NORMAL)
                spawnZombie(aBoard, "flag", ZombieType.ZOMBIE_FLAG)
                spawnZombie(aBoard, "pail", ZombieType.ZOMBIE_PAIL)
                spawnZombie(aBoard, "door", ZombieType.ZOMBIE_DOOR)
                spawnZombie(aBoard, "newspaper", ZombieType.ZOMBIE_NEWSPAPER)
                spawnZombie(aBoard, "football", ZombieType.ZOMBIE_FOOTBALL)
                spawnZombie(aBoard, "dancer", ZombieType.ZOMBIE_DANCER)
                spawnZombie(aBoard, "bungee", ZombieType.ZOMBIE_BUNGEE)

                plantPhase(aBoard)
                boardRulesPhase(aBoard)
                additionsPhase(aBoard)
                behaviourPhase(aBoard)
                torchwoodAndSpawnPhase(aBoard)
                gravePhase(aBoard)
            } finally {
                aApp.shutdown()
                LawnApp.gLawnApp = null
            }
        }

        /**
         * Spawns one zombie of the given type through {@code Board::AddZombieInRow} - the path the game's
         * own wave spawner uses - and reports everything the rebalancing rules touch. The type asked for is
         * reported too, so a type replacement shows up as "asked for 6, got 4".
         */
        private fun spawnZombie(theBoard: Board, theLabel: String, theZombieType: Int) {
            report(theLabel + "_askedFor", theZombieType)

            val aZombie = theBoard.addZombieInRow(theZombieType, SPAWN_ROW, Zombie.ZOMBIE_WAVE_UI)
            report(theLabel + "_spawned", aZombie != null)
            if (aZombie == null) {
                return
            }

            report(theLabel + "_type", aZombie.mZombieType)
            report(theLabel + "_bodyHealth", aZombie.mBodyHealth)
            report(theLabel + "_bodyMaxHealth", aZombie.mBodyMaxHealth)
            report(theLabel + "_helmType", aZombie.mHelmType)
            report(theLabel + "_helmHealth", aZombie.mHelmHealth)
            report(theLabel + "_helmMaxHealth", aZombie.mHelmMaxHealth)
            report(theLabel + "_shieldType", aZombie.mShieldType)
            report(theLabel + "_shieldHealth", aZombie.mShieldHealth)
            report(theLabel + "_shieldMaxHealth", aZombie.mShieldMaxHealth)
            report(theLabel + "_phaseCounter", aZombie.mPhaseCounter)
            report(theLabel + "_phase", aZombie.mZombiePhase)
            report(theLabel + "_reanimLoaded", aZombie.mBodyReanimID != ReanimationID.REANIMATIONID_NULL)

            // The game keeps no record of the animation it was told to play, so the body art is read the
            // way it is drawn: the tracks in the body reanimation's normal render group. "The body plays
            // anim_bucket" therefore shows up as "the anim_bucket* tracks are visible". -1 means there was
            // no body reanimation to read.
            report(theLabel + "_bucketTracksVisible", visibleTracksWithPrefix(aZombie, "anim_bucket"))
        }

        /** How many tracks whose name starts with the prefix are in the body reanimation's normal group. */
        private fun visibleTracksWithPrefix(theZombie: Zombie, thePrefix: String): Int {
            if (theZombie.mApp == null || theZombie.mBodyReanimID == ReanimationID.REANIMATIONID_NULL) {
                return -1
            }

            val aBodyReanim = theZombie.mApp.reanimationTryToGet(theZombie.mBodyReanimID)
            if (aBodyReanim == null || aBodyReanim.mDefinition == null || aBodyReanim.mDefinition.mTracks == null) {
                return -1
            }

            val aTrackCount = minOf(aBodyReanim.mDefinition.mTracks.mArrayCount, aBodyReanim.mTrackInstances.size)
            var aVisible = 0
            for (i in 0 until aTrackCount) {
                val aTrack = aBodyReanim.mDefinition.mTracks.mArrayData[i] as ReanimatorTrack
                if (aTrack.mName.regionMatches(0, thePrefix, 0, thePrefix.length, true)
                    && aBodyReanim.mTrackInstances[i].mRenderGroup == Reanimation.RENDER_GROUP_NORMAL
                ) {
                    aVisible++
                }
            }
            return aVisible
        }

        // ---------------------------------------------------------------- phase 3b

        /**
         * The plant and projectile rules, observed on the same real board and through the game's own
         * paths: the board's planter ({@code Board::AddPlant} -> {@code Plant::PlantInitialize}) really
         * bears the plants, and the game's own {@code Plant::Fire} really fires them - after which the
         * projectile the board now carries is put on a zombie and stepped once through the game's own
         * collision code, so the damage reported is the health the zombie <em>really</em> lost, i.e. the
         * definition row read at impact time, not just the row as written.
         *
         * <p>Lanes: the zombie phase used {@link #SPAWN_ROW} (2) for its eight zombies, so the plants and
         * their targets live in the other lanes - away from them, so a shot can only meet the zombie it was
         * given as a target.
         */
        private fun plantPhase(theBoard: Board) {
            // The spikerock is a spawn number, not a table entry: plant it, then read the pair the game
            // set - next to a plant the mod leaves alone, which must keep the retail 300.
            reportPlant("spikerock", plant(theBoard, SeedType.SEED_SPIKEROCK, 0, STAR_ROW))
            val aPeashooter = plant(theBoard, SeedType.SEED_PEASHOOTER, 0, PEA_ROW)
            reportPlant("control_peashooter", aPeashooter)

            // The starfruit fires five stars; its definition row is the 95 one (20 -> 40). The shot the
            // harness follows is the one leftward star, so its target goes to its left.
            fire(theBoard, "starpult", plant(theBoard, SeedType.SEED_STARFRUIT, 1, STAR_ROW), ProjectileType.PROJECTILE_STAR,
                STAR_ROW, PlantWeapon.WEAPON_PRIMARY, FLIES_LEFT)

            // The cactus fires the spike the 95 rule cuts to 1 damage. Primary is fired with damage range
            // flags 2 (DAMAGES_FLYING - the retail cactus's anti-balloon shot), which cannot hit a plain
            // zombie, so the harness fires the secondary: same projectile type, same definition row, the
            // ground damage range flags.
            fire(theBoard, "cactus", plant(theBoard, SeedType.SEED_CACTUS, 0, SPIKE_ROW), ProjectileType.PROJECTILE_SPIKE,
                SPIKE_ROW, PlantWeapon.WEAPON_SECONDARY, FLIES_RIGHT)

            // The control: a pea the mod does not touch (20), through the same firing and impact path.
            fire(theBoard, "control_pea", aPeashooter, ProjectileType.PROJECTILE_PEA, PEA_ROW,
                PlantWeapon.WEAPON_PRIMARY, FLIES_RIGHT)
        }

        /** Plants one seed type through the board's planter and reports what the plant was born with. */
        private fun plant(theBoard: Board, theSeedType: Int, theGridX: Int, theRow: Int): Plant? {
            val aPlant = theBoard.addPlant(theGridX, theRow, theSeedType, SeedType.SEED_NONE)
            report("plant_" + theSeedType + "_" + theGridX + "_" + theRow + "_planted", aPlant != null)
            return aPlant
        }

        /** What a plant carries right after the board planted it. */
        private fun reportPlant(theLabel: String, thePlant: Plant?) {
            report(theLabel + "_planted", thePlant != null)
            if (thePlant == null) {
                return
            }

            report(theLabel + "_seedType", thePlant.mSeedType)
            report(theLabel + "_health", thePlant.mPlantHealth)
            report(theLabel + "_maxHealth", thePlant.mPlantMaxHealth)
            report(theLabel + "_dead", thePlant.mDead)
        }

        /**
         * Fires a plant once with the game's own {@code Plant::Fire}, then puts the target zombie into the
         * shot's path and lets the game's own projectile step carry the shot into it.
         *
         * <p>Putting the <em>target</em> in front of the projectile (rather than the projectile on the
         * target) keeps every internal relation the game expects of a shot exactly as firing left it: a
         * pea's or star's shadow offset is one the collision check reads
         * ({@code CheckForCollision}: a pea or star whose shadow is more than 90 pixels below it never
         * collides), and the projectile's column and row keep matching the row it was fired in.
         *
         * @param theDirection {@link #FLIES_RIGHT} or {@link #FLIES_LEFT}: which way the game carries this
         *     shot, i.e. which side of it the target goes to
         */
        private fun fire(theBoard: Board, theLabel: String, thePlant: Plant?, theProjectileType: Int, theRow: Int,
            thePlantWeapon: Int, theDirection: Int) {
            report(theLabel + "_plantPresent", thePlant != null)
            if (thePlant == null) {
                return
            }

            val aZombie = theBoard.addZombieInRow(ZombieType.ZOMBIE_NORMAL, theRow, Zombie.ZOMBIE_WAVE_UI)
            report(theLabel + "_targetSpawned", aZombie != null)
            if (aZombie == null) {
                return
            }

            val aShotsBefore = countProjectiles(theBoard, theProjectileType)
            val aHealthBefore = aZombie.mBodyHealth
            thePlant.fire(aZombie, theRow, thePlantWeapon)
            report(theLabel + "_shots", countProjectiles(theBoard, theProjectileType) - aShotsBefore)

            val aProjectile = pickFiredProjectile(theBoard, theProjectileType, theDirection)
            report(theLabel + "_projectilePresent", aProjectile != null)
            if (aProjectile == null) {
                return
            }

            report(theLabel + "_projectileType", aProjectile.mProjectileType)
            report(theLabel + "_projectileDamage", aProjectile.getProjectileDef().mDamage)
            report(theLabel + "_damageRangeFlags", aProjectile.mDamageRangeFlags)
            report(theLabel + "_motionType", aProjectile.mMotionType)

            // Into the shot's path: a fixed 60 pixels ahead of it, in the lane the shot travels. On board,
            // because {@code ZombieCombat::effectedByDamage} refuses any zombie whose rect starts past the
            // wide board width - the question "can this shot hit this zombie" is asked after the move.
            aZombie.mX = aProjectile.mPosX.toInt() + theDirection * SHOT_TARGET_AHEAD
            report(theLabel + "_targetX", aZombie.mX)
            report(theLabel + "_shotX", aProjectile.mPosX.toInt())
            report(theLabel + "_projectileCanHit", aZombie.effectedByDamage(aProjectile.mDamageRangeFlags))

            var aSteps = 0
            while (!aProjectile.mDead && aZombie.mBodyHealth == aHealthBefore && aSteps < 200) {
                aProjectile.update()
                aSteps++
            }

            report(theLabel + "_impactSteps", aSteps)
            report(theLabel + "_projectileDead", aProjectile.mDead)
            report(theLabel + "_zombieBodyHealth", aZombie.mBodyHealth)
            report(theLabel + "_damageApplied", aHealthBefore - aZombie.mBodyHealth)
        }

        /** How many projectiles of a type the board currently carries. */
        private fun countProjectiles(theBoard: Board, theProjectileType: Int): Int {
            var aCount = 0
            for (aProjectile in theBoard.mProjectiles) {
                if (aProjectile.mProjectileType == theProjectileType) {
                    aCount++
                }
            }
            return aCount
        }

        /**
         * How many projectiles of a type the board carries alive. The board's projectile array is a pool
         * whose freed slots the game hands out again ({@code DataArray.dataArrayAlloc}), so a shot fired
         * into a slot an earlier phase's shot was freed from leaves the raw count where it was: the live
         * count is what tells a new shot from a reused slot.
         */
        private fun countLiveProjectiles(theBoard: Board, theProjectileType: Int): Int {
            var aCount = 0
            for (aProjectile in theBoard.mProjectiles) {
                if (aProjectile.mProjectileType == theProjectileType && !aProjectile.mDead) {
                    aCount++
                }
            }
            return aCount
        }

        /** The types of every projectile the board carries right now, dead ones included, for diagnostics. */
        private fun joinedProjectileTypes(theBoard: Board): String {
            val aTypes = TreeSet<Int>()
            for (aProjectile in theBoard.mProjectiles) {
                aTypes.add(aProjectile.mProjectileType)
            }
            return joinedInts(aTypes)
        }

        /**
         * The shot the firing should be observed on. For a rightward shot that is the first projectile of
         * the type; for the starfruit it is the one leftward star of the five it fires (the only one with
         * no vertical velocity, so it stays in the lane it was fired in and its target can be put in front
         * of it deterministically).
         */
        private fun pickFiredProjectile(theBoard: Board, theProjectileType: Int, theDirection: Int): Projectile? {
            var aFirst: Projectile? = null
            for (aProjectile in theBoard.mProjectiles) {
                if (aProjectile.mProjectileType != theProjectileType || aProjectile.mDead) {
                    continue
                }
                if (aFirst == null) {
                    aFirst = aProjectile
                }
                if (theDirection < 0 && aProjectile.mVelY == 0.0f && aProjectile.mVelX < 0.0f) {
                    return aProjectile
                }
            }
            return aFirst
        }

        // ---------------------------------------------------------------- phase 3c

        /**
         * The batch-three rules that need the real board, observed through the game's own paths:
         *
         * <ol>
         *   <li>every rewritten mode's {@code mZombieAllowed}, filled by the game's own
         *       {@code Challenge::InitZombieWaves} with the mode set on the app;</li>
         *   <li>the sun values, through {@code Board::AddCoin} and {@code Coin::GetSunValue};</li>
         *   <li>the sun cap, through {@code Board::AddSunMoney};</li>
         *   <li>the blast damage, through {@code Board::KillAllZombiesInRadius} on a real gargantuar;</li>
         *   <li>the raining-seeds interval, through {@code ChallengeWeather::UpdateRainingSeeds};</li>
         *   <li>the grave-stone answer and the Grave Buster advice bit, through {@code Board};</li>
         *   <li>the conveyor-belt seed lists, through {@code Challenge::UpdateConveyorBelt};</li>
         *   <li>the sky drop, through {@code Board::UpdateSunSpawning};</li>
         *   <li>the seed-picker veto, through {@code SeedChooserScreen::SeedNotAllowedToPick}.</li>
         * </ol>
         *
         * <p>Every observation sets the mode (and, where the game would stop earlier, the background) for
         * the call and puts it back afterwards: the mode field is what the game itself branches on, so
         * setting it is how the harness reaches the branch, not a way around the game's code.
         */
        private fun boardRulesPhase(theBoard: Board?) {
            report("boardRules_boardPresent", theBoard != null)
            if (theBoard == null) {
                return
            }

            reportAllowedZombies(theBoard)

            report("stageHasGraveStones", theBoard.stageHasGraveStones())
            report("graveBuster_notRecommended", theBoard.seedNotRecommendedForLevel(SeedType.SEED_GRAVEBUSTER))
            report("control_plantern_notRecommended", theBoard.seedNotRecommendedForLevel(SeedType.SEED_PLANTERN))

            reportSunValues(theBoard)
            reportSunMoneyCap(theBoard)
            reportBlastDamage(theBoard)
            reportRainingSeedInterval(theBoard)
            reportConveyorSeeds(theBoard)
            reportSkyDrop(theBoard)
            reportSeedPicker(theBoard)
        }

        /** The allowed zombies of every rewritten mode, from the array the game's own branch fills. */
        private fun reportAllowedZombies(theBoard: Board) {
            reportModeZombies(theBoard, "speedMode", GameMode.GAMEMODE_CHALLENGE_SPEED,
                intArrayOf(ZombieType.ZOMBIE_FOOTBALL, ZombieType.ZOMBIE_JACK_IN_THE_BOX, ZombieType.ZOMBIE_LADDER),
                arrayOf("Football", "Jack", "Ladder"),
                intArrayOf(ZombieType.ZOMBIE_ZAMBONI), arrayOf("Zamboni"))

            reportModeZombies(theBoard, "pogoPartyMode", GameMode.GAMEMODE_CHALLENGE_POGO_PARTY,
                intArrayOf(ZombieType.ZOMBIE_NORMAL, ZombieType.ZOMBIE_TRAFFIC_CONE, ZombieType.ZOMBIE_PAIL,
                    ZombieType.ZOMBIE_NEWSPAPER, ZombieType.ZOMBIE_DOOR, ZombieType.ZOMBIE_FOOTBALL,
                    ZombieType.ZOMBIE_ZAMBONI, ZombieType.ZOMBIE_GARGANTUAR),
                arrayOf("Normal", "Cone", "Pail", "Newspaper", "Door", "Football", "Zamboni", "Gargantuar"),
                intArrayOf(ZombieType.ZOMBIE_LADDER), arrayOf("Ladder"))

            reportModeZombies(theBoard, "sunnyDayMode", GameMode.GAMEMODE_CHALLENGE_SUNNY_DAY,
                intArrayOf(ZombieType.ZOMBIE_DOOR, ZombieType.ZOMBIE_LADDER),
                arrayOf("Door", "Ladder"),
                intArrayOf(ZombieType.ZOMBIE_GARGANTUAR), arrayOf("Gargantuar"))

            reportModeZombies(theBoard, "beghouledMode", GameMode.GAMEMODE_CHALLENGE_BEGHOULED,
                intArrayOf(ZombieType.ZOMBIE_POLEVAULTER, ZombieType.ZOMBIE_TRAFFIC_CONE, ZombieType.ZOMBIE_PAIL,
                    ZombieType.ZOMBIE_JACK_IN_THE_BOX),
                arrayOf("PoleVaulter", "Cone", "Pail", "Jack"),
                intArrayOf(ZombieType.ZOMBIE_LADDER), arrayOf("Ladder"))

            reportModeZombies(theBoard, "warAndPeasMode", GameMode.GAMEMODE_CHALLENGE_WAR_AND_PEAS,
                intArrayOf(ZombieType.ZOMBIE_NEWSPAPER, ZombieType.ZOMBIE_JACK_IN_THE_BOX,
                    ZombieType.ZOMBIE_BALLOON, ZombieType.ZOMBIE_POGO, ZombieType.ZOMBIE_GARGANTUAR),
                arrayOf("Newspaper", "Jack", "Balloon", "Pogo", "Gargantuar"),
                intArrayOf(ZombieType.ZOMBIE_NORMAL), arrayOf("Normal"))

            reportModeZombies(theBoard, "iceMode", GameMode.GAMEMODE_CHALLENGE_ICE,
                intArrayOf(ZombieType.ZOMBIE_NORMAL, ZombieType.ZOMBIE_PAIL, ZombieType.ZOMBIE_NEWSPAPER,
                    ZombieType.ZOMBIE_DANCER, ZombieType.ZOMBIE_SNORKEL, ZombieType.ZOMBIE_DOLPHIN_RIDER,
                    ZombieType.ZOMBIE_DIGGER),
                arrayOf("Normal", "Pail", "Newspaper", "Dancer", "Snorkel", "Dolphin", "Digger"),
                intArrayOf(ZombieType.ZOMBIE_FOOTBALL), arrayOf("Football"))

            reportModeZombies(theBoard, "zombiquariumMode", GameMode.GAMEMODE_CHALLENGE_ZOMBIQUARIUM,
                intArrayOf(ZombieType.ZOMBIE_FLAG, ZombieType.ZOMBIE_POLEVAULTER, ZombieType.ZOMBIE_LADDER),
                arrayOf("Flag", "PoleVaulter", "Ladder"),
                intArrayOf(ZombieType.ZOMBIE_GARGANTUAR), arrayOf("Gargantuar"))

            reportModeZombies(theBoard, "beghouledTwistMode", GameMode.GAMEMODE_CHALLENGE_BEGHOULED_TWIST,
                intArrayOf(ZombieType.ZOMBIE_FLAG, ZombieType.ZOMBIE_POLEVAULTER, ZombieType.ZOMBIE_LADDER,
                    ZombieType.ZOMBIE_JACK_IN_THE_BOX),
                arrayOf("Flag", "PoleVaulter", "Ladder", "Jack"),
                intArrayOf(ZombieType.ZOMBIE_GARGANTUAR), arrayOf("Gargantuar"))
        }

        /**
         * Fills {@code mZombieAllowed} for one mode through the game's own {@code Challenge::InitZombieWaves}
         * and reports the asked-about types.
         */
        private fun reportModeZombies(theBoard: Board, theLabel: String, theGameMode: Int, theAllowed: IntArray,
            theAllowedNames: Array<String>, theControls: IntArray, theControlNames: Array<String>) {
            val aSavedMode = theBoard.mApp.mGameMode
            try {
                theBoard.mApp.mGameMode = theGameMode
                Arrays.fill(theBoard.mZombieAllowed, false)
                theBoard.mChallenge.initZombieWaves()

                for (i in theAllowed.indices) {
                    report(theLabel + "_allows" + theAllowedNames[i], theBoard.mZombieAllowed[theAllowed[i]])
                }
                for (i in theControls.indices) {
                    report(theLabel + "_control" + theControlNames[i], theBoard.mZombieAllowed[theControls[i]])
                }
            } finally {
                theBoard.mApp.mGameMode = aSavedMode
            }
        }

        /** The three sun values, read the way the game reads them: off a coin that was really added. */
        private fun reportSunValues(theBoard: Board) {
            reportCoinValue(theBoard, "coinSun", CoinType.COIN_SUN)
            reportCoinValue(theBoard, "coinSmallSun", CoinType.COIN_SMALLSUN)
            reportCoinValue(theBoard, "coinLargeSun", CoinType.COIN_LARGESUN)
            reportCoinValue(theBoard, "control_coinGold", CoinType.COIN_GOLD)
        }

        /** Adds one coin of a type through {@code Board::AddCoin} and reads {@code Coin::GetSunValue}. */
        private fun reportCoinValue(theBoard: Board, theLabel: String, theCoinType: Int) {
            val aCoin = theBoard.addCoin(40, 40, theCoinType, CoinMotion.COIN_MOTION_COIN)
            report(theLabel + "_present", aCoin != null)
            if (aCoin != null) {
                report(theLabel + "_sunValue", aCoin.getSunValue())
            }
        }

        /** What {@code Board::AddSunMoney} clamps to: two lots of a billion must not be cut at 9990. */
        private fun reportSunMoneyCap(theBoard: Board) {
            val aSaved = theBoard.mSunMoney
            try {
                theBoard.mSunMoney = 0
                theBoard.addSunMoney(1_000_000_000)
                report("sunMoney_afterOneBillion", theBoard.mSunMoney)
                theBoard.addSunMoney(1_000_000_000)
                report("sunMoney_afterTwoBillion", theBoard.mSunMoney)
                theBoard.addSunMoney(1_000_000_000)
                report("sunMoney_afterThreeBillion", theBoard.mSunMoney)
            } finally {
                theBoard.mSunMoney = aSaved
            }
        }

        /** What {@code Board::KillAllZombiesInRadius} takes off a gargantuar (3000 body health). */
        private fun reportBlastDamage(theBoard: Board) {
            val aZombie = theBoard.addZombieInRow(ZombieType.ZOMBIE_GARGANTUAR, BLAST_ROW, Zombie.ZOMBIE_WAVE_UI)
            report("blast_gargantuarSpawned", aZombie != null)
            if (aZombie == null) {
                return
            }

            // Spawned zombies walk in from off the wide board; the blast (like any damage check) refuses
            // an off-board zombie, so put this one on the lawn first, the way the firing phase does.
            aZombie.mX = BLAST_X
            report("blast_gargantuarX", aZombie.mX)

            val aHealthBefore = aZombie.mBodyHealth
            report("blast_gargantuarHealthBefore", aHealthBefore)
            val aX = aZombie.getZombieRect().mX + aZombie.getZombieRect().mWidth / 2
            val aY = aZombie.getZombieRect().mY + aZombie.getZombieRect().mHeight / 2
            report("blast_centerX", aX)
            report("blast_canBeHit", aZombie.effectedByDamage(127))

            val aKilled = theBoard.killAllZombiesInRadius(aZombie.mRow, aX, aY, 300, 0, false, 127)
            report("blast_zombiesHit", aKilled)
            report("blast_gargantuarHealthAfter", aZombie.mBodyHealth)
            report("blast_damageApplied", aHealthBefore - aZombie.mBodyHealth)
        }

        /** The pause {@code ChallengeWeather::UpdateRainingSeeds} draws, sampled through the game's own call. */
        private fun reportRainingSeedInterval(theBoard: Board) {
            var aMinimum = Int.MAX_VALUE
            var aMaximum = Int.MIN_VALUE
            var aFirst = -1
            for (i in 0 until RAIN_SEED_DRAWS) {
                theBoard.mChallenge.mChallengeStateCounter = 1
                theBoard.mChallenge.mWeather.updateRainingSeeds()
                val aPause = theBoard.mChallenge.mChallengeStateCounter
                if (aFirst < 0) {
                    aFirst = aPause
                }
                aMinimum = minOf(aMinimum, aPause)
                aMaximum = maxOf(aMaximum, aPause)
            }

            report("rainSeed_firstDraw", aFirst)
            report("rainSeed_minimumSeen", aMinimum)
            report("rainSeed_maximumSeen", aMaximum)
        }

        /**
         * The conveyor-belt seed lists, sampled through the game's own {@code Challenge::UpdateConveyorBelt}:
         * the seen set of {@code mLastConveyorSeedType} over enough draws to have met every weighted entry,
         * plus the packet the belt really got on the last draw.
         */
        private fun reportConveyorSeeds(theBoard: Board) {
            // Portal Combat: peashooter, wall-nut, cherry bomb (the three the rule replaces) and the three
            // replacements; Invisighoul: peashooter, squash and the two replacements.
            reportConveyorSeeds(theBoard, "portalCombat", GameMode.GAMEMODE_CHALLENGE_PORTAL_COMBAT,
                intArrayOf(SeedType.SEED_PEASHOOTER, SeedType.SEED_WALLNUT, SeedType.SEED_CHERRYBOMB,
                    SeedType.SEED_THREEPEATER, SeedType.SEED_EXPLODE_O_NUT, SeedType.SEED_DOOMSHROOM,
                    SeedType.SEED_REPEATER))
            reportConveyorSeeds(theBoard, "invisighoul", GameMode.GAMEMODE_CHALLENGE_INVISIGHOUL,
                intArrayOf(SeedType.SEED_PEASHOOTER, SeedType.SEED_SQUASH, SeedType.SEED_CHERRYBOMB,
                    SeedType.SEED_THREEPEATER, SeedType.SEED_WALLNUT))
        }

        private fun reportConveyorSeeds(theBoard: Board, theLabel: String, theGameMode: Int, theWatched: IntArray) {
            val aSavedMode = theBoard.mApp.mGameMode
            val aSavedPackets = theBoard.mSeedBank.mNumPackets
            val aSeen = TreeSet<Int>()
            var aLastPick = SeedType.SEED_NONE
            var aLastBeltSeed = SeedType.SEED_NONE

            try {
                theBoard.mApp.mGameMode = theGameMode
                theBoard.mSeedBank.mNumPackets = GameConstants.SEEDBANK_MAX
                for (i in 0 until CONVEYOR_DRAWS) {
                    // An empty belt, so every draw really lands on it (the bank refuses a seed when it is full).
                    for (aPacket in 0 until GameConstants.SEEDBANK_MAX) {
                        theBoard.mSeedBank.mSeedPackets[aPacket].mPacketType = SeedType.SEED_NONE
                    }
                    theBoard.mChallenge.mConveyorBeltCounter = 0
                    theBoard.mChallenge.updateConveyorBelt()
                    aSeen.add(theBoard.mChallenge.mLastConveyorSeedType)
                    aLastPick = theBoard.mChallenge.mLastConveyorSeedType
                    aLastBeltSeed = theBoard.mSeedBank.mSeedPackets[0].mPacketType
                }
            } finally {
                theBoard.mApp.mGameMode = aSavedMode
                theBoard.mSeedBank.mNumPackets = aSavedPackets
            }

            val aTypes = StringBuilder()
            for (aType in aSeen) {
                if (aTypes.isNotEmpty()) {
                    aTypes.append(',')
                }
                aTypes.append(aType)
            }
            report(theLabel + "_draws", CONVEYOR_DRAWS)
            report(theLabel + "_seenSeeds", aTypes)
            report(theLabel + "_lastPick", aLastPick)
            report(theLabel + "_lastBeltSeed", aLastBeltSeed)
            for (aSeedType in theWatched) {
                report(theLabel + "_seen_" + aSeedType, if (aSeen.contains(aSeedType)) 1 else 0)
            }
        }

        /**
         * What {@code Board::UpdateSunSpawning} drops: for the mode the mod made the special one it must be
         * the usable seed packet, and for the mode it removed it must be the ordinary sun again.
         *
         * <p>The board is put on a day background for the call because {@code UpdateSunSpawning} returns
         * early on any night/fog stage - and Air Raid is a fog stage in this port, which is exactly why the
         * rule can only be observed by reaching the method's body.
         */
        private fun reportSkyDrop(theBoard: Board) {
            reportSkyDrop(theBoard, "skyDrop_airRaid", GameMode.GAMEMODE_CHALLENGE_AIR_RAID)
            reportSkyDrop(theBoard, "skyDrop_sunnyDay", GameMode.GAMEMODE_CHALLENGE_SUNNY_DAY)
            reportSkyDrop(theBoard, "skyDrop_adventure", GameMode.GAMEMODE_ADVENTURE)
        }

        private fun reportSkyDrop(theBoard: Board, theLabel: String, theGameMode: Int) {
            val aSavedMode = theBoard.mApp.mGameMode
            val aSavedBackground = theBoard.mBackground
            val aSavedCountdown = theBoard.mSunCountDown
            var aNewCoin: Coin? = null

            try {
                theBoard.mApp.mGameMode = theGameMode
                theBoard.mBackground = BackgroundType.BACKGROUND_1_DAY
                theBoard.mSunCountDown = 1

                for (aCoin in theBoard.mCoins) {
                    aCoin.mDead = true
                }
                theBoard.updateSunSpawning()

                for (aCoin in theBoard.mCoins) {
                    if (!aCoin.mDead) {
                        aNewCoin = aCoin
                        break
                    }
                }
                report(theLabel + "_coinDropped", aNewCoin != null)
                if (aNewCoin != null) {
                    report(theLabel + "_coinType", aNewCoin.mType)
                    report(theLabel + "_coinIsSun", aNewCoin.isSun())
                    report(theLabel + "_coinSunValue", aNewCoin.getSunValue())
                }
            } finally {
                theBoard.mApp.mGameMode = aSavedMode
                theBoard.mBackground = aSavedBackground
                theBoard.mSunCountDown = aSavedCountdown
            }
        }

        /**
         * The seed-picker veto, through {@code SeedChooserScreen::SeedNotAllowedToPick} on the app's own
         * chooser: the mod's mode plus seed type, and two controls - the game's own Last Stand rule, which
         * must still hold, and the same seed type in a mode the mod did not touch.
         */
        private fun reportSeedPicker(theBoard: Board) {
            // The game deletes the chooser once the level starts (LawnApp::KillSeedChooserScreen), so the
            // harness builds one the way LawnApp::ShowSeedChooserScreen does - the screen is a plain
            // widget whose constructor reads the app and the board it was opened on.
            var aChooser: SeedChooserScreen? = theBoard.mApp.mSeedChooserScreen
            report("seedPicker_appHadOne", aChooser != null)
            if (aChooser == null) {
                aChooser = SeedChooserScreen()
            }
            report("seedPicker_present", aChooser != null)

            val aSavedMode = theBoard.mApp.mGameMode
            try {
                theBoard.mApp.mGameMode = GameMode.GAMEMODE_CHALLENGE_AIR_RAID
                report("seedPicker_airRaidMarigold",
                    aChooser!!.seedNotAllowedToPick(SeedType.SEED_MARIGOLD))
                report("seedPicker_airRaidPeashooter",
                    aChooser!!.seedNotAllowedToPick(SeedType.SEED_PEASHOOTER))

                theBoard.mApp.mGameMode = GameMode.GAMEMODE_ADVENTURE
                report("seedPicker_adventureMarigold",
                    aChooser!!.seedNotAllowedToPick(SeedType.SEED_MARIGOLD))

                theBoard.mApp.mGameMode = GameMode.GAMEMODE_CHALLENGE_LAST_STAND
                report("seedPicker_lastStandSunflower",
                    aChooser!!.seedNotAllowedToPick(SeedType.SEED_SUNFLOWER))
            } finally {
                theBoard.mApp.mGameMode = aSavedMode
            }
        }

        // ---------------------------------------------------------------- phase 3d

        /** The lane the fourth batch's plants and its blast victims live in - the last lawn row. */
        private const val ADDITIONS_ROW = 4

        /** A victim placed with its rectangle centred on the blast, rather than a given distance away. */
        private const val CENTER_ON_BLAST = Int.MIN_VALUE

        /** How many death-loot draws the three coins are observed over (the branch's own roll decides). */
        private const val DROP_LOOT_DRAWS = 400

        /** How many production draws the marigold's coins are observed over. */
        private const val MARIGOLD_DRAWS = 200

        /**
         * The fourth batch's values, observed through the game's own paths on the same real board:
         *
         * <ol>
         *   <li>the potato mine's arming countdown and its blast, by firing the mine's own
         *       {@code Plant::DoSpecial} at zombies the game places around it - one in its row, one a row
         *       away, one two rows away and one beyond the radius, so the three arguments the patch moves
         *       (radius, row range, burn) each have a victim that tells them apart;</li>
         *   <li>the sun-shroom's growth countdown, off the plant the board's planter bore;</li>
         *   <li>the chomper's digesting pause and its third bite type, by stepping the game's own
         *       {@code Plant::UpdateChomper} with a target in front of it;</li>
         *   <li>what one hit takes off a spikerock and where its spikes crack, through
         *       {@code Plant::SpikeRockTakeDamage} and the tracks it hides;</li>
         *   <li>the marigold's coins, sampled through {@code Plant::UpdateProductionPlant} in an adventure
         *       level and in the Big Time level whose extra coin the patch moves, next to a sunflower that
         *       must keep dropping ordinary sun;</li>
         *   <li>the death loot, through {@code Board::DropLootPiece} itself;</li>
         *   <li>the wave counts, through the game's own {@code Board::InitZombieWaves} and
         *       {@code Board::GetNumWavesPerSurvivalStage};</li>
         *   <li>a coin's usable seed type, off a coin the board added;</li>
         *   <li>the Last Stand starting sun, through {@code Board::InitLevel} itself - the one observation
         *       that re-initialises the level, so it runs last.</li>
         * </ol>
         */
        private fun additionsPhase(theBoard: Board?) {
            report("additions_boardPresent", theBoard != null)
            if (theBoard == null) {
                return
            }

            reportPotatoMine(theBoard)
            reportSunShroom(theBoard)
            reportChomper(theBoard)
            reportSpikeRock(theBoard)
            reportMarigold(theBoard)
            reportDropLoot(theBoard)
            reportWaveCounts(theBoard)
            reportCoinUsableSeedType(theBoard)
            reportLastStandInitialSun(theBoard)
            reportStarFlight(theBoard)
        }

        /**
         * The lane every star is fired in. The middle of the board, so the two stars that turn two rows
         * away stay on it: the fan covers {@code STAR_FLIGHT_ROW - 2} .. {@code STAR_FLIGHT_ROW + 2}.
         */
        private const val STAR_FLIGHT_ROW = 2

        /** The ticks the flight is given to reach its rows and turn; a row is ~30 ticks at 3.33 a tick. */
        private const val STAR_FLIGHT_TICKS = 400

        /** The speed {@code Plant::starFruitFire} fires every star with, and the speed they turn to. */
        private const val STAR_FLIGHT_SPEED = 3.33f

        /**
         * The starfruit's star (rule B9): the five directions {@code Plant::starFruitFire} fires (cases 0
         * to 4 - back, down, up, and the two forward diagonals), each spawned the way the plant spawns it
         * and stepped by the board's own update, so the rule runs where it runs in a game (the board's
         * per-tick hook). It reports that all five still fly their own heading before their distance is
         * up, and that all five fly straight forward afterwards.
         */
        private fun reportStarFlight(theBoard: Board) {
            clearBoard(theBoard)

            // The five stars are the plant's own: the game's StarFruitFire fires them, so the headings the
            // rule reads are the ones a real starfruit fires - not a copy of them.
            val aStarFruit = plant(theBoard, SeedType.SEED_STARFRUIT, 3, STAR_FLIGHT_ROW)
            report("star_flight_planted", aStarFruit != null)
            if (aStarFruit == null) {
                return
            }

            aStarFruit.starFruitFire()
            var aLeft: Projectile? = null
            var aDown: Projectile? = null
            var aUp: Projectile? = null
            var aRightDown: Projectile? = null
            var aRightUp: Projectile? = null
            for (aProjectile in theBoard.mProjectiles) {
                if (aProjectile.mDead || aProjectile.mProjectileType != ProjectileType.PROJECTILE_STAR) {
                    continue
                }
                if (aProjectile.mVelX < 0.0f) {
                    aLeft = aProjectile
                } else if (aProjectile.mVelX == 0.0f && aProjectile.mVelY > 0.0f) {
                    aDown = aProjectile
                } else if (aProjectile.mVelX == 0.0f) {
                    aUp = aProjectile
                } else if (aProjectile.mVelY > 0.0f) {
                    aRightDown = aProjectile
                } else {
                    aRightUp = aProjectile
                }
            }
            report("star_flight_fired", aLeft != null && aDown != null && aUp != null && aRightDown != null
                && aRightUp != null)
            if (aLeft == null || aDown == null || aUp == null || aRightDown == null || aRightUp == null) {
                return
            }

            report("star_flight_left_heading", aLeft.mVelX.toString() + "/" + aLeft.mVelY)
            report("star_flight_rightDown_heading", aRightDown.mVelX.toString() + "/" + aRightDown.mVelY)
            report("star_flight_rightUp_heading", aRightUp.mVelX.toString() + "/" + aRightUp.mVelY)
            report("star_flight_up_heading", aUp.mVelX.toString() + "/" + aUp.mVelY)
            report("star_flight_down_heading", aDown.mVelX.toString() + "/" + aDown.mVelY)
            report("star_flight_firedRow", aLeft.mRow)

            // The one fired along the row is already in the row it is flying to, so it turns at once; the
            // four with a heading of their own keep it while they cross their rows.
            theBoard.update()
            report("star_flight_left_turnedAtOnce", aLeft.mVelX == STAR_FLIGHT_SPEED && aLeft.mVelY == 0.0f)

            for (i in 0 until 5) {
                theBoard.update()
            }
            report("star_flight_rightDown_keptHeading", aRightDown.mVelY != 0.0f)
            report("star_flight_up_keptHeading", aUp.mVelY != 0.0f)

            for (i in 0 until STAR_FLIGHT_TICKS) {
                theBoard.update()
            }
            report("star_flight_left_row", aLeft.mRow)
            report("star_flight_rightDown_row", aRightDown.mRow)
            report("star_flight_rightUp_row", aRightUp.mRow)
            report("star_flight_up_row", aUp.mRow)
            report("star_flight_down_row", aDown.mRow)
            val aAllForward = aLeft.mVelX == STAR_FLIGHT_SPEED && aLeft.mVelY == 0.0f
                && aRightDown.mVelX == STAR_FLIGHT_SPEED && aRightDown.mVelY == 0.0f
                && aRightUp.mVelX == STAR_FLIGHT_SPEED && aRightUp.mVelY == 0.0f
                && aUp.mVelX == STAR_FLIGHT_SPEED && aUp.mVelY == 0.0f
                && aDown.mVelX == STAR_FLIGHT_SPEED && aDown.mVelY == 0.0f
            report("star_flight_all_forward", aAllForward)
        }

        /**
         * The potato mine: the countdown it is armed with, and what its blast reaches. Six victims, each
         * placed by the geometry the game's own circle/rect test measures, so the three arguments the patch
         * moves can be read apart:
         *
         * <ul>
         *   <li>one at the blast's centre and one a row away - the row range the patch widens to 1 reaches
         *       the second, the game's own 0 does not;</li>
         *   <li>one two rows away - still outside that 1;</li>
         *   <li>one whose rect the game's own radius test misses at 60 (90 pixels from the centre) and
         *       reaches at 115 - the radius the patch widens from 60 to 115;</li>
         *   <li>one 140 pixels out - beyond both radii;</li>
         *   <li>a plant-headed zombie at the centre, which is the case {@code Zombie::ApplyBurn} keeps
         *       alive in its burned phase - i.e. what tells the burn the patch switches on from the damage
         *       the mine used to do.</li>
         * </ul>
         */
        private fun reportPotatoMine(theBoard: Board) {
            val aMine = plant(theBoard, SeedType.SEED_POTATOMINE, 2, ADDITIONS_ROW)
            reportPlant("potatomine", aMine)
            if (aMine == null) {
                return
            }

            report("potatomine_stateCountdown", aMine.mStateCountdown)
            report("potatomine_state", aMine.mState)
            report("potatomine_inPlay", aMine.isInPlay())

            val aBlastX = aMine.mX + aMine.mWidth / 2 - 20
            val aBlastY = aMine.mY + aMine.mHeight / 2
            report("potatomine_blastX", aBlastX)
            report("potatomine_blastY", aBlastY)

            val aSameRow = blastVictim(theBoard, "mineSameRow", ZombieType.ZOMBIE_NORMAL, ADDITIONS_ROW, aBlastX,
                aBlastY, CENTER_ON_BLAST)
            val aNextRow = blastVictim(theBoard, "mineNextRow", ZombieType.ZOMBIE_NORMAL, ADDITIONS_ROW - 1,
                aBlastX, aBlastY, CENTER_ON_BLAST)
            val aTwoRowsAway = blastVictim(theBoard, "mineTwoRowsAway", ZombieType.ZOMBIE_NORMAL,
                ADDITIONS_ROW - 2, aBlastX, aBlastY, CENTER_ON_BLAST)
            val aRadiusEdge = blastVictim(theBoard, "mineRadiusEdge", ZombieType.ZOMBIE_NORMAL, ADDITIONS_ROW,
                aBlastX, aBlastY, 90)
            val aPastTheRadius = blastVictim(theBoard, "minePastRadius", ZombieType.ZOMBIE_NORMAL, ADDITIONS_ROW,
                aBlastX, aBlastY, 140)
            val aBurnVictim = blastVictim(theBoard, "mineBurnVictim", ZombieType.ZOMBIE_PEA_HEAD, ADDITIONS_ROW,
                aBlastX, aBlastY, CENTER_ON_BLAST)

            reportCircleOverlap("mineSameRow", aBlastX, aBlastY, aSameRow)
            reportCircleOverlap("mineNextRow", aBlastX, aBlastY, aNextRow)
            reportCircleOverlap("mineTwoRowsAway", aBlastX, aBlastY, aTwoRowsAway)
            reportCircleOverlap("mineRadiusEdge", aBlastX, aBlastY, aRadiusEdge)
            reportCircleOverlap("minePastRadius", aBlastX, aBlastY, aPastTheRadius)
            reportCircleOverlap("mineBurnVictim", aBlastX, aBlastY, aBurnVictim)

            aMine.doSpecial()
            report("potatomine_deadAfterSpecial", aMine.mDead)

            reportBlastResult("mineSameRow", aSameRow)
            reportBlastResult("mineNextRow", aNextRow)
            reportBlastResult("mineTwoRowsAway", aTwoRowsAway)
            reportBlastResult("mineRadiusEdge", aRadiusEdge)
            reportBlastResult("minePastRadius", aPastTheRadius)
            reportBlastResult("mineBurnVictim", aBurnVictim)
        }

        /**
         * A zombie of a type, put where the game's own spawner made it and then moved so that its rectangle
         * stands {@code theDistanceFromBlast} pixels left of the blast's centre (or is centred on it, for
         * {@link #CENTER_ON_BLAST}), with the rectangle's vertical centre on the blast's - so what the
         * game's own circle/rect test answers is about the distance alone, whatever the zombie's rectangle
         * happens to be.
         *
         * @param theRow       the row the zombie is spawned in
         * @param theBlastRow  the row the blast is centred in, which the row distance is reported against
         */
        private fun blastVictim(theBoard: Board, theLabel: String, theZombieType: Int, theRow: Int, theBlastX: Int,
            theBlastY: Int, theDistanceFromBlast: Int): Zombie? {
            val aZombie = theBoard.addZombieInRow(theZombieType, theRow, Zombie.ZOMBIE_WAVE_UI)
            report(theLabel + "_spawned", aZombie != null)
            if (aZombie == null) {
                return null
            }

            // The rectangle the game measures, as it stands after a move: the offsets and the size are the
            // zombie's own, only the position moves.
            val aRectOffsetX = aZombie.getZombieRect().mX - aZombie.mX
            val aRectOffsetY = aZombie.getZombieRect().mY - aZombie.mY
            val aRect = aZombie.getZombieRect()
            val aDistance = if (theDistanceFromBlast == CENTER_ON_BLAST) -aRect.mWidth / 2 else theDistanceFromBlast
            aZombie.mX = theBlastX - aDistance - aRectOffsetX - aRect.mWidth
            aZombie.mY = theBlastY - aRectOffsetY - aRect.mHeight / 2

            report(theLabel + "_type", aZombie.mZombieType)
            report(theLabel + "_row", aZombie.mRow)
            report(theLabel + "_rowDistanceToBlastRow", ADDITIONS_ROW - aZombie.mRow)
            report(theLabel + "_x", aZombie.mX)
            report(theLabel + "_y", aZombie.mY)
            report(theLabel + "_rectX", aZombie.getZombieRect().mX)
            report(theLabel + "_rectY", aZombie.getZombieRect().mY)
            report(theLabel + "_rectWidth", aRect.mWidth)
            report(theLabel + "_rectHeight", aRect.mHeight)
            report(theLabel + "_distanceFromBlast", theBlastX - (aZombie.getZombieRect().mX + aRect.mWidth))
            report(theLabel + "_healthBefore", aZombie.mBodyHealth)
            report(theLabel + "_phaseBefore", aZombie.mZombiePhase)
            return aZombie
        }

        /**
         * What the game's own circle/rect test says about a victim, for the blast the 95 Edition widens to
         * (115) and for the one the game ships (60): a victim that only the wider circle reaches is the
         * pixel-level evidence for the radius.
         */
        private fun reportCircleOverlap(theLabel: String, theX: Int, theY: Int, theZombie: Zombie?) {
            if (theZombie == null) {
                return
            }
            report(theLabel + "_overlapsRadius60",
                Board.getCircleRectOverlap(theX, theY, 60, theZombie.getZombieRect()))
            report(theLabel + "_overlapsRadius115",
                Board.getCircleRectOverlap(theX, theY, 115, theZombie.getZombieRect()))
        }

        /**
         * What a blast did to one victim. {@code _affected} is "something happened to it at all" (it died,
         * burned or lost health); {@code _phaseAfter} is what tells the burn from a plain damage - the
         * burned phase the ash effect leaves a zombie in.
         */
        private fun reportBlastResult(theLabel: String, theZombie: Zombie?) {
            if (theZombie == null) {
                return
            }
            report(theLabel + "_deadAfter", theZombie.mDead)
            report(theLabel + "_healthAfter", theZombie.mBodyHealth)
            report(theLabel + "_phaseAfter", theZombie.mZombiePhase)
            report(theLabel + "_affected", theZombie.mZombiePhase == ZombiePhase.PHASE_ZOMBIE_BURNED
                || theZombie.mDead || theZombie.mBodyHealth < 270)
            report(theLabel + "_isBurnedPhase", theZombie.mZombiePhase == ZombiePhase.PHASE_ZOMBIE_BURNED)
        }

        /** The sun-shroom's growth countdown, next to a sunflower the batch does not touch. */
        private fun reportSunShroom(theBoard: Board) {
            val aSunShroom = plant(theBoard, SeedType.SEED_SUNSHROOM, 3, ADDITIONS_ROW)
            reportPlant("sunshroom", aSunShroom)
            if (aSunShroom != null) {
                report("sunshroom_stateCountdown", aSunShroom.mStateCountdown)
                report("sunshroom_state", aSunShroom.mState)
            }

            val aSunflower = plant(theBoard, SeedType.SEED_SUNFLOWER, 4, ADDITIONS_ROW)
            if (aSunflower != null) {
                report("control_sunflower_stateCountdown", aSunflower.mStateCountdown)
                report("control_sunflower_state", aSunflower.mState)
            }
        }

        /**
         * The chomper: its digesting pause and its third bite type, both stepped through the game's own
         * {@code Plant::UpdateChomper} - the target in front of it decides between the bite (40 damage, the
         * chomper is ready again) and the swallow (the zombie dies), and the pause is what the digesting
         * transition writes.
         *
         * <p>Three targets: the football zombie the patch moves into the bite set, a gargantuar that was
         * always in it, and a plain zombie that always goes down the swallow - so the one arm the patch
         * moves is read next to the two it does not.
         */
        private fun reportChomper(theBoard: Board) {
            val aChomper = plant(theBoard, SeedType.SEED_CHOMPER, 5, ADDITIONS_ROW)
            reportPlant("chomper", aChomper)
            if (aChomper != null) {
                val aAttackRect = aChomper.getPlantAttackRect(PlantWeapon.WEAPON_PRIMARY)
                report("chomper_attackRectX", aAttackRect.mX)
                report("chomper_attackRectY", aAttackRect.mY)
                report("chomper_attackRectWidth", aAttackRect.mWidth)
                report("chomper_attackRectHeight", aAttackRect.mHeight)

                var aPrevious: Zombie? = reportChomperBite(theBoard, "chomperFootball", aChomper,
                    ZombieType.ZOMBIE_FOOTBALL, null)
                aPrevious = reportChomperBite(theBoard, "chomperGargantuar", aChomper, ZombieType.ZOMBIE_GARGANTUAR,
                    aPrevious)
                reportChomperBite(theBoard, "chomperNormal", aChomper, ZombieType.ZOMBIE_NORMAL, aPrevious)

                // The digesting transition: the game enters it from the "got one" state once the chewing
                // animation has looped - the loop count is what the harness hands it, the pause is the
                // game's own.
                val aBodyReanim = theBoard.mApp.reanimationTryToGet(aChomper.mBodyReanimID)
                report("chomper_reanimPresent", aBodyReanim != null)
                if (aBodyReanim != null) {
                    aChomper.mState = PlantState.STATE_CHOMPER_BITING_GOT_ONE
                    aBodyReanim.mLoopCount = 1
                    aChomper.updateChomper()
                    report("chomper_stateAfterDigest", aChomper.mState)
                    report("chomper_digestCountdown", aChomper.mStateCountdown)
                }
            }

            // The control: a plant whose digesting pause nobody set keeps the game's own 4000.
            report("control_chomperDigestDefault", PlantOverride.DEFAULT_CHOMPER_DIGEST_COUNTDOWN)
        }

        /**
         * One bite: the target in front of the chomper, the game's own step, and what became of both.
         *
         * <p>Each round takes the previous round's target off the board first, because the game's own
         * target search weighs every zombie in the row and would otherwise keep picking the leftmost one -
         * the point here is which of the three types the chomper bites and which it swallows, not how its
         * search ranks the survivors.
         *
         * @param thePrevious the target of the previous round, marked dead before this one is placed
         * @return this round's target, for the next round to clear
         */
        private fun reportChomperBite(theBoard: Board, theLabel: String, theChomper: Plant, theZombieType: Int,
            thePrevious: Zombie?): Zombie? {
            if (thePrevious != null) {
                thePrevious.mDead = true
                report(theLabel + "_previousTargetCleared", true)
            }

            val aZombie = theBoard.addZombieInRow(theZombieType, ADDITIONS_ROW, Zombie.ZOMBIE_WAVE_UI)
            report(theLabel + "_spawned", aZombie != null)
            if (aZombie == null) {
                return null
            }

            // Centre the target's rectangle on the chomper's attack rectangle, so where the target stands
            // is the game's own overlap question and not a pixel guess.
            val aAttackRect = theChomper.getPlantAttackRect(PlantWeapon.WEAPON_PRIMARY)
            val aRect = aZombie.getZombieRect()
            aZombie.mX = aAttackRect.mX + aAttackRect.mWidth / 2 - (aRect.mX - aZombie.mX) - aRect.mWidth / 2
            aZombie.mY = aAttackRect.mY + aAttackRect.mHeight / 2 - (aRect.mY - aZombie.mY) - aRect.mHeight / 2
            report(theLabel + "_rectX", aZombie.getZombieRect().mX)
            report(theLabel + "_rectY", aZombie.getZombieRect().mY)
            report(theLabel + "_targetFound",
                theChomper.findTargetZombie(ADDITIONS_ROW, PlantWeapon.WEAPON_PRIMARY) === aZombie)
            val aHealthBefore = aZombie.mBodyHealth
            val aHelmHealthBefore = aZombie.mHelmHealth

            theChomper.mState = PlantState.STATE_CHOMPER_BITING
            theChomper.mStateCountdown = 0
            theChomper.updateChomper()

            // The bite's 40 damage lands on whatever the zombie protects its body with first, so both
            // pools are read: a bitten zombie lost 40 somewhere, a swallowed one is gone.
            val aBodyDamage = aHealthBefore - aZombie.mBodyHealth
            val aHelmDamage = aHelmHealthBefore - aZombie.mHelmHealth
            report(theLabel + "_chomperState", theChomper.mState)
            report(theLabel + "_zombieDead", aZombie.mDead)
            report(theLabel + "_zombieDamage", aBodyDamage)
            report(theLabel + "_zombieHelmDamage", aHelmDamage)
            report(theLabel + "_bitten", !aZombie.mDead && (aBodyDamage == 40 || aHelmDamage == 40))
            report(theLabel + "_swallowed", aZombie.mDead)
            return aZombie
        }

        /**
         * The spikerock: nine hits of the game's own {@code Plant::SpikeRockTakeDamage}, reporting which
         * hit first hid each of the two spike tracks and which one killed it. The batch scales the health
         * (16200, batch two) and the hit (1800) by the same 36, so the count of hits stays nine and the two
         * crack levels move to a third and two thirds of the health.
         */
        private fun reportSpikeRock(theBoard: Board) {
            val aSpikeRock = plant(theBoard, SeedType.SEED_SPIKEROCK, 6, ADDITIONS_ROW)
            reportPlant("spikeRock", aSpikeRock)
            if (aSpikeRock == null) {
                return
            }

            report("spikeRock_healthBefore", aSpikeRock.mPlantHealth)
            var aHideBigSpike3At = -1
            var aHideBigSpike2At = -1
            var aDiedAt = -1
            for (aHit in 1..12) {
                aSpikeRock.spikeRockTakeDamage()
                if (aHideBigSpike3At < 0 && isTrackHidden(aSpikeRock, "bigspike3")) {
                    aHideBigSpike3At = aHit
                    report("spikeRock_healthAtBigSpike3Hidden", aSpikeRock.mPlantHealth)
                }
                if (aHideBigSpike2At < 0 && isTrackHidden(aSpikeRock, "bigspike2")) {
                    aHideBigSpike2At = aHit
                    report("spikeRock_healthAtBigSpike2Hidden", aSpikeRock.mPlantHealth)
                }
                if (aSpikeRock.mDead) {
                    aDiedAt = aHit
                    break
                }
            }

            report("spikeRock_hitAtBigSpike3Hidden", aHideBigSpike3At)
            report("spikeRock_hitAtBigSpike2Hidden", aHideBigSpike2At)
            report("spikeRock_hitAtDeath", aDiedAt)
            report("spikeRock_healthAfter", aSpikeRock.mPlantHealth)
        }

        /** Whether the plant's body reanimation has the named track in the hidden render group. */
        private fun isTrackHidden(thePlant: Plant, theTrack: String): Boolean {
            if (thePlant.mApp == null || thePlant.mBodyReanimID == ReanimationID.REANIMATIONID_NULL) {
                return false
            }

            val aBodyReanim = thePlant.mApp.reanimationTryToGet(thePlant.mBodyReanimID)
            if (aBodyReanim == null || aBodyReanim.mDefinition == null || aBodyReanim.mDefinition.mTracks == null) {
                return false
            }

            val aTrackCount = minOf(aBodyReanim.mDefinition.mTracks.mArrayCount, aBodyReanim.mTrackInstances.size)
            for (i in 0 until aTrackCount) {
                val aTrackInstance = aBodyReanim.mDefinition.mTracks.mArrayData[i] as ReanimatorTrack
                if (aTrackInstance.mName.equals(theTrack, ignoreCase = true)) {
                    return aBodyReanim.mTrackInstances[i].mRenderGroup == Reanimation.RENDER_GROUP_HIDDEN
                }
            }
            return false
        }

        /**
         * The marigold's coins, sampled through the game's own {@code Plant::UpdateProductionPlant}: in an
         * adventure level every draw pays one coin, which must be a sun coin (the batch replaces the silver
         * and gold pair); in a Big Time level the same branch pays a second one, which is the coin the patch
         * moves from silver to sun. The sunflower next to it must keep paying ordinary sun.
         */
        private fun reportMarigold(theBoard: Board) {
            val aMarigold = plant(theBoard, SeedType.SEED_MARIGOLD, 7, ADDITIONS_ROW)
            reportPlant("marigold", aMarigold)
            if (aMarigold == null) {
                return
            }

            val aSavedMode = theBoard.mApp.mGameMode
            try {
                theBoard.mApp.mGameMode = GameMode.GAMEMODE_ADVENTURE
                val aSeen = TreeSet<Int>()
                var aTriplesOrMore = 0
                var aTwoCoins = 0
                for (i in 0 until MARIGOLD_DRAWS) {
                    val aDrawn = produceCoins(theBoard, aMarigold)
                    for (aCoin in aDrawn) {
                        aSeen.add(aCoin.mType)
                    }
                    if (aDrawn.size > 2) {
                        aTriplesOrMore++
                    }
                    if (aDrawn.size == 2) {
                        aTwoCoins++
                    }
                }
                report("marigold_draws", MARIGOLD_DRAWS)
                report("marigold_seenCoins", joinedInts(aSeen))
                report("marigold_drawsWithTwoCoins", aTwoCoins)
                report("marigold_drawsWithMoreThanTwo", aTriplesOrMore)

                theBoard.mApp.mGameMode = GameMode.GAMEMODE_CHALLENGE_BIG_TIME
                val aBigTimeSeen = TreeSet<Int>()
                val aBigTimeSecondCoins = TreeSet<Int>()
                for (i in 0 until MARIGOLD_DRAWS) {
                    val aDrawn = produceCoins(theBoard, aMarigold)
                    for (aCoin in aDrawn) {
                        aBigTimeSeen.add(aCoin.mType)
                    }
                    if (aDrawn.size >= 2) {
                        aBigTimeSecondCoins.add(aDrawn[1].mType)
                    }
                }
                report("marigold_bigTimeSeenCoins", joinedInts(aBigTimeSeen))
                report("marigold_bigTimeSecondCoins", joinedInts(aBigTimeSecondCoins))

                // The control: a sunflower's own production is not a coin-producing plant's branch at all,
                // and must keep dropping the ordinary sun.
                val aSunflower = plant(theBoard, SeedType.SEED_SUNFLOWER, 8, ADDITIONS_ROW)
                if (aSunflower != null) {
                    val aSunflowerSeen = TreeSet<Int>()
                    for (i in 0 until 20) {
                        for (aCoin in produceCoins(theBoard, aSunflower)) {
                            aSunflowerSeen.add(aCoin.mType)
                        }
                    }
                    report("control_sunflower_seenCoins", joinedInts(aSunflowerSeen))
                }
            } finally {
                theBoard.mApp.mGameMode = aSavedMode
            }
        }

        /** One production of a plant, through the game's own update, as the coins it really added. */
        private fun produceCoins(theBoard: Board, thePlant: Plant): List<Coin> {
            val aBefore = ArrayList<Coin>()
            for (aCoin in theBoard.mCoins) {
                aBefore.add(aCoin)
            }

            thePlant.mLaunchCounter = 1
            thePlant.updateProductionPlant()

            val aDrawn = ArrayList<Coin>()
            for (aCoin in theBoard.mCoins) {
                if (!aBefore.contains(aCoin)) {
                    aDrawn.add(aCoin)
                }
            }
            return aDrawn
        }

        /**
         * The death loot, through the game's own {@code Board::DropLootPiece}: the method's sun branch now
         * runs on this adventure board too (the patch drops the level test), and the three coins it drops
         * are the small, the large and the ordinary sun - in that order, 150 sun in all. The branch's own
         * roll still decides whether a death drops anything, which is what the control counts.
         */
        private fun reportDropLoot(theBoard: Board) {
            val aSavedMoney = theBoard.mSunMoney
            val aSavedWaves = theBoard.mTotalSpawnedWaves
            val aSeen = TreeSet<Int>()
            var aTriple = "<none>"
            var aDropped = 0
            var aTriples = 0

            try {
                // The branch's window is 2500..2800 times the drop factor while the bank is above 500; a
                // bank that high is what an ordinary level has after a few sun, so the window is reached
                // by drawing often enough rather than by moving the roll.
                theBoard.mSunMoney = 600
                theBoard.mTotalSpawnedWaves = 0

                for (i in 0 until DROP_LOOT_DRAWS) {
                    val aBefore = ArrayList<Coin>()
                    for (aCoin in theBoard.mCoins) {
                        aBefore.add(aCoin)
                    }

                    theBoard.dropLootPiece(300, 300, 1)

                    val aDroppedCoins = ArrayList<Coin>()
                    for (aCoin in theBoard.mCoins) {
                        if (!aBefore.contains(aCoin)) {
                            aDroppedCoins.add(aCoin)
                        }
                    }
                    for (aCoin in aDroppedCoins) {
                        aSeen.add(aCoin.mType)
                    }
                    if (aDroppedCoins.size >= 3) {
                        aTriples++
                        if (aTriple == "<none>") {
                            aTriple = joinedCoins(aDroppedCoins.subList(0, 3))
                        }
                    }
                    if (!aDroppedCoins.isEmpty()) {
                        aDropped++
                    }
                }
            } finally {
                theBoard.mSunMoney = aSavedMoney
                theBoard.mTotalSpawnedWaves = aSavedWaves
            }

            report("dropLoot_draws", DROP_LOOT_DRAWS)
            report("dropLoot_drawsThatDropped", aDropped)
            report("dropLoot_drawsWithThreeCoins", aTriples)
            report("dropLoot_firstTriple", aTriple)
            report("dropLoot_seenCoins", joinedInts(aSeen))
        }

        /**
         * The wave counts, through the game's own {@code Board::InitZombieWaves} (which is what fills
         * {@code mNumWaves}) and {@code Board::GetNumWavesPerSurvivalStage}: the three branches the patch
         * moves, the group whose 20 it leaves alone (the control) and the two survival counts.
         */
        private fun reportWaveCounts(theBoard: Board) {
            report("numWaves_table", joinedNumWaves())
            reportWaveCount(theBoard, "whackAZombie", GameMode.GAMEMODE_CHALLENGE_WHACK_A_ZOMBIE)
            reportWaveCount(theBoard, "pogoParty", GameMode.GAMEMODE_CHALLENGE_POGO_PARTY)
            reportWaveCount(theBoard, "beghouled", GameMode.GAMEMODE_CHALLENGE_BEGHOULED)
            // The group that assigns 20 shares the patched immediate with the survival stage and the
            // second-run floor, so all three move together (additions-2's attribution of 0x92fd).
            reportWaveCount(theBoard, "twentyWaveGroup_highGravity", GameMode.GAMEMODE_CHALLENGE_HIGH_GRAVITY)
            reportWaveCount(theBoard, "twentyWaveGroup_wallnutBowling", GameMode.GAMEMODE_CHALLENGE_WALLNUT_BOWLING)
            reportWaveCount(theBoard, "twentyWaveGroup_airRaid", GameMode.GAMEMODE_CHALLENGE_AIR_RAID)
            // The control: an adventure level's own table value is a value the game computes, not one of
            // the constants the replacements name, so 1-1 keeps its four waves.
            reportWaveCount(theBoard, "adventureLevel1OwnValue", GameMode.GAMEMODE_ADVENTURE)

            val aSavedMode = theBoard.mApp.mGameMode
            try {
                theBoard.mApp.mGameMode = GameMode.GAMEMODE_SURVIVAL_NORMAL_STAGE_1
                report("survivalNormal_stageWaves", theBoard.getNumWavesPerSurvivalStage())
                theBoard.mApp.mGameMode = GameMode.GAMEMODE_SURVIVAL_HARD_STAGE_1
                report("survivalHard_stageWaves", theBoard.getNumWavesPerSurvivalStage())
                theBoard.mApp.mGameMode = GameMode.GAMEMODE_CHALLENGE_LAST_STAND
                report("lastStand_stageWaves", theBoard.getNumWavesPerSurvivalStage())
            } finally {
                theBoard.mApp.mGameMode = aSavedMode
            }

            reportSecondRunAdventureFloor(theBoard)
        }

        /**
         * The floor a second-run adventure level is raised to: a level whose own table value is under ten
         * waves gets {@code mNumWaves < 10 ? 20 : mNumWaves + 10}, and 1-1's own four waves put it straight
         * through that arm. The reader is driven twice on the same board - once with the mod's registration
         * (20 -> 40) and once with it taken back - so the pair is the arm itself.
         */
        private fun reportSecondRunAdventureFloor(theBoard: Board) {
            val aSavedMode = theBoard.mApp.mGameMode
            val aSavedLevel = theBoard.mLevel
            val aSavedFinishedAdventure = theBoard.mApp.mPlayerInfo.mFinishedAdventure
            try {
                theBoard.mApp.mGameMode = GameMode.GAMEMODE_ADVENTURE
                theBoard.mLevel = 1
                theBoard.mApp.mPlayerInfo.mFinishedAdventure = 1

                theBoard.initZombieWaves()
                report("waves_secondRunAdventure_ruled", theBoard.mNumWaves)

                // The registration taken back: answer 20 for 20, i.e. the game's own floor again.
                BoardOverrides.numWaves(20, 20)
                theBoard.initZombieWaves()
                report("waves_secondRunAdventure_retail", theBoard.mNumWaves)
            } finally {
                BoardOverrides.numWaves(20, 40)
                theBoard.mApp.mPlayerInfo.mFinishedAdventure = aSavedFinishedAdventure
                theBoard.mApp.mGameMode = aSavedMode
                theBoard.mLevel = aSavedLevel
            }
        }

        /** One mode's wave count, through the game's own initialisation of the level's waves. */
        private fun reportWaveCount(theBoard: Board, theLabel: String, theGameMode: Int) {
            val aSavedMode = theBoard.mApp.mGameMode
            try {
                theBoard.mApp.mGameMode = theGameMode
                theBoard.initZombieWaves()
                report("waves_" + theLabel, theBoard.mNumWaves)
            } finally {
                theBoard.mApp.mGameMode = aSavedMode
            }
        }

        /** A coin's usable seed type - the field {@code Coin::CoinInitialize} sets - next to its siblings. */
        private fun reportCoinUsableSeedType(theBoard: Board) {
            val aCoin = theBoard.addCoin(60, 60, CoinType.COIN_USABLE_SEED_PACKET, CoinMotion.COIN_MOTION_COIN)
            report("coinUsableSeedType_coinPresent", aCoin != null)
            if (aCoin == null) {
                return
            }

            report("coinUsableSeedType_usableSeedType", aCoin.mUsableSeedType)
            report("coinUsableSeedType_usableSeedTypeChecked", BoardOverrides.coinUsableSeedType())
            // The other defaults the same initialiser writes, so the report shows the injection moved the
            // one field and left its siblings alone.
            report("control_coin_pottedPlantSeedType", aCoin.mPottedPlantSpec.mSeedType)
            report("control_coin_needsBouncyArrow", aCoin.mNeedsBouncyArrow)
        }

        /**
         * The Last Stand starting bank, through the game's own {@code Board::InitLevel} - the method the
         * patch moves, and the only observation here that re-initialises the level, which is why it is
         * last.
         */
        private fun reportLastStandInitialSun(theBoard: Board) {
            val aSavedMode = theBoard.mApp.mGameMode
            try {
                theBoard.mApp.mGameMode = GameMode.GAMEMODE_CHALLENGE_LAST_STAND
                theBoard.initLevel()
                report("lastStand_initialSun", theBoard.mSunMoney)

                theBoard.mApp.mGameMode = GameMode.GAMEMODE_ADVENTURE
                theBoard.initLevel()
                report("control_adventure_initialSun", theBoard.mSunMoney)
            } finally {
                theBoard.mApp.mGameMode = aSavedMode
            }
        }

        /** The registered wave-count replacements, retail count -> the count assigned instead. */
        private fun joinedNumWaves(): String {
            val aText = StringBuilder()
            for ((aEntryKey, aEntryValue) in BoardOverrides.waveCounts()) {
                if (aText.isNotEmpty()) {
                    aText.append(',')
                }
                aText.append(aEntryKey).append("->").append(aEntryValue)
            }
            return aText.toString()
        }

        /** A set of ints as a comma-joined list, in iteration order. */
        private fun joinedInts(theValues: Iterable<Int>): String {
            val aText = StringBuilder()
            for (aValue in theValues) {
                if (aText.isNotEmpty()) {
                    aText.append(',')
                }
                aText.append(aValue)
            }
            return aText.toString()
        }

        /** A run of coins as a comma-joined list of their types. */
        private fun joinedCoins(theCoins: List<Coin>): String {
            val aText = StringBuilder()
            for (aCoin in theCoins) {
                if (aText.isNotEmpty()) {
                    aText.append(',')
                }
                aText.append(aCoin.mType)
            }
            return aText.toString()
        }

        // ---------------------------------------------------------------- phase 3e

        /** The lanes the behaviour phase's subjects live in; the earlier phases' are all dead by then. */
        private const val MIND_ROW = 1
        private const val BURN_ROW = 2
        private const val COLD_ROW = 3
        private const val SHIELD_ROW = 0
        private const val SCAREDY_ROW = 4
        private const val EXTRA_FIRE_ROW = 0
        private const val CRUSH_ROW = 3
        private const val EAT_ROW = 4

        /** The lane the fume-row observation plants in (rule B17). */
        private const val FUME_ROW = 3

        /** The lane the chomper-reach observation plants in (rule B16). */
        private const val CHOMPER_REACH_ROW = 0

        /** The lane the squash-reach observation plants in (rule B15). */
        private const val SQUASH_ROW = 1

        /** The lane the butter-cold observation spawns in (rule B10). */
        private const val BUTTER_ROW = 1

        /** The lane the burn-row observation spawns in (rule B11). */
        private const val BURN_ROW_95 = 2

        /** The lane the yeti-dispatch observation spawns in (rule B12). */
        private const val YETI_ROW = 3

        /** The health the game gives a gargantuar, i.e. a zombie above the burn clamp's 1800. */
        private const val GARGANTUAR_HEALTH = 3000

        /**
         * The two damage flags the shield rule moves, as the game passes them: the flags are
         * <em>bit indices</em>, so {@code DAMAGE_BYPASSES_SHIELD == 0} is bit 0 and
         * {@code DAMAGE_HITS_SHIELD_AND_BODY == 1} is bit 1.
         */
        private val FLAG_BYPASSES_SHIELD = 1 shl DamageFlags.DAMAGE_BYPASSES_SHIELD
        private val FLAG_HITS_SHIELD_AND_BODY = 1 shl DamageFlags.DAMAGE_HITS_SHIELD_AND_BODY

        /** The zombie the extra-fire observation aims the plants at; 60 pixels is inside their reach. */
        private const val EXTRA_FIRE_TARGET_LEAD = 60

        /**
         * The behaviour rules of the fifth batch, each observed on the same real board through the game's
         * own paths - the board's spawner, planter, damage entry points, target searches, eat step and
         * level initialisation - and each next to the control that tells the rule from the game's own
         * behaviour:
         *
         * <ol>
         *   <li>the mind-control boost, by letting the game's own hypnoshroom path hypnotise a zombie;</li>
         *   <li>the burn's clamp, on a football zombie (total health 3070) and on the newspaper zombie's
         *       two immune phases;</li>
         *   <li>{@code RemoveColdEffects}'s 1000;</li>
         *   <li>the shield's absorption of both damage flags, on a ladder zombie;</li>
         *   <li>the refused hiding scary-shroom and the bungee clause that stopped applying;</li>
         *   <li>the extra shot that moved to the gatling pea, away from the cattail;</li>
         *   <li>the tall nut's two crush-proof comparisons;</li>
         *   <li>the bite's 8 and the two transformations, and the seed-packet rewrite.</li>
         * </ol>
         *
         * <p>Each observation starts by marking everything the earlier ones left on the board dead: the
         * game's own searches skip dead objects, so a rule's subjects cannot become another rule's target
         * (the earlier phases use the same trick between their own rounds).
         */
        private fun behaviourPhase(theBoard: Board?) {
            report("behaviour_boardPresent", theBoard != null)
            if (theBoard == null) {
                return
            }

            // The rules as the mod registered them, before any of them ran.
            report("behaviour_zombieRules", ZombieOverrides.rules())
            report("behaviour_plantRules", PlantOverrides.rules())
            report("behaviour_tallnutCrushProof", PlantOverrides.isCrushProof(SeedType.SEED_TALLNUT))
            report("behaviour_spikerockCrushProof", PlantOverrides.isCrushProof(SeedType.SEED_SPIKEROCK))
            report("behaviour_peashooterCrushProof", PlantOverrides.isCrushProof(SeedType.SEED_PEASHOOTER))

            reportMindControl(theBoard)
            reportBurnClamp(theBoard)
            reportColdRemoval(theBoard)
            reportShieldAbsorbs(theBoard)
            reportScaredyTarget(theBoard)
            reportExtraFire(theBoard)
            reportCrushProof(theBoard)
            reportEatRules(theBoard)
            reportFumeRow(theBoard)
            reportChomperReach(theBoard)
            reportSquashReach(theBoard)
            reportButterCold(theBoard)
            reportBurnRow(theBoard)
            reportYetiDispatch(theBoard)
            reportSuperMower(theBoard)

            // Last, because it re-initialises the level: the seed-packet rewrite.
            reportPacketRewrite(theBoard)
        }

        /**
         * Marks every plant, zombie and projectile the earlier observations left behind dead. The game's
         * own searches (a plant's target, a zombie's plant target, a squish pass) all skip dead objects, so
         * this is what makes one rule's subjects invisible to the next rule's.
         */
        private fun clearBoard(theBoard: Board) {
            for (aPlant in theBoard.mPlants) {
                aPlant.mDead = true
            }
            for (aZombie in theBoard.mZombies) {
                aZombie.mDead = true
            }
            for (aProjectile in theBoard.mProjectiles) {
                aProjectile.mDead = true
            }
        }

        /**
         * The mind-control boost (rule B1), observed through the game's own hypnosis path: a hypnoshroom in
         * front of a zombie, the game's own chewing step (which eats the plant and calls
         * {@code Zombie::StartMindControlled}), and what the zombie carries afterwards - next to a zombie
         * nobody hypnotised, which must keep the retail numbers.
         */
        private fun reportMindControl(theBoard: Board) {
            clearBoard(theBoard)

            val aHypno = plant(theBoard, SeedType.SEED_HYPNOSHROOM, 4, MIND_ROW)
            val aZombie = theBoard.addZombieInRow(ZombieType.ZOMBIE_NORMAL, MIND_ROW, Zombie.ZOMBIE_WAVE_UI)
            val aControl = theBoard.addZombieInRow(ZombieType.ZOMBIE_NORMAL, MIND_ROW, Zombie.ZOMBIE_WAVE_UI)
            report("mind_zombieSpawned", aZombie != null)
            if (aHypno == null || aZombie == null || aControl == null) {
                return
            }

            report("mind_bodyHealthBefore", aZombie.mBodyHealth)
            report("mind_bodyMaxHealth", aZombie.mBodyMaxHealth)
            report("mind_helmTypeBefore", aZombie.mHelmType)
            report("mind_helmMaxHealth", aZombie.mHelmMaxHealth)
            report("mind_scaleBefore", aZombie.mScaleZombie)
            report("mind_mindControlledBefore", aZombie.mMindControlled)

            // The game's own plant search has to find the hypnoshroom, so the zombie's attack rectangle is
            // put over it (the game measures the rectangles, not the columns). A mushroom sleeps in a day
            // level, and a sleeping hypnoshroom is not eaten, so it is woken first: the game's own eat step
            // only hypnotises a zombie with an awake hypnoshroom in front of it.
            putZombieAttackRectOverPlant(aZombie, aHypno)
            report("mind_hypnoInAttackRect",
                Board.getRectOverlap(aZombie.getZombieAttackRect(), aHypno.getPlantRect()) >= 20)
            aHypno.mIsAsleep = false
            report("mind_hypnoAsleep", aHypno.mIsAsleep)
            report("mind_hypnoFound", aZombie.findPlantTarget(ZombieAttackType.ATTACKTYPE_CHEW) === aHypno)

            // A chill the boost has to clear, so the counter is not merely "still zero".
            aZombie.mChilledCounter = 250
            report("mind_chilledBeforeBoost", aZombie.mChilledCounter)

            aZombie.animateChewSound()

            report("mind_mindControlled", aZombie.mMindControlled)
            report("mind_hypnoEaten", aHypno.mDead)
            report("mind_bodyHealthAfter", aZombie.mBodyHealth)
            report("mind_bodyMaxHealthAfter", aZombie.mBodyMaxHealth)
            report("mind_helmTypeAfter", aZombie.mHelmType)
            report("mind_helmHealthAfter", aZombie.mHelmHealth)
            report("mind_chilledAfter", aZombie.mChilledCounter)
            report("mind_scaleAfter", aZombie.mScaleZombie)

            report("control_uncharmed_mindControlled", aControl.mMindControlled)
            report("control_uncharmed_bodyHealth", aControl.mBodyHealth)
            report("control_uncharmed_helmHealth", aControl.mHelmHealth)
            report("control_uncharmed_scale", aControl.mScaleZombie)

            // The newspaper zombie's body health is the flat 920 of the patch, not "its maximum plus 200"
            // (its maximum is the ordinary 270).
            val aNewspaper = theBoard.addZombieInRow(ZombieType.ZOMBIE_NEWSPAPER, MIND_ROW, Zombie.ZOMBIE_WAVE_UI)
            report("mind_newspaperSpawned", aNewspaper != null)
            if (aNewspaper == null) {
                return
            }
            report("mind_newspaperBodyMaxHealth", aNewspaper.mBodyMaxHealth)
            aNewspaper.startMindControlled()
            report("mind_newspaperBodyHealth", aNewspaper.mBodyHealth)
            report("mind_newspaperHelmHealth", aNewspaper.mHelmHealth)
            report("mind_newspaperScale", aNewspaper.mScaleZombie)
        }

        /**
         * The burn's clamp (rule B2), through {@code Zombie::ApplyBurn} itself:
         *
         * <ul>
         *   <li>a football zombie at full health - body 270 plus a helmet of 2800, i.e. 3070, over the
         *       1800 the rule names - takes the flat 1800 (which the helmet absorbs, since the shield rule
         *       is on) and stays alive, where the game's own body-health test would burn it up;</li>
         *   <li>a plain zombie (270) burns, the control that shows the predicate is not simply "never
         *       burn";</li>
         *   <li>a newspaper zombie in its walking and maddening phases takes the clamp whatever its health
         *       - and one in its mad phase, the third of the newspaper's phases, does not, so the pair the
         *       rule names is exactly the pair that is exempt. Those three wear the retail newspaper shield
         *       (1200) on the ordinary 270 body, a 1470 total the rule's 1800 threshold is above: only the
         *       phase clause can keep them out of the burn, which makes the two outcomes readable - the
         *       clamp's flat 1800 goes through the shield (which rule B4 routes every hit through) and the
         *       600 left over kill the body, while the burn kills the zombie with its shield and its body
         *       as they were.</li>
         * </ul>
         */
        private fun reportBurnClamp(theBoard: Board) {
            clearBoard(theBoard)

            val aFootball = theBoard.addZombieInRow(ZombieType.ZOMBIE_FOOTBALL, BURN_ROW, Zombie.ZOMBIE_WAVE_UI)
            report("burn_footballSpawned", aFootball != null)
            if (aFootball != null) {
                report("burn_football_totalBefore", aFootball.mBodyHealth + aFootball.mHelmHealth + aFootball.mShieldHealth)
                report("burn_football_bodyBefore", aFootball.mBodyHealth)
                report("burn_football_helmBefore", aFootball.mHelmHealth)
                aFootball.applyBurn()
                report("burn_football_dead", aFootball.mDead)
                report("burn_football_bodyAfter", aFootball.mBodyHealth)
                report("burn_football_helmAfter", aFootball.mHelmHealth)
                report("burn_football_phaseAfter", aFootball.mZombiePhase)
            }

            val aPlain = theBoard.addZombieInRow(ZombieType.ZOMBIE_NORMAL, BURN_ROW, Zombie.ZOMBIE_WAVE_UI)
            if (aPlain != null) {
                report("burn_plain_totalBefore",
                    aPlain.mBodyHealth + aPlain.mHelmHealth + aPlain.mShieldHealth)
                aPlain.applyBurn()
                report("burn_plain_dead", aPlain.mDead)
                report("burn_plain_phaseAfter", aPlain.mZombiePhase)
            }

            reportBurnPhase(theBoard, "reading", ZombiePhase.PHASE_NEWSPAPER_READING)
            reportBurnPhase(theBoard, "maddening", ZombiePhase.PHASE_NEWSPAPER_MADDENING)
            reportBurnPhase(theBoard, "mad", ZombiePhase.PHASE_NEWSPAPER_MAD)
        }

        /**
         * One newspaper phase's burn. The zombie wears the retail newspaper shield (1200) and its body is
         * the ordinary 270, so the pair is under the rule's 1800 - only the phase clause can keep this
         * zombie out of the burn, which is what the three calls tell apart: the two phases the rule names
         * take the clamp (its flat 1800 goes through the shield, which the rule ever routes damage through,
         * so the shield is gone afterwards), while the mad phase burns with its shield untouched.
         */
        private fun reportBurnPhase(theBoard: Board, theLabel: String, theZombiePhase: Int) {
            val aZombie = theBoard.addZombieInRow(ZombieType.ZOMBIE_NEWSPAPER, BURN_ROW, Zombie.ZOMBIE_WAVE_UI)
            if (aZombie == null) {
                return
            }

            aZombie.mZombiePhase = theZombiePhase
            aZombie.mShieldHealth = 1200
            aZombie.mShieldMaxHealth = 1200
            report("burn_" + theLabel + "_phaseBefore", aZombie.mZombiePhase)
            report("burn_" + theLabel + "_totalBefore",
                aZombie.mBodyHealth + aZombie.mHelmHealth + aZombie.mShieldHealth)
            report("burn_" + theLabel + "_shieldBefore", aZombie.mShieldHealth)
            aZombie.applyBurn()
            report("burn_" + theLabel + "_dead", aZombie.mDead)
            report("burn_" + theLabel + "_shieldAfter", aZombie.mShieldHealth)
            report("burn_" + theLabel + "_bodyAfter", aZombie.mBodyHealth)
            report("burn_" + theLabel + "_phaseAfter", aZombie.mZombiePhase)
        }

        /** {@code Zombie::RemoveColdEffects} (rule B3): a chill counter of 250 becomes 1000. */
        private fun reportColdRemoval(theBoard: Board) {
            clearBoard(theBoard)

            val aZombie = theBoard.addZombieInRow(ZombieType.ZOMBIE_NORMAL, COLD_ROW, Zombie.ZOMBIE_WAVE_UI)
            report("cold_zombieSpawned", aZombie != null)
            if (aZombie == null) {
                return
            }

            aZombie.mChilledCounter = 250
            report("cold_chilledBefore", aZombie.mChilledCounter)
            aZombie.removeColdEffects()
            report("cold_chilledAfter", aZombie.mChilledCounter)
            report("cold_iceTrapCounter", aZombie.mIceTrapCounter)
        }

        /**
         * The fume-shroom's puff (rule B17), through the game's own {@code Plant::DoRowAreaDamage} - the
         * call its attack animation makes: the zombie at the far edge of the board and one right in front
         * of the plant are both damaged, while one behind the plant and one in another row are not.
         */
        private fun reportFumeRow(theBoard: Board) {
            clearBoard(theBoard)

            val aFume = plant(theBoard, SeedType.SEED_FUMESHROOM, 4, FUME_ROW)
            val aFar = theBoard.addZombieInRow(ZombieType.ZOMBIE_NORMAL, FUME_ROW, Zombie.ZOMBIE_WAVE_UI)
            val aBehind = theBoard.addZombieInRow(ZombieType.ZOMBIE_NORMAL, FUME_ROW, Zombie.ZOMBIE_WAVE_UI)
            val aOtherRow = theBoard.addZombieInRow(ZombieType.ZOMBIE_NORMAL, FUME_ROW + 2, Zombie.ZOMBIE_WAVE_UI)
            report("fumeRow_planted", aFume != null)
            if (aFume == null || aFar == null || aBehind == null || aOtherRow == null) {
                return
            }

            val aCell = theBoard.gridToPixelX(1, FUME_ROW) - theBoard.gridToPixelX(0, FUME_ROW)
            aFar.mX = aFume.mX + 5 * aCell        // the far side of the board, still the same row
            aBehind.mX = aFume.mX - 3 * aCell     // behind the plant
            report("fumeRow_farX", aFar.mX.toString() + "/" + aFume.mX + "/" + aCell)
            report("fumeRow_farInFront", aFar.mX > aFume.mX)

            val aFarHealth = aFar.mBodyHealth
            val aBehindHealth = aBehind.mBodyHealth
            val aOtherRowHealth = aOtherRow.mBodyHealth
            aFume.doRowAreaDamage(20, 33)
            report("fumeRow_farDamage", aFarHealth - aFar.mBodyHealth)
            report("fumeRow_behindDamage", aBehindHealth - aBehind.mBodyHealth)
            report("fumeRow_otherRowDamage", aOtherRowHealth - aOtherRow.mBodyHealth)
        }

        /**
         * The chomper's reach (rule B16), through the game's own {@code Plant::FindTargetZombie} - the
         * search its bite runs: a zombie two cells ahead is a target, which is the two cells the 95 edition
         * gives the chomper, and one three cells ahead is not.
         */
        private fun reportChomperReach(theBoard: Board) {
            clearBoard(theBoard)

            val aChomper = plant(theBoard, SeedType.SEED_CHOMPER, 3, CHOMPER_REACH_ROW)
            val aZombie = theBoard.addZombieInRow(ZombieType.ZOMBIE_NORMAL, CHOMPER_REACH_ROW,
                Zombie.ZOMBIE_WAVE_UI)
            report("chomperReach_planted", aChomper != null)
            report("chomperReach_zombieSpawned", aZombie != null)
            if (aChomper == null || aZombie == null) {
                return
            }

            val aCell = theBoard.gridToPixelX(1, CHOMPER_REACH_ROW) - theBoard.gridToPixelX(0, CHOMPER_REACH_ROW)
            report("chomperReach_attackRectWidth",
                aChomper.getPlantAttackRect(PlantWeapon.WEAPON_PRIMARY).mWidth)

            // Two cells measured on the zombie itself, which is what a player sees.
            aZombie.mX = aChomper.mX + 2 * aCell
            // How far the zombie's own rectangle sits past the attack rectangle's edge: the search needs
            // that to be an overlap, which is what the widening buys.
            report("chomperReach_overlap", Board.getRectOverlap(
                aChomper.getPlantAttackRect(PlantWeapon.WEAPON_PRIMARY), aZombie.getZombieRect()))
            report("chomperReach_twoCells_targetFound",
                aChomper.findTargetZombie(CHOMPER_REACH_ROW, PlantWeapon.WEAPON_PRIMARY) != null)

            aZombie.mX = aChomper.mX + 3 * aCell
            report("chomperReach_threeCells_targetFound",
                aChomper.findTargetZombie(CHOMPER_REACH_ROW, PlantWeapon.WEAPON_PRIMARY) != null)
        }

        /**
         * The squash's reach (rule B15), through {@code Plant::FindSquashTarget} as the game's own jump
         * runs it: a zombie two cells ahead is found - the two cells the 95 edition gives the squash - and
         * a zombie three cells ahead is not, which is the far end of the same search.
         */
        private fun reportSquashReach(theBoard: Board) {
            clearBoard(theBoard)

            val aSquash = plant(theBoard, SeedType.SEED_SQUASH, 2, SQUASH_ROW)
            val aZombie = theBoard.addZombieInRow(ZombieType.ZOMBIE_NORMAL, SQUASH_ROW, Zombie.ZOMBIE_WAVE_UI)
            report("squash_planted", aSquash != null)
            report("squash_zombieSpawned", aZombie != null)
            if (aSquash == null || aZombie == null) {
                return
            }

            val aCell = theBoard.gridToPixelX(1, SQUASH_ROW) - theBoard.gridToPixelX(0, SQUASH_ROW)
            report("squash_cellWidth", aCell)
            val aRect = aSquash.getPlantAttackRect(PlantWeapon.WEAPON_PRIMARY)
            report("squash_attackRectReach", (aSquash.mX.toInt() + aRect.mWidth - aSquash.mX.toInt()).toString() + "/" +
                aRect.mWidth)

            aZombie.mX = aSquash.mX + 2 * aCell - 10
            report("squash_twoCells_targetFound", aSquash.findSquashTarget() != null)

            aZombie.mX = aSquash.mX + 3 * aCell - 10
            report("squash_threeCells_targetFound", aSquash.findSquashTarget() != null)
        }

        /**
         * The butter's cold (rule B10), through {@code Zombie::ApplyButter} as the game runs it: a buttered
         * zombie carries the game's own 400 butter frames and - where the rule writes itself next to that
         * write - the 300 ice-trap and 1000 chill frames of the patched cave; a zombie the method returns
         * before the butter write for (the zamboni) carries none of them, which is the control that shows
         * the write sits behind the method's own guards.
         */
        private fun reportButterCold(theBoard: Board) {
            clearBoard(theBoard)

            val aZombie = theBoard.addZombieInRow(ZombieType.ZOMBIE_NORMAL, BUTTER_ROW, Zombie.ZOMBIE_WAVE_UI)
            report("butter_zombieSpawned", aZombie != null)
            if (aZombie != null) {
                report("butter_before", aZombie.mButteredCounter.toString() + "/" + aZombie.mIceTrapCounter + "/"
                    + aZombie.mChilledCounter)
                aZombie.applyButter()
                report("butter_butteredCounter", aZombie.mButteredCounter)
                report("butter_iceTrapCounter", aZombie.mIceTrapCounter)
                report("butter_chilledCounter", aZombie.mChilledCounter)
            }

            val aZamboni = theBoard.addZombieInRow(ZombieType.ZOMBIE_ZAMBONI, BUTTER_ROW, Zombie.ZOMBIE_WAVE_UI)
            report("butter_zamboniSpawned", aZamboni != null)
            if (aZamboni != null) {
                aZamboni.applyButter()
                report("butter_zamboni_butteredCounter", aZamboni.mButteredCounter)
                report("butter_zamboni_iceTrapCounter", aZamboni.mIceTrapCounter)
                report("butter_zamboni_chilledCounter", aZamboni.mChilledCounter)
            }
        }

        /**
         * The burn row (rule B11), through {@code Plant::BurnRow} as the jalapeno's own explosion runs it:
         * a gargantuar (3000 health, i.e. above the burn's 1800 clamp) keeps the cold counters the rule
         * writes where the game's own row clears them, and loses the rule's 1000 health - not the clamp's
         * 1800, which the rule suppresses for exactly this burn - while a plain zombie the clamp does not
         * cover still burns away as it always did, the control.
         */
        private fun reportBurnRow(theBoard: Board) {
            clearBoard(theBoard)

            val aJalapeno = plant(theBoard, SeedType.SEED_JALAPENO, 4, BURN_ROW_95)
            val aGargantuar = theBoard.addZombieInRow(ZombieType.ZOMBIE_GARGANTUAR, BURN_ROW_95,
                Zombie.ZOMBIE_WAVE_UI)
            val aPlain = theBoard.addZombieInRow(ZombieType.ZOMBIE_NORMAL, BURN_ROW_95, Zombie.ZOMBIE_WAVE_UI)
            report("burnrow_jalapenoPlaced", aJalapeno != null)
            report("burnrow_gargantuarSpawned", aGargantuar != null)
            report("burnrow_plainSpawned", aPlain != null)
            if (aJalapeno == null || aGargantuar == null || aPlain == null) {
                return
            }

            // Spawned zombies walk in from off the wide board, and the row's own damage check refuses an
            // off-board zombie, so both are put on the lawn first - the way the blast observation does.
            aGargantuar.mX = BLAST_X
            aPlain.mX = BLAST_X

            report("burnrow_healthBefore", aGargantuar.mBodyHealth)
            report("burnrow_plainPhaseBefore", aPlain.mZombiePhase)
            report("burnrow_plainHealthBefore", aPlain.mBodyHealth)
            report("burnrow_plainEffected",
                aPlain.effectedByDamage(aJalapeno.getDamageRangeFlags(PlantWeapon.WEAPON_PRIMARY)))
            report("burnrow_plainRow", aPlain.mRow)
            report("burnrow_plainDeadBefore", aPlain.mDead)
            report("burnrow_gargantuarRow", aGargantuar.mRow)
            report("burnrow_rangeFlags", aJalapeno.getDamageRangeFlags(PlantWeapon.WEAPON_PRIMARY))
            report("burnrow_gargantuarEffected",
                aGargantuar.effectedByDamage(aJalapeno.getDamageRangeFlags(PlantWeapon.WEAPON_PRIMARY)))

            // The control for the burn itself: a zombie the rule never reaches (burned directly, no burn
            // row) must still take the clamp's 1800, i.e. the suppression is the row's, not the burn's.
            val aControl = theBoard.addZombieInRow(ZombieType.ZOMBIE_GARGANTUAR, BLAST_ROW,
                Zombie.ZOMBIE_WAVE_UI)
            if (aControl != null) {
                aControl.mX = BLAST_X
                val aControlBefore = aControl.mBodyHealth
                aControl.applyBurn()
                report("burnrow_controlDamage", aControlBefore - aControl.mBodyHealth)
            }

            aJalapeno.burnRow(BURN_ROW_95)
            report("burnrow_healthAfter", aGargantuar.mBodyHealth)
            report("burnrow_gargantuarPhaseAfter", aGargantuar.mZombiePhase)
            report("burnrow_coldAfter", aGargantuar.mIceTrapCounter.toString() + "/" + aGargantuar.mChilledCounter)
            report("burnrow_plainPhaseAfter", aPlain.mZombiePhase)
            report("burnrow_plainDeadAfter", aPlain.mDead)
            report("burnrow_plainColdAfter", aPlain.mIceTrapCounter.toString() + "/" + aPlain.mChilledCounter)
            report("burnrow_plainHealthAfter", aPlain.mBodyHealth)

            // The second control: the same plain zombie burned directly (no row) burns away as it always
            // did, which is what tells the row's loop apart from the burn itself.
            val aPlainBefore = aPlain.mBodyHealth
            aPlain.applyBurn()
            report("burnrow_plainDirectDamage", aPlainBefore - aPlain.mBodyHealth)
            report("burnrow_plainDirectPhase", aPlain.mZombiePhase)
            report("burnrow_plainDirectCold", aPlain.mIceTrapCounter.toString() + "/" + aPlain.mChilledCounter)

            // The third control: the rule's own burn step called by hand, which is what the row calls for
            // every zombie it covers - with the clamp suppressed it is the rule's 1000, not 2800.
            val aHook = theBoard.addZombieInRow(ZombieType.ZOMBIE_GARGANTUAR, BLAST_ROW, Zombie.ZOMBIE_WAVE_UI)
            if (aHook != null) {
                aHook.mX = BLAST_X
                val aHookBefore = aHook.mBodyHealth
                cn.enaium.fabricpvz.api.zombie.ZombieHooks.burnRowApplyBurn(aHook)
                report("burnrow_hookDamage", aHookBefore - aHook.mBodyHealth)
            }
        }

        /**
         * The yeti's per-type update (rule B12), through {@code Zombie::UpdateActions} as the game runs it:
         * a flag zombie with a zero phase counter takes the yeti's phase change
         * ({@code PHASE_YETI_RUNNING}) and a yeti keeps the phase it had, where the game's own dispatch does
         * exactly the opposite. Both zombies are put in the phase and counter the update reacts to by hand,
         * because a freshly spawned zombie carries the counter of its spawn.
         */
        private fun reportYetiDispatch(theBoard: Board) {
            clearBoard(theBoard)

            val aFlag = theBoard.addZombieInRow(ZombieType.ZOMBIE_FLAG, YETI_ROW, Zombie.ZOMBIE_WAVE_UI)
            val aYeti = theBoard.addZombieInRow(ZombieType.ZOMBIE_YETI, YETI_ROW, Zombie.ZOMBIE_WAVE_UI)
            report("yeti_flagSpawned", aFlag != null)
            report("yeti_yetiSpawned", aYeti != null)
            if (aFlag == null || aYeti == null) {
                return
            }

            aFlag.mZombiePhase = ZombiePhase.PHASE_ZOMBIE_NORMAL
            aFlag.mPhaseCounter = 0
            aFlag.mHasHead = true
            aFlag.updateActions()
            report("yeti_flag_phase", aFlag.mZombiePhase)

            aYeti.mZombiePhase = ZombiePhase.PHASE_ZOMBIE_NORMAL
            aYeti.mPhaseCounter = 0
            aYeti.mHasHead = true
            aYeti.updateActions()
            report("yeti_yeti_phase", aYeti.mZombiePhase)
        }

        /**
         * The super-mower toggle (rule B13), through {@code Board::SetSuperMowerMode} as the game's own
         * cheat key runs it: the game writes the flag and re-skins its mowers, and - where the rule is set -
         * every mower of the board is left {@code MOWER_TRIGGERED} (2) on top of that.
         *
         * <p>The board this phase was handed is the one the earlier phases re-initialised per mode, and a
         * mode without mowers leaves it without any, so the observation gives it the mowers the game's own
         * {@code Board::InitLawnMowers} would have created for a lawn level - one per lane, through
         * {@code LawnMower::LawnMowerInitialize}, the same call the game makes - when it has none.
         */
        private fun reportSuperMower(theBoard: Board) {
            if (mowerStates(theBoard).isEmpty()) {
                for (aRow in 0 until Board.MAX_GRID_SIZE_Y) {
                    theBoard.mLawnMowers.dataArrayAlloc().lawnMowerInitialize(aRow)
                }
            }
            report("mower_count", mowerStates(theBoard).split(",").size)
            report("mower_statesBefore", mowerStates(theBoard))
            theBoard.setSuperMowerMode(true)
            report("mower_modeAfter", theBoard.mSuperMowerMode)
            report("mower_statesAfter", mowerStates(theBoard))
        }

        /** The state of every mower of a board, in row order, as {@code "2,2,2,..."}. */
        private fun mowerStates(theBoard: Board): String {
            val aStates = StringBuilder()
            for (aLawnMower in theBoard.mLawnMowers) {
                if (aStates.isNotEmpty()) {
                    aStates.append(',')
                }
                aStates.append(aLawnMower.mMowerState)
            }
            return aStates.toString()
        }

        /**
         * The shield rule (rule B4), on a ladder zombie - the zombie the rule exists for - through
         * {@code Zombie::TakeDamage}:
         *
         * <ul>
         *   <li>a hit flagged {@code DAMAGE_HITS_SHIELD_AND_BODY} takes 100 off the ladder and leaves the
         *       body alone (the game's own code would take 100 off both);</li>
         *   <li>a hit flagged {@code DAMAGE_BYPASSES_SHIELD} takes 100 off the ladder as well (the game's
         *       own code would take it off the body);</li>
         *   <li>a zombie with no shield still takes the body damage as it always did (the control);</li>
         *   <li>and once the ladder is gone, what is left over from a hit reaches the body.</li>
         * </ul>
         */
        private fun reportShieldAbsorbs(theBoard: Board) {
            clearBoard(theBoard)

            val aLadder = theBoard.addZombieInRow(ZombieType.ZOMBIE_LADDER, SHIELD_ROW, Zombie.ZOMBIE_WAVE_UI)
            report("shield_ladderSpawned", aLadder != null)
            if (aLadder != null) {
                report("shield_ladder_shieldType", aLadder.mShieldType)
                report("shield_ladder_shieldBefore", aLadder.mShieldHealth)
                report("shield_ladder_bodyBefore", aLadder.mBodyHealth)

                aLadder.takeDamage(100, FLAG_HITS_SHIELD_AND_BODY)
                report("shield_ladder_hitsBody_shieldAfter", aLadder.mShieldHealth)
                report("shield_ladder_hitsBody_bodyAfter", aLadder.mBodyHealth)

                aLadder.takeDamage(100, FLAG_BYPASSES_SHIELD)
                report("shield_ladder_bypass_shieldAfter", aLadder.mShieldHealth)
                report("shield_ladder_bypass_bodyAfter", aLadder.mBodyHealth)

                aLadder.takeDamage(400, 0)
                report("shield_ladder_broken_shieldType", aLadder.mShieldType)
                report("shield_ladder_broken_shieldAfter", aLadder.mShieldHealth)
                report("shield_ladder_broken_bodyAfter", aLadder.mBodyHealth)
            }

            // The control: a zombie without a shield takes the body damage exactly as before.
            val aPlain = theBoard.addZombieInRow(ZombieType.ZOMBIE_NORMAL, SHIELD_ROW, Zombie.ZOMBIE_WAVE_UI)
            if (aPlain != null) {
                report("shield_plain_shieldType", aPlain.mShieldType)
                report("shield_plain_bodyBefore", aPlain.mBodyHealth)
                aPlain.takeDamage(100, FLAG_HITS_SHIELD_AND_BODY)
                report("shield_plain_bodyAfter", aPlain.mBodyHealth)
            }
        }

        /**
         * The leftover-plant rule (rule B5), through the game's own "may this zombie target this plant"
         * question: a hiding scary-shroom is refused and an awake one is not; a plant of another seed type
         * in the same hiding state is refused as well (the rule is the state, not the seed type); a plant
         * rising with a bungee is <em>no longer</em> refused, which is the price the patch pays for moving
         * the predicate; and an ordinary plant is targetable, the control.
         */
        private fun reportScaredyTarget(theBoard: Board) {
            clearBoard(theBoard)

            val aScaredy = plant(theBoard, SeedType.SEED_SCAREDYSHROOM, 2, SCAREDY_ROW)
            val aPeaInThatState = plant(theBoard, SeedType.SEED_PEASHOOTER, 3, SCAREDY_ROW)
            val aRisingPea = plant(theBoard, SeedType.SEED_PEASHOOTER, 4, SCAREDY_ROW)
            val aZombie = theBoard.addZombieInRow(ZombieType.ZOMBIE_NORMAL, SCAREDY_ROW, Zombie.ZOMBIE_WAVE_UI)
            report("scaredy_zombieSpawned", aZombie != null)
            if (aScaredy == null || aPeaInThatState == null || aRisingPea == null || aZombie == null) {
                return
            }

            report("scaredy_bornState", aScaredy.mState)
            report("scaredy_awake_targetable",
                aZombie.canTargetPlant(aScaredy, ZombieAttackType.ATTACKTYPE_CHEW))

            aScaredy.mState = PlantState.STATE_SCAREDYSHROOM_SCARED
            report("scaredy_hidingState", aScaredy.mState)
            report("scaredy_hiding_targetable",
                aZombie.canTargetPlant(aScaredy, ZombieAttackType.ATTACKTYPE_CHEW))

            aPeaInThatState.mState = PlantState.STATE_SCAREDYSHROOM_SCARED
            report("scaredy_otherSeedType_hiding_targetable",
                aZombie.canTargetPlant(aPeaInThatState, ZombieAttackType.ATTACKTYPE_CHEW))

            aRisingPea.mOnBungeeState = PlantOnBungeeState.RISING_WITH_BUNGEE
            report("scaredy_risingWithBungee_notOnGround", aRisingPea.notOnGround())
            report("scaredy_risingWithBungee_targetable",
                aZombie.canTargetPlant(aRisingPea, ZombieAttackType.ATTACKTYPE_CHEW))

            val aPlain = plant(theBoard, SeedType.SEED_PEASHOOTER, 5, SCAREDY_ROW)
            if (aPlain != null) {
                report("control_plainPlant_notOnGround", aPlain.notOnGround())
                report("control_plainPlant_targetable",
                    aZombie.canTargetPlant(aPlain, ZombieAttackType.ATTACKTYPE_CHEW))
            }
        }

        /**
         * The extra shot (rule B6), by driving {@code Plant::UpdateShooter} to the launch counter the rule
         * names: with the counter one above 50 the method decrements it to exactly 50 and asks for the extra
         * shot - which for these two seed types (the fallen-back arm of {@code FindTargetAndFire}) starts
         * the shooting animation rather than firing a shot on the spot, so the shot the animation leads to
         * is what the observation follows:
         *
         * <ul>
         *   <li>the gatling pea takes the extra shot: the counter the fire left is the 100 its own arm
         *       starts, and stepping the game's own {@code UpdateShooting} from there to the end of the
         *       window fires four peas - its per-burst chain (counters 18/35/51/68) is untouched, so the
         *       moved shot is one extra firing of that chain, not "six peas a burst";</li>
         *   <li>the cattail, which the game used to give that shot to, takes nothing: the counter is still
         *       the 0 the observation set and the board gained no spike.</li>
         * </ul>
         */
        private fun reportExtraFire(theBoard: Board) {
            clearBoard(theBoard)

            val aTarget = theBoard.addZombieInRow(ZombieType.ZOMBIE_NORMAL, EXTRA_FIRE_ROW, Zombie.ZOMBIE_WAVE_UI)
            report("extraFire_targetSpawned", aTarget != null)
            if (aTarget == null) {
                return
            }

            val aGatling = plant(theBoard, SeedType.SEED_GATLINGPEA, 2, EXTRA_FIRE_ROW)
            if (aGatling != null) {
                // On the lawn, in the plant's own attack rectangle: what the game's target search measures.
                putZombieInPlantAttackRect(aTarget, aGatling, PlantWeapon.WEAPON_PRIMARY, EXTRA_FIRE_TARGET_LEAD)
                report("extraFire_gatling_registeredSeedType", PlantOverrides.extraFireSeedType())
                report("extraFire_gatling_targetFound",
                    aGatling.findTargetZombie(EXTRA_FIRE_ROW, PlantWeapon.WEAPON_PRIMARY) === aTarget)

                aGatling.mLaunchCounter = 51
                aGatling.mShootingCounter = 0
                aGatling.updateShooter()
                report("extraFire_gatling_launchCounter", aGatling.mLaunchCounter)
                report("extraFire_gatling_shootingCounter", aGatling.mShootingCounter)

                val aBurstBefore = countLiveProjectiles(theBoard, ProjectileType.PROJECTILE_PEA)
                var aSteps = 0
                // The gatling's arm keeps the counter at 1 once the window is over (the game's own update
                // would advance the head reanimation and end it), so the loop stops there.
                while (aGatling.mShootingCounter > 1 && aSteps < 400) {
                    aGatling.updateShooting()
                    aSteps++
                }
                report("extraFire_gatling_burstSteps", aSteps)
                report("extraFire_gatling_burstShots",
                    countLiveProjectiles(theBoard, ProjectileType.PROJECTILE_PEA) - aBurstBefore)
                report("extraFire_gatling_shootingCounterAfterBurst", aGatling.mShootingCounter)
            }

            val aCattail = plant(theBoard, SeedType.SEED_CATTAIL, 5, EXTRA_FIRE_ROW)
            if (aCattail != null) {
                putZombieInPlantAttackRect(aTarget, aCattail, PlantWeapon.WEAPON_PRIMARY, EXTRA_FIRE_TARGET_LEAD)
                report("extraFire_cattail_targetFound",
                    aCattail.findTargetZombie(EXTRA_FIRE_ROW, PlantWeapon.WEAPON_PRIMARY) === aTarget)

                val aSpikesBefore = countLiveProjectiles(theBoard, ProjectileType.PROJECTILE_SPIKE)
                aCattail.mLaunchCounter = 51
                aCattail.mShootingCounter = 0
                aCattail.updateShooter()
                report("extraFire_cattail_launchCounter", aCattail.mLaunchCounter)
                report("extraFire_cattail_shootingCounter", aCattail.mShootingCounter)
                report("extraFire_cattail_shots",
                    countLiveProjectiles(theBoard, ProjectileType.PROJECTILE_SPIKE) - aSpikesBefore)
            }
            report("extraFire_projectileTypes", joinedProjectileTypes(theBoard))
        }

        /**
         * The tall nut's two crush-proof comparisons (rule B7):
         *
         * <ul>
         *   <li>{@code Zombie::UpdateZombieGargantuar}'s smash, by driving the game's own method with the
         *       smash animation's hit frame forced open: a gargantuar smashing an ordinary plant squashes
         *       it and takes no damage, while one smashing a tall nut or a spikerock bounces off - it takes
         *       the bounce damage, the plant takes a spikerock hit and is not squashed;</li>
         *   <li>{@code Zombie::SquishAllInSquare}, the whole-square pass every vehicle and every squash
         *       makes, called for its own two attack types: an ordinary plant is squashed, a tall nut and a
         *       spikerock are not.</li>
         * </ul>
         */
        private fun reportCrushProof(theBoard: Board) {
            reportSmash(theBoard, "plain", SeedType.SEED_PEASHOOTER)
            reportSmash(theBoard, "tallnut", SeedType.SEED_TALLNUT)
            reportSmash(theBoard, "spikerock", SeedType.SEED_SPIKEROCK)

            reportSquish(theBoard, "driveOver", ZombieAttackType.ATTACKTYPE_DRIVE_OVER)
            reportSquish(theBoard, "chew", ZombieAttackType.ATTACKTYPE_CHEW)
        }

        /** One gargantuar smash, through {@code Zombie::UpdateZombieGargantuar}. */
        private fun reportSmash(theBoard: Board, theLabel: String, theSeedType: Int) {
            clearBoard(theBoard)

            val aPlant = plant(theBoard, theSeedType, 2, CRUSH_ROW)
            val aGargantuar = theBoard.addZombieInRow(ZombieType.ZOMBIE_GARGANTUAR, CRUSH_ROW, Zombie.ZOMBIE_WAVE_UI)
            if (aPlant == null || aGargantuar == null) {
                report("smash_" + theLabel + "_planted", false)
                return
            }

            putZombieAttackRectOverPlant(aGargantuar, aPlant)
            aGargantuar.mZombiePhase = ZombiePhase.PHASE_GARGANTUAR_SMASHING
            report("smash_" + theLabel + "_found", aGargantuar.findPlantTarget(ZombieAttackType.ATTACKTYPE_CHEW) === aPlant)
            report("smash_" + theLabel + "_plantHealthBefore", aPlant.mPlantHealth)
            report("smash_" + theLabel + "_zombieBodyBefore", aGargantuar.mBodyHealth)

            // The smash block runs on the animation's hit frame: the game asks
            // `shouldTriggerTimedEvent(0.64f)`, which is true while the frame time is inside
            // [mLastFrameTime, mAnimTime) - so the harness brackets 0.64 with the two fields.
            val aBodyReanim = theBoard.mApp.reanimationTryToGet(aGargantuar.mBodyReanimID)
            report("smash_" + theLabel + "_reanimPresent", aBodyReanim != null)
            if (aBodyReanim == null) {
                return
            }
            aBodyReanim.mLastFrameTime = 0.60f
            aBodyReanim.mAnimTime = 0.70f
            report("smash_" + theLabel + "_hitFrame", aBodyReanim.shouldTriggerTimedEvent(0.64f))

            ZombieBehaviourA.updateZombieGargantuar(aGargantuar)

            report("smash_" + theLabel + "_plantDead", aPlant.mDead)
            report("smash_" + theLabel + "_plantSquished", aPlant.mSquished)
            report("smash_" + theLabel + "_plantHealthAfter", aPlant.mPlantHealth)
            report("smash_" + theLabel + "_zombieBodyAfter", aGargantuar.mBodyHealth)
            report("smash_" + theLabel + "_plantsEaten", theBoard.mPlantsEaten)
        }

        /** One whole-square squish pass, through {@code Zombie::SquishAllInSquare}. */
        private fun reportSquish(theBoard: Board, theLabel: String, theAttackType: Int) {
            clearBoard(theBoard)

            val aPlain = plant(theBoard, SeedType.SEED_PEASHOOTER, 2, CRUSH_ROW)
            val aTallNut = plant(theBoard, SeedType.SEED_TALLNUT, 3, CRUSH_ROW)
            val aSpikeRock = plant(theBoard, SeedType.SEED_SPIKEROCK, 4, CRUSH_ROW)
            val aZombie = theBoard.addZombieInRow(ZombieType.ZOMBIE_ZAMBONI, CRUSH_ROW, Zombie.ZOMBIE_WAVE_UI)
            if (aPlain == null || aTallNut == null || aSpikeRock == null || aZombie == null) {
                report("squish_" + theLabel + "_spawned", false)
                return
            }

            aZombie.squishAllInSquare(2, CRUSH_ROW, theAttackType)
            aZombie.squishAllInSquare(3, CRUSH_ROW, theAttackType)
            aZombie.squishAllInSquare(4, CRUSH_ROW, theAttackType)

            report("squish_" + theLabel + "_plainDead", aPlain.mDead)
            report("squish_" + theLabel + "_plainSquished", aPlain.mSquished)
            report("squish_" + theLabel + "_tallnutDead", aTallNut.mDead)
            report("squish_" + theLabel + "_tallnutSquished", aTallNut.mSquished)
            report("squish_" + theLabel + "_spikerockDead", aSpikeRock.mDead)
            report("squish_" + theLabel + "_spikerockSquished", aSpikeRock.mSquished)
            report("squish_" + theLabel + "_tallnutHealth", aTallNut.mPlantHealth)
            report("squish_" + theLabel + "_spikerockHealth", aSpikeRock.mPlantHealth)
        }

        /**
         * What a bite does (rule B8), through {@code Zombie::EatPlant} itself - the method the patch's
         * cave is injected into:
         *
         * <ul>
         *   <li>an explode-o-nut at 45 health survives one bite (45 - 8 = 37, still over 40) and turns into
         *       a cherry bomb on the next one (37 is under 40), with the two countdowns the patch writes -
         *       and the plant's own next {@code Update} then really explodes it, which the zombie standing
         *       next to it shows;</li>
         *   <li>a tall nut at 320 health - 320 - 8 = 312, over its 300 threshold - is untouched by the
         *       first bite and becomes a squash on the next one (312 - 8 = 304 <em>and</em> the health the
         *       patch tests, 312 - 8, is under 300 only when the tall nut is at 305; the harness uses 305
         *       for the transformation and 320 for the control);</li>
         *   <li>every bite takes 8 off, not 4.</li>
         * </ul>
         */
        private fun reportEatRules(theBoard: Board) {
            clearBoard(theBoard)

            // (a) the explode-o-nut's transformation: one bite above the threshold (45 - 8 = 37, still over
            //     40) and one below it (37 is under 40, so this bite turns the plant into a cherry bomb).
            //     The zombie stands next to the plant, so the blast that follows really reaches it.
            val aExplodeONut = plant(theBoard, SeedType.SEED_EXPLODE_O_NUT, 2, EAT_ROW)
            val aBiter = theBoard.addZombieInRow(ZombieType.ZOMBIE_NORMAL, EAT_ROW, Zombie.ZOMBIE_WAVE_UI)
            report("eat_zombieSpawned", aBiter != null)
            if (aExplodeONut != null && aBiter != null) {
                putZombieRectOnPlant(aBiter, aExplodeONut)
                aExplodeONut.mPlantHealth = 45
                report("eat_explode_seedTypeBefore", aExplodeONut.mSeedType)
                report("eat_explode_healthBefore", aExplodeONut.mPlantHealth)
                aBiter.eatPlant(aExplodeONut)
                report("eat_explode_healthAfterFirstBite", aExplodeONut.mPlantHealth)
                report("eat_explode_seedTypeAfterFirstBite", aExplodeONut.mSeedType)
                report("eat_firstBiteDamage", 45 - aExplodeONut.mPlantHealth)
                aBiter.eatPlant(aExplodeONut)
                report("eat_explode_healthAfterSecondBite", aExplodeONut.mPlantHealth)
                report("eat_explode_seedTypeAfterSecondBite", aExplodeONut.mSeedType)
                report("eat_explode_specialCountdown", aExplodeONut.mDoSpecialCountdown)
                report("eat_explode_stateCountdown", aExplodeONut.mStateCountdown)

                // The plant's own update turns that countdown into the cherry bomb's blast, which the
                // zombie standing next to it shows.
                report("eat_explode_biterInBlast",
                    Board.getCircleRectOverlap(aExplodeONut.mX + aExplodeONut.mWidth / 2,
                        aExplodeONut.mY + aExplodeONut.mHeight / 2, 115, aBiter.getZombieRect()))
                val aZombieHealthBefore = aBiter.mBodyHealth
                aExplodeONut.update()
                report("eat_explode_plantDead", aExplodeONut.mDead)
                report("eat_explode_zombieBodyAfter", aBiter.mBodyHealth)
                report("eat_explode_zombieAffected", aBiter.mBodyHealth < aZombieHealthBefore || aBiter.mDead)
            }

            // (b) the tall nut's transformation (305 - 8 = 297, under its 300 threshold) and the control one
            //     bite above it (320 - 8 = 312, over it), each bitten by a zombie of its own.
            val aTallNut = plant(theBoard, SeedType.SEED_TALLNUT, 3, EAT_ROW)
            val aTallNutBiter = theBoard.addZombieInRow(ZombieType.ZOMBIE_NORMAL, EAT_ROW, Zombie.ZOMBIE_WAVE_UI)
            if (aTallNut != null && aTallNutBiter != null) {
                aTallNut.mPlantHealth = 305
                report("eat_tallnut_seedTypeBefore", aTallNut.mSeedType)
                aTallNutBiter.eatPlant(aTallNut)
                report("eat_tallnut_healthAfter", aTallNut.mPlantHealth)
                report("eat_tallnut_seedTypeAfter", aTallNut.mSeedType)
            }

            val aTallNutControl = plant(theBoard, SeedType.SEED_TALLNUT, 4, EAT_ROW)
            val aControlBiter = theBoard.addZombieInRow(ZombieType.ZOMBIE_NORMAL, EAT_ROW, Zombie.ZOMBIE_WAVE_UI)
            if (aTallNutControl != null && aControlBiter != null) {
                aTallNutControl.mPlantHealth = 320
                aControlBiter.eatPlant(aTallNutControl)
                report("control_tallnut_healthAfter", aTallNutControl.mPlantHealth)
                report("control_tallnut_seedTypeAfter", aTallNutControl.mSeedType)
            }
        }

        /**
         * The seed-packet rewrite (rule B8's other half), through the game's own level initialisation: the
         * ice challenge's bank carries a wall-nut packet, and after {@code Board::InitLevel} - which is where
         * the patch's cave runs, with the bank already filled - the packet <em>is</em> an explode-o-nut, next
         * to the five packets the rule leaves alone. The name and the price are what a player reads off that
         * packet, and both are the explode-o-nut's; the drawn art of the two seed types is the same retail
         * reanimation (both have packet index 2), so the type, the name and the price are the whole visible
         * difference.
         */
        private fun reportPacketRewrite(theBoard: Board) {
            val aSavedMode = theBoard.mApp.mGameMode
            try {
                clearBoard(theBoard)
                theBoard.mApp.mGameMode = GameMode.GAMEMODE_CHALLENGE_ICE
                theBoard.initLevel()

                for (i in 0 until 6) {
                    report("packet_bankType_" + i, theBoard.mSeedBank.mSeedPackets[i].mPacketType)
                }
                report("packet_wallnutPacketType", theBoard.mSeedBank.mSeedPackets[2].mPacketType)
                report("packet_wallnutDisplayName", Plant.getNameString(
                    theBoard.mSeedBank.mSeedPackets[2].mPacketType, theBoard.mSeedBank.mSeedPackets[2].mImitaterType))
                report("packet_explodeONutName", Plant.getNameString(SeedType.SEED_EXPLODE_O_NUT, SeedType.SEED_NONE))
                report("packet_wallnutName", Plant.getNameString(SeedType.SEED_WALLNUT, SeedType.SEED_NONE))
                report("packet_displayedSeedTypeName", Plant.getNameString(
                    theBoard.mSeedBank.mSeedPackets[2].mPacketType, SeedType.SEED_NONE))
                report("packet_wallnutPacketCost",
                    Plant.getPlantDefinition(SeedType.SEED_WALLNUT).mSeedCost)
                report("packet_explodeONutPacketCost",
                    Plant.getPlantDefinition(SeedType.SEED_EXPLODE_O_NUT).mSeedCost)
                report("packet_control_packet0Cost",
                    Plant.getPlantDefinition(theBoard.mSeedBank.mSeedPackets[0].mPacketType).mSeedCost)
            } finally {
                theBoard.mApp.mGameMode = aSavedMode
            }
        }

        /** Puts the zombie's own attack rectangle over the plant's rectangle. */
        private fun putZombieAttackRectOverPlant(theZombie: Zombie, thePlant: Plant) {
            val aAttackRect = theZombie.getZombieAttackRect()
            val aPlantRect = thePlant.getPlantRect()
            theZombie.mX += aPlantRect.mX + aPlantRect.mWidth / 2 - (aAttackRect.mX + aAttackRect.mWidth / 2)
            theZombie.mY += aPlantRect.mY + aPlantRect.mHeight / 2 - (aAttackRect.mY + aAttackRect.mHeight / 2)
        }

        /**
         * Puts the zombie's rectangle centred on the plant's rectangle - the placement the game's own
         * circle/rect test measures, so a blast centred on the plant really reaches this zombie.
         */
        private fun putZombieRectOnPlant(theZombie: Zombie, thePlant: Plant) {
            val aZombieRect = theZombie.getZombieRect()
            val aPlantRect = thePlant.getPlantRect()
            theZombie.mX += aPlantRect.mX + aPlantRect.mWidth / 2 - (aZombieRect.mX + aZombieRect.mWidth / 2)
            theZombie.mY += aPlantRect.mY + aPlantRect.mHeight / 2 - (aZombieRect.mY + aZombieRect.mHeight / 2)
        }

        /**
         * Puts the zombie's rectangle in the plant's attack rectangle, {@code theLeadPixels} pixels into it
         * from its left edge - the geometry the game's own target search measures
         * ({@code Board.getRectOverlap(attackRect, zombieRect)} and, for the zombie, whether it is on the
         * board), so a plant really fires at this zombie.
         */
        private fun putZombieInPlantAttackRect(theZombie: Zombie, thePlant: Plant, theWeapon: Int,
            theLeadPixels: Int) {
            val aAttackRect = thePlant.getPlantAttackRect(theWeapon)
            val aZombieRect = theZombie.getZombieRect()
            theZombie.mX += aAttackRect.mX + theLeadPixels - (aZombieRect.mX + aZombieRect.mWidth / 2)
            theZombie.mY += aAttackRect.mY + aAttackRect.mHeight / 2 - (aZombieRect.mY + aZombieRect.mHeight / 2)
        }

        // ---------------------------------------------------------------- phase 3f

        /** The lane the torchwood's pea lives in; the behaviour phase's subjects are dead by then. */
        private const val TORCHWOOD_ROW = 2

        /** The lane the control's pea lives in - the same shot with no torchwood in its way. */
        private const val TORCHWOOD_CONTROL_ROW = 4

        /** The lane the reference snow pea - one the game fired as a snow pea - lives in. */
        private const val TORCHWOOD_REFERENCE_ROW = 0

        /** The shooter on the left, the torchwood in the middle, the target to the right of both. */
        private const val TORCHWOOD_SHOOTER_COL = 0
        private const val TORCHWOOD_COL = 2
        private const val TORCHWOOD_TARGET_COL = 8

        /**
         * How far past the torchwood the target zombie is put: far enough that the shot meets the flame
         * first, close enough to stay well inside the board - a zombie past the board's width is one the
         * game refuses to let a projectile damage.
         */
        private const val TORCHWOOD_TARGET_LEAD = 60

        /** The seed both halves of a wave-budget pair start the game's own random stream from. */
        private const val WAVE_BUDGET_SEED = 95L

        /** How long a shot may take to reach the flame / the target before the observation gives up. */
        private const val TORCHWOOD_STEP_LIMIT = 600

        /** The off-screen canvas the drawn sprite is read back from, and where the sprite is put on it. */
        private const val ART_CANVAS = 160
        private const val ART_ORIGIN = 4

        /**
         * The sixth batch, observed on the same real board and through the game's own paths:
         *
         * <ol>
         *   <li><b>the torchwood's conversion</b> - a pea fired through a torchwood, read back as the
         *       projectile type it became, as the pixels the game's own {@code Projectile::Draw} puts on a
         *       canvas for it (next to the pixels of the same projectile before the flame, and of a snow pea
         *       the game fired as one), and as what its impact does to the zombie it lands on;</li>
         *   <li><b>the wave budget</b> - the zombies {@code Board::InitZombieWaves} writes into every wave
         *       of a level, once with the mod's factor registered and once with it taken away, so the ratio
         *       of the two is the factor's effect.</li>
         * </ol>
         *
         * <p>Every observation starts by marking what the earlier phases left on the board dead
         * ({@link #clearBoard(Board)}), so a rule's subjects cannot become another rule's target.
         */
        private fun torchwoodAndSpawnPhase(theBoard: Board?) {
            report("torchwood_boardPresent", theBoard != null)
            if (theBoard == null) {
                return
            }

            clearBoard(theBoard)

            report("torchwood_registeredConversions", joinedConversions(ProjectileOverrides.torchwoodConversions()))
            report("torchwood_gamePeaTarget", ProjectileOverrides.torchwoodTarget(ProjectileType.PROJECTILE_PEA,
                ProjectileType.PROJECTILE_FIREBALL))
            report("torchwood_gameSnowPeaTarget", ProjectileOverrides.torchwoodTarget(ProjectileType.PROJECTILE_SNOWPEA,
                ProjectileType.PROJECTILE_PEA))

            reportTorchwoodPea(theBoard)
            reportTorchwoodControl(theBoard)
            reportTorchwoodSnowPea(theBoard)
            reportSpawnBudget(theBoard)

            // The two materials the game ships, so the drawn sprite's summary above can be read against
            // them: these are the images of the projectile types in question, straight out of the pak.
            report("torchwood_imagePea", imagePixels(theBoard.mApp, Resources.IMAGE_PROJECTILEPEA_ID))
            report("torchwood_imageSnowPea", imagePixels(theBoard.mApp, Resources.IMAGE_PROJECTILESNOWPEA_ID))
        }

        /** The pea that crosses a torchwood: its type, its art and what its impact does. */
        private fun reportTorchwoodPea(theBoard: Board) {
            val aTarget = theBoard.addZombieInRow(ZombieType.ZOMBIE_NORMAL, TORCHWOOD_ROW, Zombie.ZOMBIE_WAVE_UI)
            val aShooter = theBoard.addPlant(TORCHWOOD_SHOOTER_COL, TORCHWOOD_ROW, SeedType.SEED_PEASHOOTER,
                SeedType.SEED_NONE)
            val aTorchwood = theBoard.addPlant(TORCHWOOD_COL, TORCHWOOD_ROW, SeedType.SEED_TORCHWOOD, SeedType.SEED_NONE)
            report("torchwood_subjectsReady", aTarget != null && aShooter != null && aTorchwood != null)
            if (aTarget == null || aShooter == null || aTorchwood == null) {
                return
            }

            val aBefore = liveProjectiles(theBoard)
            val aHealthBefore = aTarget.mBodyHealth
            aShooter.fire(aTarget, TORCHWOOD_ROW, PlantWeapon.WEAPON_PRIMARY)
            val aPea = onlyNewProjectile(theBoard, aBefore)
            report("torchwood_peaFired", aPea != null)
            if (aPea == null) {
                return
            }

            // The target goes just past the torchwood, in the lane the shot travels: the pea has to meet
            // the flame before it can meet the zombie (the harness's firing observations place their
            // targets the same way).
            aTarget.mX = aTorchwood.mX + TORCHWOOD_TARGET_LEAD

            report("torchwood_peaTypeBefore", aPea.mProjectileType)
            report("torchwood_peaPixelsBefore", projectilePixels(aPea))
            val aXBefore = aPea.mPosX.toInt()

            var aSteps = 0
            while (aPea.mProjectileType == ProjectileType.PROJECTILE_PEA && aSteps < TORCHWOOD_STEP_LIMIT) {
                aPea.update()
                aTorchwood.update()
                aSteps++
            }

            report("torchwood_conversionSteps", aSteps)
            report("torchwood_peaXBefore", aXBefore)
            report("torchwood_peaXAtConversion", aPea.mPosX.toInt())
            report("torchwood_convertedType", aPea.mProjectileType)
            report("torchwood_convertedTypeIsSnowPea", aPea.mProjectileType == ProjectileType.PROJECTILE_SNOWPEA)
            report("torchwood_convertedDamage", aPea.getProjectileDef().mDamage)
            report("torchwood_convertedPixels", projectilePixels(aPea))
            report("torchwood_convertedHasFlameReanim", attachedFirePea(aPea))

            var aHitSteps = 0
            while (!aPea.mDead && aTarget.mBodyHealth == aHealthBefore && aHitSteps < TORCHWOOD_STEP_LIMIT) {
                aPea.update()
                aTorchwood.update()
                aHitSteps++
            }

            report("torchwood_hitSteps", aHitSteps)
            report("torchwood_hitProjectileDead", aPea.mDead)
            report("torchwood_hitMotionType", aPea.mMotionType)
            report("torchwood_hitVelX", aPea.mVelX)
            report("torchwood_hitXAtEnd", aPea.mPosX.toInt())
            report("torchwood_hitTargetXAtEnd", aTarget.mX)
            report("torchwood_hitTargetRow", aTarget.mRow)
            report("torchwood_hitTargetDead", aTarget.mDead)
            report("torchwood_hitTargetCanBeHit", aTarget.effectedByDamage(aPea.mDamageRangeFlags))
            report("torchwood_hitDamage", aHealthBefore - aTarget.mBodyHealth)
            report("torchwood_hitChilledCounter", aTarget.mChilledCounter)
            report("torchwood_slowApplied", aTarget.mChilledCounter > 0)
        }

        /** The same shot with no torchwood in the lane: a plain pea, and a target that is not chilled. */
        private fun reportTorchwoodControl(theBoard: Board) {
            val aTarget = theBoard.addZombieInRow(ZombieType.ZOMBIE_NORMAL, TORCHWOOD_CONTROL_ROW, Zombie.ZOMBIE_WAVE_UI)
            val aShooter = theBoard.addPlant(TORCHWOOD_SHOOTER_COL, TORCHWOOD_CONTROL_ROW, SeedType.SEED_PEASHOOTER,
                SeedType.SEED_NONE)

            report("control_torchwood_subjectsReady", aTarget != null && aShooter != null)
            if (aTarget == null || aShooter == null) {
                return
            }

            val aBefore = liveProjectiles(theBoard)
            val aHealthBefore = aTarget.mBodyHealth
            aShooter.fire(aTarget, TORCHWOOD_CONTROL_ROW, PlantWeapon.WEAPON_PRIMARY)
            val aPea = onlyNewProjectile(theBoard, aBefore)
            report("control_torchwood_peaFired", aPea != null)
            if (aPea == null) {
                return
            }

            aTarget.mX = theBoard.gridToPixelX(TORCHWOOD_COL, TORCHWOOD_CONTROL_ROW) + TORCHWOOD_TARGET_LEAD

            report("control_torchwood_peaPixels", projectilePixels(aPea))

            var aHitSteps = 0
            while (!aPea.mDead && aTarget.mBodyHealth == aHealthBefore && aHitSteps < TORCHWOOD_STEP_LIMIT) {
                aPea.update()
                aHitSteps++
            }

            report("control_torchwood_peaType", aPea.mProjectileType)
            report("control_torchwood_motionType", aPea.mMotionType)
            report("control_torchwood_xAtEnd", aPea.mPosX.toInt())
            report("control_torchwood_targetXAtEnd", aTarget.mX)
            report("control_torchwood_targetRow", aTarget.mRow)
            report("control_torchwood_targetDead", aTarget.mDead)
            report("control_torchwood_hitDamage", aHealthBefore - aTarget.mBodyHealth)
            report("control_torchwood_chilledCounter", aTarget.mChilledCounter)
            report("control_torchwood_slowApplied", aTarget.mChilledCounter > 0)
        }

        /**
         * The other two rows of the table: a snow pea the game fired as one (the reference the converted
         * pea's pixels are compared against), the snow pea that crosses a torchwood, and a fireball that
         * crosses one - which the game's own loop never converts.
         */
        private fun reportTorchwoodSnowPea(theBoard: Board) {
            val aReferenceTarget = theBoard.addZombieInRow(ZombieType.ZOMBIE_NORMAL, TORCHWOOD_REFERENCE_ROW,
                Zombie.ZOMBIE_WAVE_UI)
            val aReferenceShooter = theBoard.addPlant(TORCHWOOD_SHOOTER_COL, TORCHWOOD_REFERENCE_ROW,
                SeedType.SEED_SNOWPEA, SeedType.SEED_NONE)
            report("torchwood_referenceReady", aReferenceTarget != null && aReferenceShooter != null)
            if (aReferenceTarget == null || aReferenceShooter == null) {
                return
            }

            val aBeforeReference = liveProjectiles(theBoard)
            aReferenceShooter.fire(aReferenceTarget, TORCHWOOD_REFERENCE_ROW, PlantWeapon.WEAPON_PRIMARY)
            val aReference = onlyNewProjectile(theBoard, aBeforeReference)
            report("torchwood_referenceSnowPeaFired", aReference != null)
            if (aReference != null) {
                report("torchwood_referenceSnowPeaType", aReference.mProjectileType)
                report("torchwood_referenceSnowPeaPixels", projectilePixels(aReference))
            }

            // The snow pea that crosses a torchwood: the game turns it into a fireball (the same shape the
            // retail game's pea gets) and attaches the flame.
            val aTarget = theBoard.addZombieInRow(ZombieType.ZOMBIE_NORMAL, TORCHWOOD_ROW, Zombie.ZOMBIE_WAVE_UI)
            val aShooter = theBoard.addPlant(TORCHWOOD_SHOOTER_COL, TORCHWOOD_ROW, SeedType.SEED_SNOWPEA, SeedType.SEED_NONE)
            val aTorchwood = theBoard.addPlant(TORCHWOOD_COL, TORCHWOOD_ROW, SeedType.SEED_TORCHWOOD, SeedType.SEED_NONE)
            report("torchwood_snowPeaSubjectsReady", aTarget != null && aShooter != null && aTorchwood != null)
            if (aTarget == null || aShooter == null || aTorchwood == null) {
                return
            }

            val aBefore = liveProjectiles(theBoard)
            val aHealthBefore = aTarget.mBodyHealth
            aShooter.fire(aTarget, TORCHWOOD_ROW, PlantWeapon.WEAPON_PRIMARY)
            val aSnowPea = onlyNewProjectile(theBoard, aBefore)
            report("torchwood_snowPeaFired", aSnowPea != null)
            if (aSnowPea == null) {
                return
            }

            aTarget.mX = aTorchwood.mX + TORCHWOOD_TARGET_LEAD

            var aSteps = 0
            while (aSnowPea.mProjectileType == ProjectileType.PROJECTILE_SNOWPEA && aSteps < TORCHWOOD_STEP_LIMIT) {
                aSnowPea.update()
                aTorchwood.update()
                aSteps++
            }

            report("torchwood_snowPeaSteps", aSteps)
            report("torchwood_snowPeaBecomes", aSnowPea.mProjectileType)
            report("torchwood_snowPeaDamage", aSnowPea.getProjectileDef().mDamage)
            report("torchwood_snowPeaFlameReanim", attachedFirePea(aSnowPea))

            var aHitSteps = 0
            while (!aSnowPea.mDead && aTarget.mBodyHealth == aHealthBefore && aHitSteps < TORCHWOOD_STEP_LIMIT) {
                aSnowPea.update()
                aTorchwood.update()
                aHitSteps++
            }

            report("torchwood_snowPeaHitDamage", aHealthBefore - aTarget.mBodyHealth)

            // A fireball crossing a torchwood: the game's own loop only ever looks at a pea and a snow pea,
            // so the table is never asked and the fireball is left alone.
            val aFireball = theBoard.addProjectile(TORCHWOOD_SHOOTER_COL, TORCHWOOD_ROW, TORCHWOOD_ROW,
                TORCHWOOD_ROW, ProjectileType.PROJECTILE_FIREBALL)
            report("torchwood_fireballPresent", aFireball != null)
            if (aFireball == null) {
                return
            }

            aFireball.mX = aTorchwood.mX
            aFireball.mPosX = aTorchwood.mX.toFloat()
            for (i in 0 until 10) {
                aTorchwood.update()
            }

            report("torchwood_fireballTypeAfterTorchwood", aFireball.mProjectileType)
            report("torchwood_fireballStaysFireball", aFireball.mProjectileType == ProjectileType.PROJECTILE_FIREBALL)
        }

        /**
         * The wave budget of a level, measured through the game's own {@code Board::InitZombieWaves}: every
         * wave's zombie count with the mod's factor registered and with it taken away, on the 1-1 the sixth
         * batch is about and on three modes whose own factor the game picks differently.
         */
        private fun reportSpawnBudget(theBoard: Board) {
            // The factor the mod registered, read before anything here touches the knob: both halves of
            // every pair below use it, so a run without the registration measures the game's own factor
            // against itself.
            val aRegistered = BoardOverrides.spawnMultiplier()
            report("spawn_multiplierRegistered", aRegistered)
            report("spawn_multiplierRegisteredIsFour", aRegistered == 4)

            reportSpawnBudgetMode(theBoard, "adventureLevel1", GameMode.GAMEMODE_ADVENTURE, aRegistered)
            reportSpawnBudgetMode(theBoard, "beghouled", GameMode.GAMEMODE_CHALLENGE_BEGHOULED, aRegistered)
            reportSpawnBudgetMode(theBoard, "littleTrouble", GameMode.GAMEMODE_CHALLENGE_LITTLE_TROUBLE, aRegistered)
            reportSpawnBudgetMode(theBoard, "column", GameMode.GAMEMODE_CHALLENGE_COLUMN, aRegistered)
        }

        /** One mode's wave budget, once the way the game composes it and once the way the mod does. */
        private fun reportSpawnBudgetMode(theBoard: Board, theLabel: String, theGameMode: Int, theMultiplier: Int) {
            val aSavedMode = theBoard.mApp.mGameMode
            try {
                theBoard.mApp.mGameMode = theGameMode

                // The composition draws from the game's own stream, which advances with every pick: both
                // halves of the pair start it at the same seed, so the only thing that differs between them
                // is the factor.
                Common.sRand(WAVE_BUDGET_SEED)
                BoardOverrides.spawnMultiplier(BoardOverrides.DEFAULT_SPAWN_MULTIPLIER)
                theBoard.initZombieWaves()
                val aRetail = waveZombieCounts(theBoard)
                val aRetailTotal = totalOf(aRetail)

                Common.sRand(WAVE_BUDGET_SEED)
                BoardOverrides.spawnMultiplier(theMultiplier)
                theBoard.initZombieWaves()
                val aRuled = waveZombieCounts(theBoard)
                val aRuledTotal = totalOf(aRuled)

                report("spawn_" + theLabel + "_waves", theBoard.mNumWaves)
                report("spawn_" + theLabel + "_retailCounts", joinedWaveCounts(aRetail))
                report("spawn_" + theLabel + "_ruledCounts", joinedWaveCounts(aRuled))
                report("spawn_" + theLabel + "_retailTotal", aRetailTotal)
                report("spawn_" + theLabel + "_ruledTotal", aRuledTotal)
                report("spawn_" + theLabel + "_ratio", if (aRetailTotal == 0) "-"
                    else String.format(Locale.ROOT, "%.4f", aRuledTotal / aRetailTotal.toDouble()))
                // The final wave of an adventure level is the one the game tops up with the level's own
                // zombie types (a count the factor does not scale), so the waves before it are the ones
                // that show the factor alone.
                val aRetailBody = totalOf(aRetail, aRetail.size - 1)
                val aRuledBody = totalOf(aRuled, aRuled.size - 1)
                report("spawn_" + theLabel + "_bodyRatio", if (aRetailBody == 0) "-"
                    else String.format(Locale.ROOT, "%.4f", aRuledBody / aRetailBody.toDouble()))
            } finally {
                theBoard.mApp.mGameMode = aSavedMode
                BoardOverrides.spawnMultiplier(theMultiplier)
            }
        }

        /** How many zombies the game wrote into each wave of the level the board just picked. */
        private fun waveZombieCounts(theBoard: Board): IntArray {
            val aCounts = IntArray(theBoard.mNumWaves)
            for (aWave in 0 until theBoard.mNumWaves) {
                var aCount = 0
                for (aIndex in 0 until Board.MAX_ZOMBIES_IN_WAVE) {
                    if (theBoard.mZombiesInWave[aWave][aIndex] == ZombieType.ZOMBIE_INVALID) {
                        break
                    }
                    aCount++
                }
                aCounts[aWave] = aCount
            }
            return aCounts
        }

        /** The per-wave zombie counts as one comma-separated line. */
        private fun joinedWaveCounts(theCounts: IntArray): String {
            val aText = StringBuilder()
            for (aCount in theCounts) {
                if (aText.isNotEmpty()) {
                    aText.append(',')
                }
                aText.append(aCount)
            }
            return aText.toString()
        }

        /** The first {@code theCount} waves' zombies summed. */
        private fun totalOf(theCounts: IntArray, theCount: Int): Int {
            var aTotal = 0
            for (i in 0 until minOf(theCount, theCounts.size)) {
                aTotal += theCounts[i]
            }
            return aTotal
        }

        private fun totalOf(theCounts: IntArray): Int {
            var aTotal = 0
            for (aCount in theCounts) {
                aTotal += aCount
            }
            return aTotal
        }

        /** The last entry of a per-wave array, i.e. what the array's subject was worth at the last wave. */
        private fun lastOf(theValues: IntArray): Int {
            return if (theValues.isEmpty()) 0 else theValues[theValues.size - 1]
        }

        /** The flame reanimation attached to a projectile, or -1 when what is attached is not one. */
        private fun attachedFirePea(theProjectile: Projectile): Int {
            val aReanim = Attachment.findReanimAttachment(theProjectile.mAttachmentID)
            if (aReanim == null) {
                return -1
            }
            return if (aReanim.mReanimationType == ReanimationType.REANIM_FIRE_PEA) ReanimationType.REANIM_FIRE_PEA
            else aReanim.mReanimationType
        }

        /**
         * The pixels the game's own {@code Projectile::Draw} puts on a canvas for this projectile: the draw
         * call the board makes, into a CPU-backed image instead of the screen, and the drawn pixels
         * summarised. The summary is therefore the material the game would show, not the projectile's type.
         */
        private fun projectilePixels(theProjectile: Projectile): String {
            val aSavedX = theProjectile.mX
            val aSavedY = theProjectile.mY
            val aSavedFrame = theProjectile.mFrame
            val aCanvas = MemoryImage(theProjectile.mApp)
            aCanvas.create(ART_CANVAS, ART_CANVAS)

            try {
                theProjectile.mX = ART_ORIGIN
                theProjectile.mY = ART_ORIGIN
                theProjectile.mFrame = 0
                aCanvas.getBits()

                val aGraphics = aCanvas.getGraphics()
                if (theProjectile.beginDraw(aGraphics)) {
                    theProjectile.draw(aGraphics)
                    theProjectile.endDraw(aGraphics)
                }

                return pixelSignature(aCanvas.getBits())
            } catch (aThrowable: Throwable) {
                return "error:" + aThrowable
            } finally {
                theProjectile.mX = aSavedX
                theProjectile.mY = aSavedY
                theProjectile.mFrame = aSavedFrame
            }
        }

        /** A 0xAARRGGBB buffer summarised as how much was drawn, in which family, and a checksum. */
        private fun pixelSignature(theBits: IntArray?): String {
            if (theBits == null) {
                return "none"
            }

            var aOpaque = 0
            var aGreen = 0
            var aBlue = 0
            val aFull = StringBuilder()
            for (i in theBits.indices) {
                if (i + 1 >= theBits.size) {
                    break
                }
                val aPixel = theBits[i]
                if ((aPixel ushr 24) == 0) {
                    continue
                }

                aOpaque++
                val aRedPart = (aPixel shr 16) and 0xff
                val aGreenPart = (aPixel shr 8) and 0xff
                val aBluePart = aPixel and 0xff
                if (aGreenPart > aRedPart && aGreenPart > aBluePart) {
                    aGreen++
                }
                if (aBluePart > aRedPart && aBluePart > aGreenPart) {
                    aBlue++
                }
                aFull.append(Integer.toHexString(aPixel)).append('@').append(i).append(';')
            }

            return "opaque=" + aOpaque + ",green=" + aGreen + ",blue=" + aBlue + ",crc=" +
                Integer.toHexString(aFull.toString().hashCode())
        }

        /** The live projectiles of the board, by identity, so the one a shot added can be told apart. */
        private fun liveProjectiles(theBoard: Board): Set<Projectile> {
            val aSet = HashSet<Projectile>()
            for (aProjectile in theBoard.mProjectiles) {
                if (!aProjectile.mDead) {
                    aSet.add(aProjectile)
                }
            }
            return aSet
        }

        /**
         * The projectile a shot added. The board's projectile pool hands freed slots out again, so a shot
         * fired after an earlier phase's can land on an object of the same type: the identity of what was
         * alive before the shot is what tells the new one from the old.
         */
        private fun onlyNewProjectile(theBoard: Board, theBefore: Set<Projectile>): Projectile? {
            var aFound: Projectile? = null
            for (aProjectile in theBoard.mProjectiles) {
                if (!aProjectile.mDead && !theBefore.contains(aProjectile)) {
                    aFound = aProjectile
                }
            }
            return aFound
        }

        /** A loaded image's own pixels, so the two materials the game ships can be told apart. */
        private fun imagePixels(theApp: LawnApp, theImageId: Int): String {
            val aImage = theApp.getImage(theImageId)
            if (aImage !is MemoryImage) {
                return "<not a memory image>"
            }

            return aImage.getWidth().toString() + "x" + aImage.getHeight() + " " + pixelSignature((aImage as MemoryImage).getBits())
        }

        /** The registered torchwood conversions as {@code from->to} pairs, in registration order. */
        private fun joinedConversions(theConversions: Map<Int, Int>): String {
            val aText = StringBuilder()
            for ((aEntryKey, aEntryValue) in theConversions) {
                if (aText.isNotEmpty()) {
                    aText.append(',')
                }
                aText.append(aEntryKey).append("->").append(aEntryValue)
            }
            return aText.toString()
        }

        // ---------------------------------------------------------------- phase 3f

        /** The adventure level whose wave table gives two flag waves: 1-9 is the first 20-wave level. */
        private const val GRAVE_LEVEL = 9

        /** The flag wave of that level that is not its last wave (the other one is wave 19). */
        private const val GRAVE_FLAG_WAVE = 9

        /** The whack-a-zombie adventure level (2-5), which the game's own level test knows. */
        private const val WHACK_A_ZOMBIE_LEVEL = 15

        /** How many ticks a grave stone's own growth takes ({@code mGridItemCounter} up to 100). */
        private const val GRAVE_GROW_TICKS = 150

        /** How often the grave stones' cell bound is sampled - more placements than any lawn has cells. */
        private const val GRAVE_BOUND_PLACEMENTS = 40

        /**
         * The seventh batch's rules, on the game's own path: the grave branch of
         * {@code Challenge::SpawnZombieWave} driven wave by wave on a real board (the adventure level 1-9,
         * whose 20 waves give two flag waves), what the branch does with the level's own grave stones, and
         * {@code Challenge::WhackAZombieSpawning} driven through its own wave block.
         *
         * <p>Every measurement is taken twice where the rule is a condition: once with the mod's
         * registration and once with it taken back ({@code BoardOverrides} hands the game's own answer back
         * when a knob is put on the default), so the report carries the ruled and the retail values of the
         * same board - which is also what the counter-proofs of
         * {@code ../verification/VERIFICATION.md} §64.5 turn on.
         */
        private fun gravePhase(theBoard: Board) {
            val aRegisteredEnabled = BoardOverrides.graveModeEnabled()
            val aRegisteredThreshold = BoardOverrides.graveStoneWaveThreshold()
            val aRegisteredWhackCount = BoardOverrides.whackAZombieZombieCount()

            report("grave_modeEnabled", aRegisteredEnabled)
            report("grave_waveThreshold", aRegisteredThreshold)
            report("grave_whackAZombieCount", aRegisteredWhackCount)
            report("grave_modeBackgrounds", joinedModeBackgrounds())

            try {
                reportGraveStoneGrowth(theBoard, aRegisteredEnabled, aRegisteredThreshold)
                reportGraveStoneBounds(theBoard)
                reportFlagWaveGraveZombies(theBoard, aRegisteredEnabled)
                reportWhackAZombieWaves(theBoard, aRegisteredWhackCount)
                reportModeBackgrounds(theBoard)
            } finally {
                BoardOverrides.graveModeEnabled(aRegisteredEnabled)
                BoardOverrides.graveStoneWaveThreshold(aRegisteredThreshold)
                BoardOverrides.whackAZombieZombieCount(aRegisteredWhackCount)
            }
        }

        /**
         * The growth rule: a 20-wave level grows one grave stone per wave after its first flag wave - nine
         * of them between the two flag waves, none before, none on a flag wave and none on the last wave -
         * and none at all when the grave mode is taken back (the retail rule raises them only in Grave
         * Danger levels).
         *
         * <p>The threshold is measured the same way: with the game's own 5 the same level starts growing at
         * wave 6 instead of wave 10.
         */
        private fun reportGraveStoneGrowth(theBoard: Board, theRegisteredEnabled: Boolean,
            theRegisteredThreshold: Int) {
            val aSavedMode = theBoard.mApp.mGameMode
            val aSavedLevel = theBoard.mLevel
            try {
                theBoard.mApp.mGameMode = GameMode.GAMEMODE_ADVENTURE
                theBoard.mLevel = GRAVE_LEVEL
                clearBoard(theBoard)
                clearGridItems(theBoard)
                // The lawn of that level (a level-1 board is the tutorial's, whose first rows are dirt).
                resetGridSquares(theBoard)
                theBoard.pickBackground()
                report("grave_background", theBoard.mBackground)
                // The level's own waves, so the flag waves are the game's own (9 and 19 of 20).
                theBoard.initZombieWaves()
                report("grave_levelWaves", theBoard.mNumWaves)
                report("grave_flagWaves", joinedFlagWaves(theBoard))

                val aRuled = driveGraveWaves(theBoard)
                report("grave_growthCounts", joinedWaveCounts(aRuled.mGraveStones))
                report("grave_growthStonesAtEnd", lastOf(aRuled.mGraveStones))
                report("grave_growthRiseCounts", joinedWaveCounts(aRuled.mRisenZombies))
                report("grave_growthRiseTotal", totalOf(aRuled.mRisenZombies))
                report("grave_growthColumns", graveColumnRange(theBoard))
                report("grave_growthOutsideRightFiveColumns", gravesOutsideColumns(theBoard, 4, 8))

                // The control: the same twenty waves with the grave mode taken back - the retail rule.
                clearBoard(theBoard)
                clearGridItems(theBoard)
                BoardOverrides.graveModeEnabled(false)
                val aRetail = driveGraveWaves(theBoard)
                report("grave_control_growthCounts", joinedWaveCounts(aRetail.mGraveStones))
                report("grave_control_growthStonesAtEnd", lastOf(aRetail.mGraveStones))
                report("grave_control_growthRiseTotal", totalOf(aRetail.mRisenZombies))
                BoardOverrides.graveModeEnabled(theRegisteredEnabled)

                // ... and the threshold itself, on the game's own value of it.
                clearBoard(theBoard)
                clearGridItems(theBoard)
                BoardOverrides.graveStoneWaveThreshold(BoardOverrides.DEFAULT_GRAVE_STONE_WAVE_THRESHOLD)
                val aEarly = driveGraveWaves(theBoard)
                report("grave_threshold5_growthCounts", joinedWaveCounts(aEarly.mGraveStones))
                report("grave_threshold5_growthStonesAtEnd", lastOf(aEarly.mGraveStones))
                BoardOverrides.graveStoneWaveThreshold(theRegisteredThreshold)
                report("grave_threshold_restored", BoardOverrides.graveStoneWaveThreshold())
            } finally {
                BoardOverrides.graveModeEnabled(theRegisteredEnabled)
                BoardOverrides.graveStoneWaveThreshold(theRegisteredThreshold)
                theBoard.mApp.mGameMode = aSavedMode
                theBoard.mLevel = aSavedLevel
            }
        }

        /** One level's wave-by-wave grave run: the grave stones on the board and the zombies that rose. */
        private class GraveRun(theWaves: Int) {
            val mGraveStones = IntArray(theWaves)
            val mRisenZombies = IntArray(theWaves)
        }

        /**
         * Drives {@code Challenge::SpawnZombieWave} once per wave of the level the board carries, which is
         * what the game's own wave spawner does (the port's {@code BoardWaves::SpawnZombieWave} calls it
         * first thing, with {@code mCurrentWave} already advanced to the wave being spawned). The grave
         * stones left on the board and the zombies that rose out of one are read after each wave.
         */
        private fun driveGraveWaves(theBoard: Board): GraveRun {
            val aRun = GraveRun(theBoard.mNumWaves)
            for (aWave in 0 until theBoard.mNumWaves) {
                theBoard.mCurrentWave = aWave
                val aBefore = zombiesSnapshot(theBoard)
                theBoard.mChallenge.spawnZombieWave()
                aRun.mGraveStones[aWave] = theBoard.getGraveStonesCount()
                aRun.mRisenZombies[aWave] = newZombies(theBoard, aBefore).size
            }
            return aRun
        }

        /**
         * The cell bound of the grave stones: the game's own placement is sampled far more often than any
         * lawn has cells, so what is left is the bound itself - 25 cells on a day lawn (five columns, five
         * rows), 20 on a pool lawn (the two water rows are not grave-able) - and every stone is in the five
         * right-hand columns, never in the left four and never in the water.
         */
        private fun reportGraveStoneBounds(theBoard: Board) {
            val aSavedMode = theBoard.mApp.mGameMode
            try {
                clearBoard(theBoard)
                clearGridItems(theBoard)
                theBoard.mApp.mGameMode = GameMode.GAMEMODE_ADVENTURE
                theBoard.mLevel = GRAVE_LEVEL
                resetGridSquares(theBoard)
                theBoard.pickBackground()
                report("graveBounds_dayBackground", theBoard.mBackground)
                placeGraveStones(theBoard, GRAVE_BOUND_PLACEMENTS)
                report("graveBounds_dayCount", theBoard.getGraveStonesCount())
                report("graveBounds_dayColumns", graveColumnRange(theBoard))
                report("graveBounds_dayRows", graveRowRange(theBoard))

                clearBoard(theBoard)
                clearGridItems(theBoard)
                // A pool lawn: the game's own background table gives the pool to survival stage 3.
                theBoard.mApp.mGameMode = GameMode.GAMEMODE_SURVIVAL_NORMAL_STAGE_3
                resetGridSquares(theBoard)
                theBoard.pickBackground()
                report("graveBounds_poolBackground", theBoard.mBackground)
                placeGraveStones(theBoard, GRAVE_BOUND_PLACEMENTS)
                report("graveBounds_poolCount", theBoard.getGraveStonesCount())
                report("graveBounds_poolColumns", graveColumnRange(theBoard))
                report("graveBounds_poolRows", graveRowRange(theBoard))
                report("graveBounds_poolWaterStones", graveStonesInPoolRows(theBoard))
            } finally {
                theBoard.mApp.mGameMode = aSavedMode
            }
        }

        /**
         * The flag wave's rise: every grave stone that has finished growing rises one zombie, of a type out
         * of the game's own three (normal, cone, pail); a War and Peas level rises none even with the
         * stones there; a pool level rises its own trio and a roof level its own three instead (the two
         * "trio" rules, which the grave mode moves from the last flag wave to every flag wave).
         */
        private fun reportFlagWaveGraveZombies(theBoard: Board, theRegisteredEnabled: Boolean) {
            val aSavedMode = theBoard.mApp.mGameMode
            val aSavedLevel = theBoard.mLevel
            try {
                theBoard.mApp.mGameMode = GameMode.GAMEMODE_ADVENTURE
                theBoard.mLevel = GRAVE_LEVEL
                clearBoard(theBoard)
                clearGridItems(theBoard)
                resetGridSquares(theBoard)
                theBoard.pickBackground()
                theBoard.mNumWaves = 20
                theBoard.mCurrentWave = GRAVE_FLAG_WAVE

                placeGrownGraveStones(theBoard, 3)
                report("graveRise_graves", theBoard.getGraveStonesCount())
                report("graveRise_grownStones", grownGraveStones(theBoard))
                var aRisen = riseOnTheFlagWave(theBoard)
                report("graveRise_risen", aRisen.size)
                report("graveRise_types", joinedZombieTypes(aRisen))
                report("graveRise_onGraveCells", zombiesOnGraveCells(theBoard, aRisen))
                report("graveRise_phaseRisingFromGrave", zombiesRisingFromGrave(aRisen))

                // The control: the same flag wave on the same board with the grave mode taken back.
                clearBoard(theBoard)
                clearGridItems(theBoard)
                BoardOverrides.graveModeEnabled(false)
                placeGrownGraveStones(theBoard, 3)
                report("graveRise_control_risen", riseOnTheFlagWave(theBoard).size)
                BoardOverrides.graveModeEnabled(theRegisteredEnabled)

                // War and Peas: the game's own exclusion, zombies grown on plants and no grave zombies.
                clearBoard(theBoard)
                clearGridItems(theBoard)
                theBoard.mApp.mGameMode = GameMode.GAMEMODE_CHALLENGE_WAR_AND_PEAS
                resetGridSquares(theBoard)
                theBoard.pickBackground()
                theBoard.mCurrentWave = GRAVE_FLAG_WAVE
                placeGrownGraveStones(theBoard, 3)
                report("graveZomBotany_graves", theBoard.getGraveStonesCount())
                report("graveZomBotany_risen", riseOnTheFlagWave(theBoard).size)

                // A pool level: no zombie rises out of a grave stone, the level's own trio does instead.
                clearBoard(theBoard)
                clearGridItems(theBoard)
                theBoard.mApp.mGameMode = GameMode.GAMEMODE_SURVIVAL_NORMAL_STAGE_3
                resetGridSquares(theBoard)
                theBoard.pickBackground()
                theBoard.mCurrentWave = GRAVE_FLAG_WAVE
                placeGrownGraveStones(theBoard, 3)
                report("gravePool_background", theBoard.mBackground)
                report("gravePool_graves", theBoard.getGraveStonesCount())
                aRisen = riseOnTheFlagWave(theBoard)
                report("gravePool_risen", aRisen.size)
                report("gravePool_types", joinedZombieTypes(aRisen))
                report("gravePool_onGraveCells", zombiesOnGraveCells(theBoard, aRisen))

                // The control for the two trios: with the grave mode taken back, a pool level's flag wave
                // is not a Grave Danger wave and rises nothing at all.
                clearBoard(theBoard)
                clearGridItems(theBoard)
                BoardOverrides.graveModeEnabled(false)
                placeGrownGraveStones(theBoard, 3)
                report("gravePool_control_risen", riseOnTheFlagWave(theBoard).size)
                BoardOverrides.graveModeEnabled(theRegisteredEnabled)

                // A roof level: the sky trio, likewise on every flag wave.
                clearBoard(theBoard)
                clearGridItems(theBoard)
                theBoard.mApp.mGameMode = GameMode.GAMEMODE_CHALLENGE_HIGH_GRAVITY
                resetGridSquares(theBoard)
                theBoard.pickBackground()
                theBoard.mCurrentWave = GRAVE_FLAG_WAVE
                report("graveRoof_background", theBoard.mBackground)
                aRisen = riseOnTheFlagWave(theBoard)
                report("graveRoof_risen", aRisen.size)
                report("graveRoof_types", joinedZombieTypes(aRisen))
                report("graveRoof_bungees", zombiesOfType(aRisen, ZombieType.ZOMBIE_BUNGEE))
            } finally {
                BoardOverrides.graveModeEnabled(theRegisteredEnabled)
                theBoard.mApp.mGameMode = aSavedMode
                theBoard.mLevel = aSavedLevel
            }
        }

        /** Drives one flag wave and answers the zombies that appeared with it. */
        private fun riseOnTheFlagWave(theBoard: Board): List<Zombie> {
            val aBefore = zombiesSnapshot(theBoard)
            theBoard.mChallenge.spawnZombieWave()
            return newZombies(theBoard, aBefore)
        }

        /**
         * The two mode backgrounds, measured where the game reads them: {@code Board::PickBackground}'s
         * table fills the background, so the background the board carries after the call is the answer -
         * and with it the plantable rows the method derives from it (a pool level's two water rows; the
         * air raid's day lawn has none). Both are measured with the mod's registration and with the game's
         * own cell put back, and a mode nobody registered is the control.
         */
        private fun reportModeBackgrounds(theBoard: Board) {
            val aSavedMode = theBoard.mApp.mGameMode
            try {
                theBoard.mApp.mGameMode = GameMode.GAMEMODE_CHALLENGE_AIR_RAID
                resetGridSquares(theBoard)
                theBoard.pickBackground()
                report("modeBackground_airRaid_ruled", theBoard.mBackground)
                report("modeBackground_airRaid_waterRows", poolRows(theBoard))

                theBoard.mApp.mGameMode = GameMode.GAMEMODE_CHALLENGE_ICE
                resetGridSquares(theBoard)
                theBoard.pickBackground()
                report("modeBackground_ice_ruled", theBoard.mBackground)
                report("modeBackground_ice_waterRows", poolRows(theBoard))

                // The game's own cells put back: the air raid is a fog night and the ice level a day lawn.
                BoardOverrides.modeBackground(GameMode.GAMEMODE_CHALLENGE_AIR_RAID, BackgroundType.BACKGROUND_4_FOG)
                BoardOverrides.modeBackground(GameMode.GAMEMODE_CHALLENGE_ICE, BackgroundType.BACKGROUND_1_DAY)

                theBoard.mApp.mGameMode = GameMode.GAMEMODE_CHALLENGE_AIR_RAID
                resetGridSquares(theBoard)
                theBoard.pickBackground()
                report("modeBackground_airRaid_retail", theBoard.mBackground)

                theBoard.mApp.mGameMode = GameMode.GAMEMODE_CHALLENGE_ICE
                resetGridSquares(theBoard)
                theBoard.pickBackground()
                report("modeBackground_ice_retail", theBoard.mBackground)
                report("modeBackground_ice_retailWaterRows", poolRows(theBoard))

                // The control: a mode nobody registered keeps the background the game's own table gives it.
                theBoard.mApp.mGameMode = GameMode.GAMEMODE_CHALLENGE_RAINING_SEEDS
                resetGridSquares(theBoard)
                theBoard.pickBackground()
                report("modeBackground_control_rainingSeeds", theBoard.mBackground)

                BoardOverrides.modeBackground(GameMode.GAMEMODE_CHALLENGE_AIR_RAID, BackgroundType.BACKGROUND_1_DAY)
                BoardOverrides.modeBackground(GameMode.GAMEMODE_CHALLENGE_ICE, BackgroundType.BACKGROUND_3_POOL)
                report("modeBackground_restored", joinedModeBackgrounds())
            } finally {
                BoardOverrides.modeBackground(GameMode.GAMEMODE_CHALLENGE_AIR_RAID, BackgroundType.BACKGROUND_1_DAY)
                BoardOverrides.modeBackground(GameMode.GAMEMODE_CHALLENGE_ICE, BackgroundType.BACKGROUND_3_POOL)
                theBoard.mApp.mGameMode = aSavedMode
            }
        }

        /** How many of the board's rows are water, i.e. whether the background really is a pool lawn. */
        private fun poolRows(theBoard: Board): Int {
            var aRows = 0
            for (aRow in 0 until Board.MAX_GRID_SIZE_Y) {
                if (theBoard.mPlantRow[aRow] == PlantRowType.PLANTROW_POOL) {
                    aRows++
                }
            }
            return aRows
        }

        /**
         * The whack-a-zombie wave: {@code Challenge::WhackAZombieSpawning}'s own spawn block, driven at the
         * wave whose phase has no double/triple chance yet (so what is measured is the base count alone),
         * with four grown grave stones to pick from. The mod's count rises two zombies out of two different
         * stones; the game's own count rises one.
         */
        private fun reportWhackAZombieWaves(theBoard: Board, theRegisteredCount: Int) {
            val aSavedMode = theBoard.mApp.mGameMode
            val aSavedLevel = theBoard.mLevel
            try {
                theBoard.mApp.mGameMode = GameMode.GAMEMODE_ADVENTURE
                theBoard.mLevel = WHACK_A_ZOMBIE_LEVEL
                clearBoard(theBoard)
                clearGridItems(theBoard)
                resetGridSquares(theBoard)
                theBoard.pickBackground()
                theBoard.mNumWaves = 20
                theBoard.mCurrentWave = 1
                placeGraveStones(theBoard, 4)
                report("whackAZombie_graves", theBoard.getGraveStonesCount())

                val aRuled = driveWhackAZombieSpawn(theBoard)
                report("whackAZombie_risen", aRuled.size)
                report("whackAZombie_types", joinedZombieTypes(aRuled))
                report("whackAZombie_gravesUsed", graveCellsUsed(theBoard, aRuled))

                // The control: the game's own base count, on the same board.
                clearBoard(theBoard)
                clearGridItems(theBoard)
                BoardOverrides.whackAZombieZombieCount(BoardOverrides.DEFAULT_WHACK_A_ZOMBIE_ZOMBIE_COUNT)
                placeGraveStones(theBoard, 4)
                val aRetail = driveWhackAZombieSpawn(theBoard)
                report("whackAZombie_control_risen", aRetail.size)
                report("whackAZombie_control_gravesUsed", graveCellsUsed(theBoard, aRetail))
                BoardOverrides.whackAZombieZombieCount(theRegisteredCount)
                report("whackAZombie_restored", BoardOverrides.whackAZombieZombieCount())
            } finally {
                BoardOverrides.whackAZombieZombieCount(theRegisteredCount)
                theBoard.mApp.mGameMode = aSavedMode
                theBoard.mLevel = aSavedLevel
            }
        }

        /**
         * One {@code Challenge::WhackAZombieSpawning} step that reaches its spawn block: a countdown above
         * the 300 the method returns below and a state counter of one, which is the state the method's own
         * wave loop leaves behind between two spawns.
         */
        private fun driveWhackAZombieSpawn(theBoard: Board): List<Zombie> {
            theBoard.mZombieCountDown = 1000
            theBoard.mChallenge.mChallengeStateCounter = 1
            val aBefore = zombiesSnapshot(theBoard)
            theBoard.mChallenge.whackAZombieSpawning()
            return newZombies(theBoard, aBefore)
        }

        // ---------------------------------------------------------------- grave helpers

        /** The live grave stones on the board. */
        private fun graveStones(theBoard: Board): List<GridItem> {
            val aStones = ArrayList<GridItem>()
            for (aGridItem in theBoard.mGridItems) {
                if (!aGridItem.mDead && aGridItem.mGridItemType == GridItemType.GRIDITEM_GRAVESTONE) {
                    aStones.add(aGridItem)
                }
            }
            return aStones
        }

        /** How many of the board's grave stones have finished growing ({@code mGridItemCounter >= 100}). */
        private fun grownGraveStones(theBoard: Board): Int {
            var aGrown = 0
            for (aStone in graveStones(theBoard)) {
                if (aStone.mGridItemCounter >= 100) {
                    aGrown++
                }
            }
            return aGrown
        }

        /** Drops the board's grid items (the `clearBoard` of the grid). */
        private fun clearGridItems(theBoard: Board) {
            for (aGridItem in theBoard.mGridItems) {
                aGridItem.mDead = true
            }
        }

        /**
         * Puts the board's grid squares back to the lawn's own default.
         *
         * <p>{@code Board::PickBackground} only ever overwrites the squares of the rows a background makes
         * dirt, pool or high ground; it never puts a square back to grass. A fresh board starts all grass
         * (the constructor's loop), so the harness has to do it here: these measurements re-use the one
         * board the run booted, whose first pass was the level-1 tutorial lawn (its rows 0, 1, 3 and 4 are
         * dirt), and without this a later background would keep those dirt squares and the lawn would look
         * smaller than it is.
         */
        private fun resetGridSquares(theBoard: Board) {
            for (aCol in 0 until Board.MAX_GRID_SIZE_X) {
                for (aRow in 0 until Board.MAX_GRID_SIZE_Y) {
                    theBoard.mGridSquareType[aCol][aRow] = GridSquareType.GRIDSQUARE_GRASS
                }
            }
        }

        /** Places grave stones with the game's own placement until it refuses, i.e. until the lawn is full. */
        private fun placeGraveStones(theBoard: Board, theAttempts: Int) {
            for (aAttempt in 0 until theAttempts) {
                theBoard.mChallenge.graveDangerSpawnRandomGrave()
            }
        }

        /**
         * Places {@code theCount} grave stones on cells a lawn always has (the first, second and fifth
         * rows are grass on a day lawn and above the water on a pool lawn) and grows them with the game's
         * own growth tick, so the flag-wave rule sees stones the game would have grown itself.
         */
        private fun placeGrownGraveStones(theBoard: Board, theCount: Int) {
            val aRows = intArrayOf(0, 1, 4)
            var aPlaced = 0
            for (aRow in aRows) {
                for (aCol in 4 until 9) {
                    if (aPlaced >= theCount) {
                        break
                    }
                    if (theBoard.canAddGraveStoneAt(aCol, aRow)) {
                        theBoard.addAGraveStone(aCol, aRow)
                        aPlaced++
                    }
                }
            }

            theBoard.mEnableGraveStones = true
            for (aTick in 0 until GRAVE_GROW_TICKS) {
                theBoard.updateGridItems()
            }
        }

        /** The board's live zombies, as a snapshot the caller can compare against a later one. */
        private fun zombiesSnapshot(theBoard: Board): List<Zombie> {
            val aZombies = ArrayList<Zombie>()
            for (aZombie in theBoard.mZombies) {
                if (!aZombie.mDead) {
                    aZombies.add(aZombie)
                }
            }
            return aZombies
        }

        /** The board's live zombies that are not in the snapshot, i.e. the ones born since it was taken. */
        private fun newZombies(theBoard: Board, theBefore: List<Zombie>): List<Zombie> {
            val aNew = ArrayList<Zombie>()
            for (aZombie in theBoard.mZombies) {
                if (!aZombie.mDead && !theBefore.contains(aZombie)) {
                    aNew.add(aZombie)
                }
            }
            return aNew
        }

        /** The wave indexes of the board's level that are flag waves, as one comma-joined line. */
        private fun joinedFlagWaves(theBoard: Board): String {
            val aText = StringBuilder()
            for (aWave in 0 until theBoard.mNumWaves) {
                if (theBoard.isFlagWave(aWave)) {
                    if (aText.isNotEmpty()) {
                        aText.append(',')
                    }
                    aText.append(aWave)
                }
            }
            return aText.toString()
        }

        /** The columns the board's grave stones are in, as {@code min..max} (or {@code -}). */
        private fun graveColumnRange(theBoard: Board): String {
            var aMinimum = Int.MAX_VALUE
            var aMaximum = Int.MIN_VALUE
            for (aStone in graveStones(theBoard)) {
                aMinimum = minOf(aMinimum, aStone.mGridX)
                aMaximum = maxOf(aMaximum, aStone.mGridX)
            }
            return if (aMinimum > aMaximum) "-" else aMinimum.toString() + ".." + aMaximum
        }

        /** The rows the board's grave stones are in, as {@code min..max} (or {@code -}). */
        private fun graveRowRange(theBoard: Board): String {
            var aMinimum = Int.MAX_VALUE
            var aMaximum = Int.MIN_VALUE
            for (aStone in graveStones(theBoard)) {
                aMinimum = minOf(aMinimum, aStone.mGridY)
                aMaximum = maxOf(aMaximum, aStone.mGridY)
            }
            return if (aMinimum > aMaximum) "-" else aMinimum.toString() + ".." + aMaximum
        }

        /** How many of the board's grave stones are outside the columns {@code theFirst}..{@code theLast}. */
        private fun gravesOutsideColumns(theBoard: Board, theFirst: Int, theLast: Int): Int {
            var aOutside = 0
            for (aStone in graveStones(theBoard)) {
                if (aStone.mGridX < theFirst || aStone.mGridX > theLast) {
                    aOutside++
                }
            }
            return aOutside
        }

        /** How many of the board's grave stones stand on a lawn cell that is water. */
        private fun graveStonesInPoolRows(theBoard: Board): Int {
            var aInPool = 0
            for (aStone in graveStones(theBoard)) {
                if (theBoard.mPlantRow[aStone.mGridY] == PlantRowType.PLANTROW_POOL) {
                    aInPool++
                }
            }
            return aInPool
        }

        /** The zombies' types, as one comma-joined line. */
        private fun joinedZombieTypes(theZombies: List<Zombie>): String {
            val aText = StringBuilder()
            for (aZombie in theZombies) {
                if (aText.isNotEmpty()) {
                    aText.append(',')
                }
                aText.append(aZombie.mZombieType)
            }
            return aText.toString()
        }

        /** How many of the zombies are of one type. */
        private fun zombiesOfType(theZombies: List<Zombie>, theZombieType: Int): Int {
            var aCount = 0
            for (aZombie in theZombies) {
                if (aZombie.mZombieType == theZombieType) {
                    aCount++
                }
            }
            return aCount
        }

        /**
         * How many of the zombies stand on a grave stone's own cell: {@code Zombie::RiseFromGrave} puts a
         * zombie exactly where the stone's cell resolves to ({@code GridToPixelX(col, row) - 25}).
         */
        private fun zombiesOnGraveCells(theBoard: Board, theZombies: List<Zombie>): Int {
            var aOnGrave = 0
            for (aZombie in theZombies) {
                if (graveCellOf(theBoard, aZombie) != null) {
                    aOnGrave++
                }
            }
            return aOnGrave
        }

        /** The distinct grave cells the zombies stand on, as one comma-joined {@code x@y} line. */
        private fun graveCellsUsed(theBoard: Board, theZombies: List<Zombie>): String {
            val aCells = HashSet<String>()
            for (aZombie in theZombies) {
                val aStone = graveCellOf(theBoard, aZombie)
                if (aStone != null) {
                    aCells.add(aStone.mGridX.toString() + "@" + aStone.mGridY)
                }
            }
            return TreeSet(aCells).joinToString(",")
        }

        /** The grave stone whose cell the zombie stands on, or {@code null}. */
        private fun graveCellOf(theBoard: Board, theZombie: Zombie): GridItem? {
            for (aStone in graveStones(theBoard)) {
                if (theZombie.mRow == aStone.mGridY
                    && theZombie.mX == theBoard.gridToPixelX(aStone.mGridX, aStone.mGridY) - 25
                ) {
                    return aStone
                }
            }
            return null
        }

        /** How many of the zombies are in the phase the game raises them out of a grave in. */
        private fun zombiesRisingFromGrave(theZombies: List<Zombie>): Int {
            var aRising = 0
            for (aZombie in theZombies) {
                if (aZombie.mZombiePhase == ZombiePhase.PHASE_RISING_FROM_GRAVE) {
                    aRising++
                }
            }
            return aRising
        }

        /** The registered mode backgrounds, as {@code mode->background} pairs. */
        private fun joinedModeBackgrounds(): String {
            val aText = StringBuilder()
            for ((aEntryKey, aEntryValue) in BoardOverrides.modeBackgrounds()) {
                if (aText.isNotEmpty()) {
                    aText.append(',')
                }
                aText.append(aEntryKey).append("->").append(aEntryValue)
            }
            return aText.toString()
        }

        // ---------------------------------------------------------------- phase 4

        /**
         * What the run changed about the resources: nothing. The mod registers no override at all, so
         * {@code api-resource} has no name and nothing shadows the pak, and the engine's own read of
         * {@code properties/LawnStrings.txt} is the retail {@code main.pak} member.
         */
        private fun resourcePhase() {
            // Constructing the engine's singleton is what applies the module's Mixin (and is what the game
            // does at boot); report the installation only after that.
            val aPak = PakInterface.getInstance()
            report("resourceOverridesInstalled", ResourceOverrides.installed())
            val aNames = ResourceOverrides.overriddenNames()
            report("overriddenNamesCount", aNames.size)
            report("overriddenNames", aNames.joinToString(","))
            report("overrideOfLawnStrings", overrideOf(LAWN_STRINGS))
            report("overrideOfAlmanac", overrideOf(ALMANAC))

            val aRetailRoot = retailResourceRoot()
            val aMainPak = if (aRetailRoot == null) null else aRetailRoot.resolve("main.pak")
            report("retailMainPak", aMainPak)
            if (aMainPak == null || !Files.isRegularFile(aMainPak)) {
                report("retailPakMounted", false)
                return
            }

            // The game's boot mounts the pak; do it here too when the boot was skipped, so the read below
            // goes through the engine either way.
            if (aPak.resourceCount() == 0) {
                aPak.setResourceFolder(aRetailRoot!!.toString())
                aPak.addPakFile(aMainPak)
            }
            report("retailPakMounted", aPak.resourceCount() > 0)

            val aLawnStrings = aPak.readResource(LAWN_STRINGS)
            report("lawnStringsEngineLength", if (aLawnStrings == null) -1 else aLawnStrings.size)
            report("lawnStringsEngineSha256", if (aLawnStrings == null) "<missing>" else sha256(aLawnStrings))
            report("lawnStringsFromPak", isPakMember(aPak, LAWN_STRINGS))
        }

        // ---------------------------------------------------------------- helpers

        /** What, if anything, shadows a pak path; {@code none} when the pak member is what is served. */
        private fun overrideOf(thePakPath: String): String {
            val aOverride = ResourceOverrides.resolveOverride(thePakPath)
            return if (aOverride == null) "none" else aOverride.toString()
        }

        /** Whether the engine resolves the name to a member of the mounted pak. */
        private fun isPakMember(thePak: PakInterface, theName: String): Boolean {
            val aHandle = thePak.open(theName, "rb")
            return aHandle != null && aHandle.use { it.isPakMember() }
        }

        /** The retail resource root this machine has installed, or {@code null} when there is none. */
        private fun retailResourceRoot(): Path? {
            val aOverride = System.getProperty(RETAIL_RESOURCES_PROPERTY, "")
            if (!aOverride.isBlank()) {
                val aPath = Path.of(aOverride)
                return if (Files.isDirectory(aPath)) aPath else null
            }
            val aDiscovered = ResourceLocator.discover(arrayOf<String>()).resourceRoot()
            return if (aDiscovered == null) null else aDiscovered
        }

        private fun sha256(theData: ByteArray): String {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(theData))
        }

        private fun report(theKey: String, theValue: Any?) {
            REPORT.append(theKey).append('=').append(theValue).append('\n')
        }

        private fun flush(theReportPath: Path) {
            Files.writeString(theReportPath, REPORT.toString())
        }

        private fun requiredProperty(theName: String): String {
            val aValue = System.getProperty(theName, "")
            if (aValue.isBlank()) {
                throw IllegalStateException("-D" + theName + " is required")
            }
            return aValue
        }
    }
}
