package cn.enaium.fabricpvz.mod95

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Assumptions.assumeTrue

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.ArrayList
import java.util.HashMap
import java.util.HexFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.jar.JarFile
import java.util.stream.Collectors
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import cn.enaium.fabricpvz.portable.pvzp.ResourceLocator
import cn.enaium.fabricpvz.portable.sexy.pak.PakInterface

/**
 * End-to-end verification of the 95 Edition mod on the real Fabric Loader.
 *
 * <p>The test does exactly what a launcher does: it copies the <em>built mod jar</em> plus the
 * aggregate API jar into a real {@code mods/} directory and starts
 * {@code net.fabricmc.loader.impl.launch.knot.KnotClient} in a second JVM with the game and the
 * loader (plus Mixin and ASM, the game's launch libraries) on the class
 * path. The loader resolves the mods, runs the mod's {@code ModInitializer} - which registers the 95
 * rules with {@code api-zombie} - applies the API modules' Mixin configuration and only then calls the
 * game entry point, which this test redirects to
 * {@link cn.enaium.fabricpvz.mod95.test.Edition95GameMain} with {@code -Dpvz.game.mainClass}.
 *
 * <p>What the run has to show, in the words of {@code ../verification/VERIFICATION.md} §56:
 *
 * <ol>
 *   <li>the rebalanced zombies are <em>really born</em> through the board's own spawner with the 95
 *       numbers on them (and its flag zombie with the retail 820 body and the pail), while a type the
 *       mod does not touch keeps the game's own numbers;</li>
 *   <li>the 95 plant numbers are in the definition table the game reads the price, the recharge and
 *       the firing cadence from, and the spikerock is <em>really planted</em> through the board's own
 *       planter with the 95 health - while the plants and fields the mod leaves alone keep the retail
 *       values;</li>
 *   <li>the starfruit and the cactus <em>really fire</em> through the game's own {@code Plant::Fire},
 *       and the damage the fired projectile applies to the zombie it hits is the 95 one (40 / 1), next
 *       to a control pea at the retail 20;</li>
 *   <li>the mod changes <b>no resource</b>: {@code api-resource} has nothing registered and the
 *       engine's own read of {@code properties/LawnStrings.txt} is the retail {@code main.pak} member,
 *       which this test verifies by hashing the member itself.</li>
 *   <li>the batch-three board rules hold: each rewritten mode's zombie list, the five allowed-levels
 *       cells, the dropped spawn vetoes, the Beghouled winning score, the blast damage, the
 *       raining-seeds interval, the sun values and cap, the conveyor-belt seed lists, the sky drop,
 *       the grave-stone answer and the seed-picker veto - each next to a control the mod leaves
 *       alone, and each observed through the game's own read point ({@code BoardOverrides} hands the
 *       values over, the game decides).</li>
 * </ol>
 *
 * <p>Everything is reported through a file because the loader defines its own copies of the game, the
 * API and the mod classes; the assertions below are all made on that report. A machine without a
 * retail installation is a skip, not a failure: the rules are only observable on a real board, and the
 * mod deliberately ships none of the rights holder's files.
 */
class Edition95EndToEndTest {

    companion object {

        /** Launcher main class of the real Fabric Loader (client environment), from its own manifest. */
        private const val LAUNCHER_CLASS = "net.fabricmc.loader.impl.launch.knot.KnotClient"

        /** System property carrying the class path the launcher has to run with. */
        private const val FORK_CLASSPATH_PROPERTY = "pvz.test.forkClasspath"

        /** System property naming the built mod jar. */
        private const val MOD_JAR_PROPERTY = "pvz.test.modJar"

        /** The retail resource the mod must not shadow, and the resource the report controls it with. */
        private const val LAWN_STRINGS = "properties/LawnStrings.txt"

        /** Mod ids that must be resolved next to this mod, i.e. the game and the API modules. */
        private val DEPENDENCY_MODS = listOf(
            "fabricloader", "pvz-portable",
            "fabric-api", "api-base", "api-events", "api-plant", "api-resource", "api-zombie")

        /** The 95 rule set, as {@code ZombieOverrides} reports it: types and rule count. */
        private const val EXPECTED_OVERRIDDEN_TYPES = "1,4,5,6,7,8,20"
        private const val EXPECTED_OVERRIDE_COUNT = "7"

        /**
         * Every type in a comma-joined list of zombie types the grave wave rose must be one of the game's
         * three ({@code Board::PickGraveRisingZombieType}'s normal, cone and pail rows).
         */
        private fun assertGraveRisingTypes(theTypes: String?) {
            assertNotNull(theTypes, "the grave wave must have risen zombies")
            assertFalse(theTypes!!.isBlank(), "the grave wave must have risen zombies")
            for (aType in theTypes.split(",")) {
                assertTrue(aType == "0" || aType == "2" || aType == "4",
                    "a grave-rising zombie must be normal (0), cone (2) or pail (4), got " + theTypes)
            }
        }

        /**
         * The aggregate API jar this workspace builds: the one jar whose nested jars are the API modules,
         * with {@code api-zombie} among them.
         */
        private fun fabricApiJar(): Path {
            val libs = Path.of("..", "fabric-api", "build", "libs").toAbsolutePath().normalize()
            assertTrue(Files.isDirectory(libs), "the aggregate API jar must be built first: " + libs)
            // The version is the whole of what follows the artifact name, digits and dots only: the
            // classifiers of the same version - the `-sources` and `-javadoc` jars a Maven Central
            // publication needs next to the binary one - are not the aggregate, and neither is their
            // signature file.
            val jars = Files.list(libs).use { stream ->
                stream
                    .filter { path -> path.fileName.toString().matches(Regex("fabric-api-\\d[\\d.]*\\.jar")) }
                    .collect(Collectors.toList<Path>())
            }
            assertFalse(jars.isEmpty(), "no fabric-api-*.jar in " + libs)
            return jars.maxWithOrNull(Comparator { a, b ->
                compareVersions(
                    versionOf(a.fileName.toString()),
                    versionOf(b.fileName.toString())
                )
            }) ?: throw NoSuchElementException()
        }

        /** Compares dotted numeric versions segment by segment ({@code 1.10.0} is newer than {@code 1.2.0}). */
        private fun compareVersions(a: String, b: String): Int {
            val left = a.split(".")
            val right = b.split(".")
            for (i in 0 until maxOf(left.size, right.size)) {
                val l = if (i < left.size) numberPrefix(left[i]) else 0
                val r = if (i < right.size) numberPrefix(right[i]) else 0
                if (l != r) {
                    return l.compareTo(r)
                }
            }
            return a.compareTo(b)
        }

        private fun numberPrefix(segment: String): Int {
            var end = 0
            while (end < segment.length && Character.isDigit(segment[end])) {
                end++
            }
            return if (end == 0) 0 else segment.substring(0, end).toInt()
        }

        /** The version part of {@code fabric-api-<version>.jar}, compared numerically segment by segment. */
        private fun versionOf(fileName: String): String {
            return fileName.substring("fabric-api-".length, fileName.length - ".jar".length)
        }

        /**
         * The class path the launcher runs with: the test classes, the loader, the game and the game
         * provider - but not this project's own main output (the mod may only come from the mods
         * directory) and not the aggregate API jar the test class path carries for compilation: the API
         * has to load from {@code mods/} like every other mod, with the module versions its metadata
         * declares.
         */
        private fun forkClasspathWithoutBundledApi(): String {
            val classpath = System.getProperty(FORK_CLASSPATH_PROPERTY, "")
            if (classpath.isBlank()) {
                fail<Nothing>("-D" + FORK_CLASSPATH_PROPERTY + " is not set (run through Gradle)")
            }
            val entries = ArrayList<String>()
            for (entry in classpath.split(File.pathSeparator)) {
                val name = Path.of(entry).fileName.toString()
                val isBundledApi = name.matches(Regex("fabric-api-\\d[^/]*\\.jar"))
                if (!isBundledApi && !entry.isBlank()) {
                    entries.add(entry)
                }
            }
            return entries.joinToString(File.pathSeparator)
        }

        /** The directory holding the harness classes (this test's own output directory). */
        private fun harnessDirectory(): String {
            try {
                return Path.of(
                    Edition95EndToEndTest::class.java.protectionDomain.codeSource.location
                        .toURI()
                ).toString()
            } catch (e: Exception) {
                throw IllegalStateException("cannot locate the harness classes", e)
            }
        }

        /** The retail resource root this machine has installed, or an empty string. */
        private fun retailResourceRoot(): String {
            val override = System.getProperty("pvz.test.resources", "")
            if (!override.isBlank()) {
                return override
            }
            val discovered = ResourceLocator.discover(arrayOf<String>()).resourceRoot()
            return if (discovered == null) "" else discovered.toString()
        }

        /** The SHA-256 of a pak member, read with the engine's own reader in this (Mixin-free) JVM. */
        private fun pakMemberSha(pak: Path, member: String): String {
            val engine = PakInterface()
            engine.setResourceFolder(pak.parent.toString())
            assertTrue(engine.addPakFile(pak), "the retail pak must mount: " + pak)
            val bytes = engine.readResource(member)
            assertNotNull(bytes, "the retail pak must contain " + member)
            return sha256(bytes!!)
        }

        private fun sha256(data: ByteArray): String {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data))
        }

        private fun assertHex(value: String?) {
            assertNotNull(value, "a SHA-256 value is required")
            assertTrue(value!!.matches(Regex("[0-9a-f]{64}")), "expected a lower-case SHA-256, got " + value)
        }

        /** Asserts one reported value, naming the key in the failure message. */
        private fun assertValue(values: Map<String, String>, key: String, expected: String) {
            assertEquals(expected, values[key], key + " (report: " + values[key] + ")")
        }

        /** Asserts one reported number lies inside an inclusive range, naming the key. */
        private fun assertInRange(values: Map<String, String>, key: String, from: Int, to: Int) {
            val value = intOf(values, key)
            assertTrue(value >= from && value <= to,
                key + " must be in " + from + ".." + to + " (report: " + value + ")")
        }

        /** Asserts one plant rule label's definition row: present, with the given packet numbers. */
        private fun assertPlantDefinition(values: Map<String, String>, label: String, cost: String,
            refreshTime: String, launchRate: String) {
            assertValue(values, label + "_present", "true")
            assertValue(values, label + "_cost", cost)
            assertValue(values, label + "_refreshTime", refreshTime)
            assertValue(values, label + "_launchRate", launchRate)
        }

        private fun intOf(values: Map<String, String>, key: String): Int {
            val value = values[key]
            assertNotNull(value, "the report must carry " + key)
            return value!!.trim().toInt()
        }

        /** The comma-separated zombie types of a report value, sorted numerically, as one string. */
        private fun sortedTypes(value: String): String {
            return value.split(",")
                .map { it.trim() }
                .filter { entry -> entry.isNotEmpty() }
                .map { it.toInt() }
                .sorted()
                .joinToString(",") { it.toString() }
        }

        private fun readReport(report: Path): Map<String, String> {
            assertTrue(Files.isRegularFile(report), "the harness must have written its report")
            return parse(Files.readString(report))
        }

        private fun parse(content: String): Map<String, String> {
            val values = HashMap<String, String>()
            for (line in content.split("\n")) {
                val equals = line.indexOf('=')
                if (equals > 0) {
                    values[line.substring(0, equals)] = line.substring(equals + 1).trim()
                }
            }
            return values
        }

        private fun read(path: Path): String {
            return if (Files.exists(path)) Files.readString(path) else "<no output>"
        }

        private fun failureMessage(output: Path, report: Path): String {
            var tail = read(output)
            val keep = 4000
            if (tail.length > keep) {
                tail = "..." + tail.substring(tail.length - keep)
            }
            return "\n--- launcher output (tail) ---\n" + tail +
                "\n--- report ---\n" + (if (Files.exists(report)) Files.readString(report) else "<none>")
        }

        private fun requiredProperty(theName: String): String {
            val value = System.getProperty(theName, "")
            assertNotNull(value, "-D" + theName + " is required")
            assertTrue(!value.isBlank(), "-D" + theName + " is required")
            return value
        }
    }

    @TempDir
    lateinit var tmp: Path

    @Test
    fun theRulesReachTheZombiesAndPlantsAndNoResourceIsChanged() {
        val retailRoot = retailResourceRoot()
        val retailPak = if (retailRoot.isBlank()) null else Path.of(retailRoot).resolve("main.pak")
        assumeTrue(retailPak != null && Files.isRegularFile(retailPak),
                "No retail installation (main.pak) found: the rules can only be observed on a real "
                        + "board, and the no-override claim only against the retail pak.")

        val modJar = Path.of(requiredProperty(MOD_JAR_PROPERTY))
        assertTrue(Files.isRegularFile(modJar), "the mod jar must be built first: " + modJar)
        assertModJarContents(modJar)

        val apiJar = fabricApiJar()
        assertApiJarCarriesTheNewZombieModule(apiJar)
        assertApiJarCarriesTheNewPlantModule(apiJar)

        val mods = Files.createDirectories(tmp.resolve("mods"))
        val installedMod = Files.copy(modJar, mods.resolve(modJar.fileName)).toFile()
        assertTrue(installedMod.length() > 0, "the installed mod jar must not be empty")
        Files.copy(apiJar, mods.resolve(apiJar.fileName))

        val runDir = Files.createDirectories(tmp.resolve("run"))
        val report = tmp.resolve("mod95-report.txt")
        val output = tmp.resolve("loader-output.txt")

        val exitCode = launch(mods, runDir, report, output)
        assertEquals(0, exitCode, "Fabric Loader must boot the game with the mod: "
                + failureMessage(output, report))

        val values = readReport(report)
        assertEquals("none", values["harnessError"], "the harness must run to the end: "
                + failureMessage(output, report))

        // 1) The loader resolved the game, the API modules (from the aggregate jar in mods/) and this
        //    mod jar, and the API's own entry point ran before the game's.
        assertEquals(values["gameVersionByLoader"], values["gameVersion"],
                "the provider must report the version of the game it resolved")
        assertEquals("client", values["environment"])
        assertEquals("true", values["edition95Loaded"], "the 95 Edition mod must be loaded")
        for (id in DEPENDENCY_MODS) {
            assertTrue(values["modsLoaded"]!!.split(",").contains(id),
                    "mod " + id + " must be loaded, got " + values["modsLoaded"])
        }
        assertEquals("true", values["zombieModuleLoaded"])
        assertEquals("true", values["resourceModuleLoaded"])
        assertEquals("true", values["apiInitialized"], "the API entry point must run before the game")
        assertEquals(values["apiVersionByLoader"], values["apiVersion"],
                "the API must report the version the loader resolved")

        // 2) The mod registered exactly the 95 rule set with api-zombie - before anything spawned.
        assertEquals(EXPECTED_OVERRIDE_COUNT, values["overrideCount"],
                "the mod must register the seven zombie rules: " + values["overriddenZombieTypes"])
        assertEquals(EXPECTED_OVERRIDDEN_TYPES, sortedTypes(values["overriddenZombieTypes"]!!),
                "the mod must override exactly these zombie types, got "
                        + values["overriddenZombieTypes"])
        assertTrue(values["override_6"]!!.contains("spawnsAs=4"),
                "the door zombie must spawn as the bucket zombie: " + values["override_6"])
        assertTrue(values["override_1"]!!.contains("bodyHealth=820")
                        && values["override_1"]!!.contains("helm=2/1100")
                        && values["override_1"]!!.contains("bodyAnimation=anim_bucket"),
                "the flag zombie must be the 95 bucket-headed one: " + values["override_1"])

        // 3) The real game booted and the zombies were born through the board's own spawner.
        assertEquals("true", values["assetsPresent"])
        assertEquals("true", values["gameBooted"])
        assertEquals("true", values["assetsLoaded"])
        assertEquals("true", values["scenePlaying"])
        assertEquals("true", values["boardPresent"])
        for (label in listOf("normal", "flag", "pail", "door", "newspaper", "football", "dancer", "bungee")) {
            assertEquals("true", values[label + "_spawned"], label + " must spawn")
            assertEquals("true", values[label + "_reanimLoaded"], label + " must have its body art")
        }

        // 4) Every rebalanced zombie carries the 95 numbers - read back from the spawned object.
        //    The control first: a type the mod does not touch keeps the game's own values.
        assertValue(values, "normal_askedFor", "0")
        assertValue(values, "normal_type", "0")
        assertValue(values, "normal_bodyHealth", "270")
        assertValue(values, "normal_bodyMaxHealth", "270")
        assertValue(values, "normal_helmType", "0")
        assertValue(values, "normal_helmHealth", "0")
        assertValue(values, "normal_shieldType", "0")
        assertValue(values, "normal_shieldHealth", "0")
        assertValue(values, "normal_phaseCounter", "0")
        assertValue(values, "normal_bucketTracksVisible", "0")

        //    The flag zombie: 270 -> 820 body, a pail (2/1100), and the bucket body art shown.
        assertValue(values, "flag_type", "1")
        assertValue(values, "flag_bodyHealth", "820")
        assertValue(values, "flag_bodyMaxHealth", "820")
        assertValue(values, "flag_helmType", "2")
        assertValue(values, "flag_helmHealth", "1100")
        assertValue(values, "flag_helmMaxHealth", "1100")
        assertTrue(intOf(values, "flag_bucketTracksVisible") > 0,
                "the flag zombie must show the bucket body animation, got "
                        + values["flag_bucketTracksVisible"])

        //    The bucket zombie's pail is pinned to 1100, and nothing else about it moved.
        assertValue(values, "pail_type", "4")
        assertValue(values, "pail_bodyHealth", "270")
        assertValue(values, "pail_helmType", "2")
        assertValue(values, "pail_helmHealth", "1100")

        //    The door zombie: asked for 6, born as the bucket zombie - the type replacement happens
        //    before the game sets the zombie up, so it has no screen door at all.
        assertValue(values, "door_askedFor", "6")
        assertValue(values, "door_type", "4")
        assertValue(values, "door_helmType", "2")
        assertValue(values, "door_helmHealth", "1100")
        assertValue(values, "door_shieldType", "0")
        assertValue(values, "door_shieldHealth", "0")
        assertValue(values, "door_bucketTracksVisible", intOf(values, "pail_bucketTracksVisible").toString())

        //    The newspaper zombie's newspaper: 150 -> 1200, and the shield stays the newspaper.
        assertValue(values, "newspaper_type", "5")
        assertValue(values, "newspaper_bodyHealth", "270")
        assertValue(values, "newspaper_shieldType", "2")
        assertValue(values, "newspaper_shieldHealth", "1200")
        assertValue(values, "newspaper_shieldMaxHealth", "1200")

        //    The football zombie's helmet: 1400 -> 2800, the helmet type unchanged, body unchanged.
        assertValue(values, "football_type", "7")
        assertValue(values, "football_bodyHealth", "270")
        assertValue(values, "football_helmType", "3")
        assertValue(values, "football_helmHealth", "2800")

        //    The dancing zombie: 500 -> 1350 body.
        assertValue(values, "dancer_type", "8")
        assertValue(values, "dancer_bodyHealth", "1350")
        assertValue(values, "dancer_bodyMaxHealth", "1350")

        //    The bungee zombie lands immediately: the landing counter is 0.
        assertValue(values, "bungee_type", "20")
        assertValue(values, "bungee_bodyHealth", "450")
        assertValue(values, "bungee_phaseCounter", "0")

        // 5) The 95 plant and projectile rules. Registration first: exactly the JSON's rule set, with
        //    the spikerock's spawn health and the cattail's star/spike pair on it.
        assertEquals("18", values["plantOverrideCount"],
                "the mod must register the eighteen plant rules: " + values["plantOverriddenSeeds"])
        assertEquals("1,4,6,9,10,11,12,17,23,24,26,27,29,38,40,43,46,49",
                sortedTypes(values["plantOverriddenSeeds"]!!),
                "the mod must override exactly these seed types, got " + values["plantOverriddenSeeds"])
        assertTrue(values["plantOverride_6"]!!.contains("digestCountdown=2500")
                        && values["plantOverride_6"]!!.contains("biteInsteadOfEat=7"),
                "the chomper's two batch-four rules must be registered: " + values["plantOverride_6"])
        assertTrue(values["plantOverride_4"]!!.contains("spawnCountdown=1000")
                        && values["plantOverride_4"]!!.contains("explosionBlast=115/1/true"),
                "the potato mine's two batch-four rules must be registered: " + values["plantOverride_4"])
        assertTrue(values["plantOverride_9"]!!.contains("spawnCountdown=9000"),
                "the sun-shroom's growth countdown must be registered: " + values["plantOverride_9"])
        assertTrue(values["plantOverride_46"]!!.contains("spikeRockDamage=1800/10800/5400"),
                "the spikerock's hit and crack levels must be registered: " + values["plantOverride_46"])
        assertTrue(values["plantOverride_38"]!!.contains("producedCoins=4/6/50"),
                "the marigold's coins must be registered: " + values["plantOverride_38"])
        assertEquals("2", values["projectileOverrideCount"])
        assertEquals("7,8", sortedTypes(values["projectileOverriddenTypes"]!!),
                "the star and the spike, got " + values["projectileOverriddenTypes"])
        assertTrue(values["plantOverride_46"]!!.contains("bodyHealth=16200"),
                "the spikerock's 95 health must be registered: " + values["plantOverride_46"])
        assertTrue(values["plantOverride_43"]!!.contains("cost=275")
                        && values["plantOverride_43"]!!.contains("launchRate=75"),
                "the cattail's two rules must be registered: " + values["plantOverride_43"])
        assertTrue(values["projectileOverride_7"]!!.contains("damage=40")
                        && values["projectileOverride_8"]!!.contains("damage=1"),
                "both projectile rules must be registered: " + values["projectileOverride_7"] + ' '
                        + values["projectileOverride_8"])

        //    Every rule read back through the game's own definition accessor, next to the controls: a
        //    plant the mod leaves alone, and the fields of an overridden plant that its rule does not
        //    touch.
        assertPlantDefinition(values, "sunflower", "50", "750", "3300")
        assertPlantDefinition(values, "sunshroom", "25", "750", "3300")
        assertPlantDefinition(values, "potatomine", "50", "3000", "0")
        assertPlantDefinition(values, "gravebuster", "50", "750", "0")
        assertPlantDefinition(values, "hypnoshroom", "100", "3000", "0")
        assertPlantDefinition(values, "tallnut", "175", "2000", "0")
        assertPlantDefinition(values, "seashroom", "0", "1500", "150")
        assertPlantDefinition(values, "cactus", "200", "750", "150")
        assertPlantDefinition(values, "blover", "200", "2000", "0")
        assertPlantDefinition(values, "starfruit", "250", "750", "200")
        assertPlantDefinition(values, "marigold", "75", "1500", "3300")
        assertPlantDefinition(values, "gatlingpea", "450", "5000", "150")
        assertPlantDefinition(values, "cattail", "275", "5000", "75")
        assertPlantDefinition(values, "explodeonut", "150", "3000", "0")
        assertPlantDefinition(values, "control_peashooter", "100", "750", "150")
        assertValue(values, "control_potatomine_refresh", "3000")
        assertValue(values, "control_sunflower_cost", "50")

        //    Both projectile rules, as the rows the game reads at impact time.
        assertValue(values, "starpult_starDamageDefinition", "40")
        assertValue(values, "cactus_spikeDamageDefinition", "1")
        assertValue(values, "control_peaDamageDefinition", "20")

        //    And the star's flight (rule B9): all five directions the starfruit fires fly their own
        //    heading for their distance first, and then all five fly straight forward.
        assertValue(values, "star_flight_left_row", "2")
        // The two right corners reach the rows beside the plant's own, and the up/down pair the rows
        // two away: five rows in all, which is the starfruit's attack in the 95 edition.
        assertValue(values, "star_flight_rightDown_row", "3")
        assertValue(values, "star_flight_rightUp_row", "1")
        assertValue(values, "star_flight_up_row", "0")
        assertValue(values, "star_flight_down_row", "4")
        assertValue(values, "star_flight_left_turnedAtOnce", "true")
        assertValue(values, "star_flight_rightDown_keptHeading", "true")
        assertValue(values, "star_flight_up_keptHeading", "true")
        assertValue(values, "star_flight_all_forward", "true")

        // 6) The plants really born on the board: the spikerock carries the 95 health, the control
        //    keeps the retail 300.
        assertValue(values, "spikerock_planted", "true")
        assertValue(values, "spikerock_seedType", "46")
        assertValue(values, "spikerock_health", "16200")
        assertValue(values, "spikerock_maxHealth", "16200")
        assertValue(values, "control_peashooter_health", "300")
        assertValue(values, "control_peashooter_maxHealth", "300")

        //    The projectiles really fired, and the damage the game then applied to the zombie each hit
        //    (normal zombie, 270 body): the starfruit's five stars at 40, the cactus's spike at 1, and
        //    the control pea at the retail 20.
        assertValue(values, "starpult_shots", "5")
        assertValue(values, "starpult_projectileType", "7")
        assertValue(values, "starpult_projectileDamage", "40")
        assertValue(values, "starpult_projectileCanHit", "true")
        assertValue(values, "starpult_zombieBodyHealth", "230")
        assertValue(values, "starpult_damageApplied", "40")
        assertValue(values, "starpult_projectileDead", "true")

        assertValue(values, "cactus_shots", "1")
        assertValue(values, "cactus_projectileType", "8")
        assertValue(values, "cactus_projectileDamage", "1")
        assertValue(values, "cactus_zombieBodyHealth", "269")
        assertValue(values, "cactus_damageApplied", "1")

        assertValue(values, "control_pea_shots", "1")
        assertValue(values, "control_pea_projectileType", "0")
        assertValue(values, "control_pea_projectileDamage", "20")
        assertValue(values, "control_pea_zombieBodyHealth", "250")
        assertValue(values, "control_pea_damageApplied", "20")

        // 7) The batch-three board rules, registration first: the mod's own knobs, before anything ran.
        assertEquals("48", values["boardRuleCount"],
                "the mod must register the batch-three, batch-four and batch-seven knobs: "
                        + values["boardRuleKeys"])
        assertTrue(values["boardRuleKeys"]!!.contains("skyDropCoinType")
                        && values["boardRuleKeys"]!!.contains("dropLootSunBranch")
                        && values["boardRuleKeys"]!!.contains("dropLootCoin[2]")
                        && values["boardRuleKeys"]!!.contains("lastStandInitialSun")
                        && values["boardRuleKeys"]!!.contains("coinUsableSeedType")
                        && values["boardRuleKeys"]!!.contains("numWaves[40]")
                        && values["boardRuleKeys"]!!.contains("spawnMultiplier"),
                "every batch-four knob must be registered, got " + values["boardRuleKeys"])
        assertTrue(values["boardRuleKeys"]!!.contains("graveModeEnabled")
                        && values["boardRuleKeys"]!!.contains("graveStoneWaveThreshold")
                        && values["boardRuleKeys"]!!.contains("whackAZombieZombieCount")
                        && values["boardRuleKeys"]!!.contains("modeBackground[41]")
                        && values["boardRuleKeys"]!!.contains("modeBackground[42]"),
                "every batch-seven knob must be registered, got " + values["boardRuleKeys"])
        assertTrue(values["boardRuleKeys"]!!.contains("allowZombies[29]")
                        && values["boardRuleKeys"]!!.contains("allowZombies[34]")
                        && values["boardRuleKeys"]!!.contains("allowZombies[37]")
                        && values["boardRuleKeys"]!!.contains("allowZombies[20]")
                        && values["boardRuleKeys"]!!.contains("allowZombies[16]")
                        && values["boardRuleKeys"]!!.contains("allowZombies[42]")
                        && values["boardRuleKeys"]!!.contains("allowZombies[23]"),
                "every rewritten mode must be registered, got " + values["boardRuleKeys"])
        assertEquals("true", values["boardRule_relaxed"])
        assertValue(values, "beghouled_winScore", "100")
        assertValue(values, "beghouled_winScoreDefault", "75")
        assertValue(values, "killAllZombies_damage", "2400")
        assertValue(values, "rainSeed_min", "200")
        assertValue(values, "rainSeed_max", "699")
        assertValue(values, "sunMoney_cap", "2000000000")
        assertValue(values, "sunSpawning_mode", "41")
        assertValue(values, "sunSpawning_coin", "16")
        assertValue(values, "graveStones_forced", "false")
        assertValue(values, "graveBuster_inverted", "true")
        assertValue(values, "sun_monetaryValues", "4=50,5=25,6=75,2=-1")

        // 8) The five allowed-levels cells, raw and through the game's own spawn question.
        assertValue(values, "table_poleVaulter31", "0")
        assertValue(values, "table_poleVaulter32", "1")
        assertValue(values, "table_newspaper10", "0")
        assertValue(values, "table_newspaper11", "0")
        assertValue(values, "table_newspaper12", "0")
        assertValue(values, "table_newspaper16", "1")
        assertValue(values, "table_football31", "0")
        assertValue(values, "table_football32", "0")

        assertValue(values, "spawn_poleVaulterLevel32", "true")
        assertValue(values, "spawn_poleVaulterLevel31", "false")
        assertValue(values, "spawn_newspaperLevel11", "false")
        assertValue(values, "spawn_newspaperLevel12", "false")
        assertValue(values, "spawn_newspaperLevel16", "true")
        assertValue(values, "spawn_footballLevel32", "false")
        assertValue(values, "spawn_footballLevel31", "false")

        //    The two dropped vetoes: the flag zombie's definition weighs 0, i.e. the retail rule
        //    refuses it everywhere, while the table allows it everywhere - and the control whose table
        //    row says no stays no.
        assertValue(values, "flag_pickWeight", "0")
        assertValue(values, "spawn_flagLevel1", "true")
        assertValue(values, "spawn_gargantuarLevel1", "false")

        // 9) The allowed zombies each rewritten mode really gets, read back from the array the game's
        //    own Challenge::InitZombieWaves filled - next to a control the mode still refuses.
        assertValue(values, "speedMode_allowsFootball", "true")
        assertValue(values, "speedMode_allowsJack", "true")
        assertValue(values, "speedMode_allowsLadder", "true")
        assertValue(values, "speedMode_controlZamboni", "false")

        for (name in listOf("Normal", "Cone", "Pail", "Newspaper", "Door", "Football", "Zamboni",
                "Gargantuar")) {
            assertValue(values, "pogoPartyMode_allows" + name, "true")
        }
        assertValue(values, "pogoPartyMode_controlLadder", "false")

        assertValue(values, "sunnyDayMode_allowsDoor", "true")
        assertValue(values, "sunnyDayMode_allowsLadder", "true")
        assertValue(values, "sunnyDayMode_controlGargantuar", "false")

        for (name in listOf("PoleVaulter", "Cone", "Pail", "Jack")) {
            assertValue(values, "beghouledMode_allows" + name, "true")
        }
        assertValue(values, "beghouledMode_controlLadder", "false")

        for (name in listOf("Newspaper", "Jack", "Balloon", "Pogo", "Gargantuar")) {
            assertValue(values, "warAndPeasMode_allows" + name, "true")
        }
        assertValue(values, "warAndPeasMode_controlNormal", "false")

        for (name in listOf("Normal", "Pail", "Newspaper", "Dancer", "Snorkel", "Dolphin", "Digger")) {
            assertValue(values, "iceMode_allows" + name, "true")
        }
        assertValue(values, "iceMode_controlFootball", "false")

        for (name in listOf("Flag", "PoleVaulter", "Ladder")) {
            assertValue(values, "zombiquariumMode_allows" + name, "true")
            assertValue(values, "beghouledTwistMode_allows" + name, "true")
        }
        assertValue(values, "beghouledTwistMode_allowsJack", "true")
        assertValue(values, "zombiquariumMode_controlGargantuar", "false")
        assertValue(values, "beghouledTwistMode_controlGargantuar", "false")

        // 10) The global numbers, observed on the real board.
        assertValue(values, "stageHasGraveStones", "false")
        assertValue(values, "graveBuster_notRecommended", "0")
        assertEquals("8", values["control_plantern_notRecommended"],
                "the other advice bits must be untouched (the plantern's needs-fog bit): "
                        + values["control_plantern_notRecommended"])

        assertValue(values, "coinSun_sunValue", "50")
        assertValue(values, "coinSmallSun_sunValue", "25")
        assertValue(values, "coinLargeSun_sunValue", "75")
        assertValue(values, "control_coinGold_sunValue", "0")

        assertValue(values, "sunMoney_afterOneBillion", "1000000000")
        assertValue(values, "sunMoney_afterTwoBillion", "2000000000")

        assertValue(values, "blast_canBeHit", "true")
        assertValue(values, "blast_zombiesHit", "1")
        assertValue(values, "blast_gargantuarHealthBefore", "3000")
        assertValue(values, "blast_gargantuarHealthAfter", "600")
        assertValue(values, "blast_damageApplied", "2400")

        intOf(values, "rainSeed_firstDraw")
        assertInRange(values, "rainSeed_firstDraw", 200, 699)
        assertInRange(values, "rainSeed_minimumSeen", 200, 699)
        assertInRange(values, "rainSeed_maximumSeen", 200, 699)

        // 11) The conveyor-belt lists: the replaced seed types are gone, the replacements are what the
        //     belt really gets, and the pick the game remembers is the packet the belt carries.
        assertEquals("0", values["portalCombat_seen_0"], "Portal Combat must not draw a peashooter")
        assertEquals("0", values["portalCombat_seen_3"], "Portal Combat must not draw a wall-nut")
        assertEquals("0", values["portalCombat_seen_2"], "Portal Combat must not draw a cherry bomb")
        assertEquals("1", values["portalCombat_seen_18"], "Portal Combat must draw threepeaters")
        assertEquals("1", values["portalCombat_seen_49"], "Portal Combat must draw explode-o-nuts")
        assertEquals("1", values["portalCombat_seen_15"], "Portal Combat must draw doom shrooms")
        assertEquals(values["portalCombat_lastPick"], values["portalCombat_lastBeltSeed"],
                "the remembered pick and the belt packet must agree")

        assertEquals("0", values["invisighoul_seen_0"], "Invisighoul must not draw a peashooter")
        assertEquals("0", values["invisighoul_seen_17"], "Invisighoul must not draw a squash")
        assertEquals("1", values["invisighoul_seen_18"], "Invisighoul must draw threepeaters")
        assertEquals("1", values["invisighoul_seen_2"], "Invisighoul must draw cherry bombs")
        assertEquals(values["invisighoul_lastPick"], values["invisighoul_lastBeltSeed"])

        // 12) The sky drop: Air Raid drops a usable seed packet, and every other stage the small sun
        //     (25 under the batch-four correction), not the ordinary one.
        assertValue(values, "skyDrop_airRaid_coinDropped", "true")
        assertValue(values, "skyDrop_airRaid_coinType", "16")
        assertValue(values, "skyDrop_airRaid_coinIsSun", "false")
        assertValue(values, "skyDrop_sunnyDay_coinType", "5")
        assertValue(values, "skyDrop_sunnyDay_coinIsSun", "true")
        assertValue(values, "skyDrop_sunnyDay_coinSunValue", "25")
        assertValue(values, "skyDrop_adventure_coinType", "5")
        assertValue(values, "skyDrop_adventure_coinSunValue", "25")

        // 13) The seed picker: Air Raid refuses the marigold, the game's own rules still hold.
        assertValue(values, "seedPicker_present", "true")
        assertValue(values, "seedPicker_airRaidMarigold", "true")
        assertValue(values, "seedPicker_airRaidPeashooter", "false")
        assertValue(values, "seedPicker_adventureMarigold", "false")
        assertValue(values, "seedPicker_lastStandSunflower", "true")

        // 14) The batch-four rules, registration first (the values the mod wrote before the game
        //     booted) and then each one where the game itself reads it.
        assertValue(values, "skyDrop_coin", "5")
        assertValue(values, "dropLoot_sunBranch", "true")
        assertValue(values, "dropLoot_coins", "5,6,4")
        assertValue(values, "lastStand_initialSun", "8000")
        assertValue(values, "coin_usableSeedType", "27")
        assertEquals("10->20,20->40,12->20,30->40,40->50", values["numWaves_registered"],
                "the wave-count table must be registered, got " + values["numWaves_registered"])

        // 15) The potato mine: armed with 1000, and its blast is the cherry bomb's - a radius the
        //     game's own circle test measures as 115 (a victim 90 pixels out is inside it and outside
        //     60), one row either side (a victim a row away is hit, one two rows away is not) and
        //     burning (a plant-headed victim survives it in the burned phase, which is what the ash
        //     effect leaves - a plain 1800 of damage would have killed it).
        assertValue(values, "potatomine_stateCountdown", "1000")
        assertValue(values, "potatomine_state", "0")
        assertValue(values, "potatomine_deadAfterSpecial", "true")
        assertValue(values, "mineSameRow_affected", "true")
        assertValue(values, "mineNextRow_rowDistanceToBlastRow", "1")
        assertValue(values, "mineNextRow_affected", "true")
        assertValue(values, "mineTwoRowsAway_rowDistanceToBlastRow", "2")
        assertValue(values, "mineTwoRowsAway_affected", "false")
        assertValue(values, "mineRadiusEdge_distanceFromBlast", "90")
        assertValue(values, "mineRadiusEdge_overlapsRadius60", "false")
        assertValue(values, "mineRadiusEdge_overlapsRadius115", "true")
        assertValue(values, "mineRadiusEdge_affected", "true")
        assertValue(values, "minePastRadius_distanceFromBlast", "140")
        assertValue(values, "minePastRadius_overlapsRadius115", "false")
        assertValue(values, "minePastRadius_affected", "false")
        assertValue(values, "mineBurnVictim_type", "26")
        assertValue(values, "mineBurnVictim_deadAfter", "false")
        assertValue(values, "mineBurnVictim_phaseAfter", "2")
        assertValue(values, "mineBurnVictim_isBurnedPhase", "true")

        // 16) The sun-shroom grows in 90s, and a plant the batch does not touch is born with none.
        assertValue(values, "sunshroom_stateCountdown", "9000")
        assertValue(values, "sunshroom_state", "23")
        assertValue(values, "control_sunflower_stateCountdown", "0")

        // 17) The chomper: it swallows a plain zombie, bites the gargantuar as it always did, and now
        //     bites a football zombie too (the 40 lands on the helmet, which is where that zombie
        //     takes its first damage) - and the digesting transition writes 2500.
        assertValue(values, "chomper_digestCountdown", "2500")
        assertValue(values, "chomper_stateAfterDigest", "13")
        assertValue(values, "control_chomperDigestDefault", "4000")
        assertValue(values, "chomperFootball_targetFound", "true")
        assertValue(values, "chomperFootball_bitten", "true")
        assertValue(values, "chomperFootball_zombieHelmDamage", "40")
        assertValue(values, "chomperFootball_swallowed", "false")
        assertValue(values, "chomperGargantuar_targetFound", "true")
        assertValue(values, "chomperGargantuar_bitten", "true")
        assertValue(values, "chomperGargantuar_zombieDamage", "40")
        assertValue(values, "chomperNormal_targetFound", "true")
        assertValue(values, "chomperNormal_swallowed", "true")
        assertValue(values, "chomperNormal_zombieDead", "true")

        // 18) The spikerock: 1800 a hit, the first crack at 10800 and the second at 5400 - nine hits,
        //     as the retail pair of 50 and 16200/300/150 gave.
        assertValue(values, "spikeRock_healthBefore", "16200")
        assertValue(values, "spikeRock_healthAtBigSpike3Hidden", "10800")
        assertValue(values, "spikeRock_healthAtBigSpike2Hidden", "5400")
        assertValue(values, "spikeRock_hitAtBigSpike3Hidden", "3")
        assertValue(values, "spikeRock_hitAtBigSpike2Hidden", "6")
        assertValue(values, "spikeRock_hitAtDeath", "9")

        // 19) The marigold pays sun: both of its coins are sun coins over enough draws, and the coin a
        //     Big Time level pays on top is the ordinary one - while the sunflower next to it keeps
        //     dropping the ordinary sun, so the coin producers the batch does not touch are unchanged.
        assertValue(values, "marigold_draws", "200")
        assertValue(values, "marigold_seenCoins", "4,6")
        assertValue(values, "marigold_drawsWithTwoCoins", "0")
        assertValue(values, "marigold_drawsWithMoreThanTwo", "0")
        assertValue(values, "marigold_bigTimeSeenCoins", "4,6")
        assertValue(values, "marigold_bigTimeSecondCoins", "4")
        assertValue(values, "control_sunflower_seenCoins", "4")

        // 20) The death loot: the sun branch runs on an adventure board too, and its three coins are
        //     the small, the large and the ordinary sun in that order (150 in all). Silver and gold
        //     coins still exist in the loot table the branch falls through to, so the triple - not the
        //     set of seen coins - is what moved.
        assertValue(values, "dropLoot_draws", "400")
        assertValue(values, "dropLoot_firstTriple", "5,6,4")
        assertTrue(intOf(values, "dropLoot_drawsWithThreeCoins") > 0,
                "the sun branch must have dropped its three coins (report: "
                        + values["dropLoot_drawsWithThreeCoins"] + ")")
        assertTrue(values["dropLoot_seenCoins"]!!.contains("1")
                        && values["dropLoot_seenCoins"]!!.contains("2"),
                "the loot table's own silver/gold drops are untouched (report: "
                        + values["dropLoot_seenCoins"] + ")")

        // 21) The wave counts: the whack-a-zombie challenge 12 -> 20, the 30-wave group 30 -> 40, the
        //     remaining challenges 40 -> 50 and a survival stage 10 -> 20 / 20 -> 40. The group that
        //     assigns 20 - the Wall-nut Bowling / Air Raid / Grave Danger / High Gravity / Portal
        //     Combat / War and Peas / Invisighoul one - shares the patched immediate with the survival
        //     stage, so its 20 -> 40 with it, and so does the floor a second-run adventure level is
        //     raised to (additions-2's attribution of file 0x92fd; §58 had recorded the group as
        //     "stays 20" from the first additions file, which this batch corrects).
        assertValue(values, "waves_whackAZombie", "20")
        assertValue(values, "waves_pogoParty", "40")
        assertValue(values, "waves_beghouled", "50")
        assertValue(values, "waves_twentyWaveGroup_highGravity", "40")
        assertValue(values, "waves_twentyWaveGroup_wallnutBowling", "40")
        assertValue(values, "waves_twentyWaveGroup_airRaid", "40")
        assertValue(values, "waves_adventureLevel1OwnValue", "4")
        assertValue(values, "waves_secondRunAdventure_ruled", "40")
        assertValue(values, "waves_secondRunAdventure_retail", "20")
        assertValue(values, "survivalNormal_stageWaves", "20")
        assertValue(values, "survivalHard_stageWaves", "40")
        assertValue(values, "lastStand_stageWaves", "20")

        // 22) A coin is born carrying the blover, and the initialiser's other defaults are untouched;
        //     a Last Stand level starts with 8000, while the adventure branch keeps its own 150.
        assertValue(values, "coinUsableSeedType_usableSeedType", "27")
        assertValue(values, "coinUsableSeedType_usableSeedTypeChecked", "27")
        assertValue(values, "control_coin_pottedPlantSeedType", "-1")
        assertValue(values, "control_coin_needsBouncyArrow", "false")
        assertValue(values, "lastStand_initialSun", "8000")
        assertValue(values, "control_adventure_initialSun", "150")

        // 23) No resource is overridden: api-resource has nothing registered (no root, no file, no
        //    bytes), so nothing can shadow the retail pak - and the engine's read of LawnStrings is
        //    the pak member, whose SHA-256 this test computes itself.
        assertEquals("true", values["resourceOverridesInstalled"],
                "the api-resource module's PakInterface Mixin must be applied")
        assertEquals("0", values["overriddenNamesCount"],
                "the mod must register no resource override, got " + values["overriddenNames"])
        assertEquals("", values["overriddenNames"])
        assertEquals("none", values["overrideOfLawnStrings"])
        assertEquals("none", values["overrideOfAlmanac"])
        assertEquals("true", values["retailPakMounted"])
        assertEquals("true", values["lawnStringsFromPak"],
                "the engine must read LawnStrings out of the retail pak")

        val pakMemberSha = pakMemberSha(retailPak!!, LAWN_STRINGS)
        assertHex(values["lawnStringsEngineSha256"])
        assertEquals(pakMemberSha, values["lawnStringsEngineSha256"],
                "the engine's LawnStrings must be the retail pak member byte for byte, got "
                        + values["lawnStringsEngineSha256"] + " (pak member: " + pakMemberSha + ")")

        // 24) The batch-five behaviour rules, each one as the run left it - and each next to the control
        //     that tells the rule from the game's own behaviour. See ../verification/VERIFICATION.md §60 for what
        //     each observation drives and why.
        assertEquals("true", values["behaviour_boardPresent"])
        assertEquals("{mindControlBoost=200/200/1.25, burnImmunityTotalHealth=1800, "
                        + "burnImmunityPhases=29,30, coldRemovalChilledCounter=1000, shieldAlwaysAbsorbs=true, "
                        + "butterIceTrapCounter=300, butterChilledCounter=1000, burnRowIceTrapCounter=750, "
                        + "burnRowChilledCounter=2500, burnRowDamage=1000, yetiUpdateOnFlag=true}",
                values["behaviour_zombieRules"],
                "the ten zombie rules must be registered with api-zombie");
        assertEquals("{notOnGroundState=21, extraFireSeedType=40}",
                values["behaviour_plantRules"],
                "the two whole-game plant rules must be registered with api-plant");
        assertEquals("true", values["behaviour_tallnutCrushProof"],
                "the tall nut must be crush-proof");
        assertEquals("true", values["behaviour_spikerockCrushProof"],
                "the spikerock keeps the game's own exemption");
        assertEquals("false", values["behaviour_peashooterCrushProof"],
                "a plant nobody exempted is not crush-proof");
        assertTrue(values["override_5"]!!.contains("mindControlBodyHealth=920"),
                "the newspaper zombie's flat mind-control body health: " + values["override_5"])

        // 24a) Mind control: the game's own hypnoshroom path hypnotises the zombie, and the boost is on
        //      it - cleared chill, body maximum plus 200, helmet maximum plus 200 (for a zombie that
        //      wears no helmet: the value the patch writes, unseen), scale 1.25 - while the zombie
        //      nobody hypnotised, and the newspaper zombie's flat 920, are the controls.
        assertEquals("true", values["mind_hypnoFound"])
        assertEquals("true", values["mind_hypnoEaten"], "the hypnoshroom must be eaten")
        assertEquals("true", values["mind_mindControlled"])
        assertEquals("250", values["mind_chilledBeforeBoost"])
        assertEquals("0", values["mind_chilledAfter"])
        assertEquals("270", values["mind_bodyMaxHealth"])
        assertEquals("470", values["mind_bodyHealthAfter"], "270 + 200")
        assertEquals("0", values["mind_helmTypeAfter"])
        assertEquals("200", values["mind_helmHealthAfter"], "0 + 200")
        assertEquals("1.25", values["mind_scaleAfter"])
        assertEquals("false", values["control_uncharmed_mindControlled"])
        assertEquals("270", values["control_uncharmed_bodyHealth"])
        assertEquals("0", values["control_uncharmed_helmHealth"])
        assertEquals("1.0", values["control_uncharmed_scale"])
        assertEquals("270", values["mind_newspaperBodyMaxHealth"])
        assertEquals("920", values["mind_newspaperBodyHealth"], "the newspaper's flat 920")
        assertEquals("200", values["mind_newspaperHelmHealth"])
        assertEquals("1.25", values["mind_newspaperScale"])

        // 24b) The burn's clamp: the football zombie's 3070 total takes the flat 1800 (on its helmet,
        //      since every hit routes through the armour the rule leaves in place) and it survives,
        //      where the plain zombie's 270 burns it up. The newspaper's two phases take the clamp
        //      whatever their 1470 total - the flat 1800 goes through the shield and what is left of it
        //      kills the body, so the shield is empty and the body is gone - while its mad phase, the
        //      third of them, burns with its shield and its body untouched.
        assertEquals("3070", values["burn_football_totalBefore"])
        assertEquals("false", values["burn_football_dead"], "one ash attack must not kill it")
        assertEquals("270", values["burn_football_bodyAfter"])
        assertEquals("1000", values["burn_football_helmAfter"], "2800 - 1800")
        assertEquals("true", values["burn_plain_dead"], "the control must burn")
        assertEquals("1470", values["burn_reading_totalBefore"])
        assertEquals("true", values["burn_reading_dead"], "the clamp's whole 1800 lands on it")
        assertEquals("0", values["burn_reading_shieldAfter"], "1200 - 1800, capped at the shield")
        assertEquals("0", values["burn_reading_bodyAfter"], "the 600 left over kill the body")
        assertEquals("true", values["burn_maddening_dead"])
        assertEquals("0", values["burn_maddening_shieldAfter"], "the second named phase clamps too")
        assertEquals("0", values["burn_maddening_bodyAfter"])
        assertEquals("true", values["burn_mad_dead"], "the phase the rule does not name burns")
        assertEquals("1200", values["burn_mad_shieldAfter"], "the burn never touches the shield")
        assertEquals("270", values["burn_mad_bodyAfter"], "nor the body")

        // 24c) RemoveColdEffects writes 1000 instead of clearing the counter.
        assertEquals("250", values["cold_chilledBefore"])
        assertEquals("1000", values["cold_chilledAfter"])

        // 24d) The ladder zombie: both damage flags now go through the ladder, so its body is untouched
        //      until the ladder is gone - and a zombie without a shield still takes the body damage.
        assertEquals("3", values["shield_ladder_shieldType"])
        assertEquals("400", values["shield_ladder_hitsBody_shieldAfter"])
        assertEquals("500", values["shield_ladder_hitsBody_bodyAfter"],
                "the hits-shield-and-body flag no longer restores the full damage")
        assertEquals("300", values["shield_ladder_bypass_shieldAfter"],
                "the bypass-the-shield flag no longer skips the ladder")
        assertEquals("500", values["shield_ladder_bypass_bodyAfter"])
        assertEquals("0", values["shield_ladder_broken_shieldType"])
        assertEquals("400", values["shield_ladder_broken_bodyAfter"], "what is left over reaches the body")
        assertEquals("170", values["shield_plain_bodyAfter"], "the control: no shield, no change")

        // 24e) The hiding scary-shroom is refused by the zombie's target search, any plant in that state
        //      is refused with it, a plant rising with a bungee is no longer refused, and an ordinary
        //      plant is targetable.
        assertEquals("1", values["scaredy_bornState"])
        assertEquals("true", values["scaredy_awake_targetable"])
        assertEquals("21", values["scaredy_hidingState"])
        assertEquals("false", values["scaredy_hiding_targetable"])
        assertEquals("false", values["scaredy_otherSeedType_hiding_targetable"])
        assertEquals("false", values["scaredy_risingWithBungee_notOnGround"])
        assertEquals("true", values["scaredy_risingWithBungee_targetable"])
        assertEquals("false", values["control_plainPlant_notOnGround"])
        assertEquals("true", values["control_plainPlant_targetable"])

        // 24f) The extra shot moved: driving the launch counter to 50 makes the gatling pea take the
        //      shot the cattail used to (the shooting counter its own arm starts, and the four-pea burst
        //      it leads to - the per-burst chain is untouched, so this is not "six peas"), while the
        //      cattail takes nothing.
        assertEquals("40", values["extraFire_gatling_registeredSeedType"])
        assertEquals("true", values["extraFire_gatling_targetFound"])
        assertEquals("50", values["extraFire_gatling_launchCounter"])
        assertEquals("100", values["extraFire_gatling_shootingCounter"])
        assertEquals("4", values["extraFire_gatling_burstShots"], "the gatling's own four-pea chain")
        assertEquals("true", values["extraFire_cattail_targetFound"])
        assertEquals("0", values["extraFire_cattail_shootingCounter"], "the cattail takes nothing")
        assertEquals("0", values["extraFire_cattail_shots"])

        // 24g) The tall nut is crush-proof at both comparisons: a gargantuar smashing it does not squash
        //      it and takes its bounce damage, and the whole-square pass every vehicle and squash makes
        //      leaves it standing - while an ordinary plant is squashed by both.
        assertEquals("true", values["smash_plain_found"])
        assertEquals("true", values["smash_plain_plantSquished"])
        assertEquals("3000", values["smash_plain_zombieBodyAfter"], "the gargantuar takes nothing")
        assertEquals("true", values["smash_tallnut_found"])
        assertEquals("false", values["smash_tallnut_plantSquished"])
        assertEquals("2980", values["smash_tallnut_zombieBodyAfter"], "3000 - 20, the bounce")
        assertEquals("true", values["smash_spikerock_found"])
        assertEquals("false", values["smash_spikerock_plantSquished"])
        assertEquals("14400", values["smash_spikerock_plantHealthAfter"], "16200 - 1800")
        assertEquals("2980", values["smash_spikerock_zombieBodyAfter"])
        assertEquals("true", values["squish_driveOver_plainSquished"])
        assertEquals("false", values["squish_driveOver_tallnutSquished"])
        assertEquals("false", values["squish_driveOver_spikerockSquished"])
        assertEquals("true", values["squish_chew_plainSquished"])
        assertEquals("false", values["squish_chew_tallnutSquished"])
        assertEquals("false", values["squish_chew_spikerockSquished"])

        // 24h) The bite takes 8, the explode-o-nut turns into a cherry bomb below 40 health (and its own
        //      update then really explodes it on the zombie beside it), and the tall nut turns into a
        //      squash below 300 - with the control one bite above that threshold left alone.
        assertEquals("8", values["eat_firstBiteDamage"])
        assertEquals("49", values["eat_explode_seedTypeAfterFirstBite"], "45 - 8 is still over 40")
        assertEquals("37", values["eat_explode_healthAfterFirstBite"])
        assertEquals("2", values["eat_explode_seedTypeAfterSecondBite"], "37 is under 40")
        assertEquals("29", values["eat_explode_healthAfterSecondBite"])
        assertEquals("1", values["eat_explode_specialCountdown"])
        assertEquals("1", values["eat_explode_stateCountdown"])
        assertEquals("true", values["eat_explode_plantDead"])
        assertEquals("true", values["eat_explode_zombieAffected"], "the cherry bomb's blast")
        assertEquals("17", values["eat_tallnut_seedTypeAfter"], "305 - 8 is under 300")
        assertEquals("297", values["eat_tallnut_healthAfter"])
        assertEquals("23", values["control_tallnut_seedTypeAfter"], "320 - 8 is over 300")
        assertEquals("312", values["control_tallnut_healthAfter"])

        // 24i) The seed-packet rewrite: the ice challenge's wall-nut packet is an explode-o-nut after the
        //      level initialised, and the name and the price a player reads off it are the explode-o-nut's
        //      - while the five packets beside it keep the types the level gave them.
        assertEquals("0", values["packet_bankType_0"])
        assertEquals("2", values["packet_bankType_1"])
        assertEquals("49", values["packet_bankType_2"])
        assertEquals("7", values["packet_bankType_3"])
        assertEquals("5", values["packet_bankType_4"])
        assertEquals("6", values["packet_bankType_5"])
        assertEquals("49", values["packet_wallnutPacketType"])
        assertEquals(values["packet_explodeONutName"], values["packet_wallnutDisplayName"],
                "the rewritten packet must show the explode-o-nut, got " + values["packet_wallnutDisplayName"])
        assertEquals(values["packet_explodeONutName"], values["packet_displayedSeedTypeName"])
        assertFalse(values["packet_wallnutDisplayName"] == values["packet_wallnutName"],
                "and that name is not the wall nut's")
        assertEquals("50", values["packet_wallnutPacketCost"], "the wall nut's own price")
        assertEquals("150", values["packet_explodeONutPacketCost"], "the 95 explode-o-nut price")
        assertEquals("100", values["packet_control_packet0Cost"])

        // 24j) The butter carries cold: a buttered zombie keeps the game's 400 butter frames and gains
        //      the rule's 300 ice-trap and 1000 chill frames, while a zamboni - which the game's own
        //      method returns before the butter write for - gains none of them.
        // 24n) The squash reaches two cells: the zombie the game's own search would leave out of
        //      reach at two cells is found, and one at three cells is not (the far end of the search).
        assertEquals("80", values["squash_cellWidth"], "one cell in pixels")
        assertEquals("true", values["squash_twoCells_targetFound"],
                "the squash must reach two cells")
        assertEquals("false", values["squash_threeCells_targetFound"],
                "and not three: " + values["squash_attackRectReach"])

        // 24o) The chomper reaches two cells as well: the zombie the game's own search would leave out
        //      of reach at two cells is a target, and one at three cells is not.
        assertEquals("130", values["chomperReach_attackRectWidth"],
                "the widened attack rectangle: the game's own 40 plus 90")
        assertTrue(values["chomperReach_overlap"]!!.toInt() > 0,
                "the two-cell zombie must overlap the attack rectangle: " + values["chomperReach_overlap"])
        assertEquals("true", values["chomperReach_twoCells_targetFound"],
                "the chomper must reach two cells")
        assertEquals("false", values["chomperReach_threeCells_targetFound"],
                "and not three")

        // 24p) The fume-shroom's puff covers the whole row ahead: the zombie at the far side of the
        //      board is damaged by it, while one behind the plant and one in another row are not.
        assertEquals("true", values["fumeRow_farInFront"], "the far zombie: " + values["fumeRow_farX"])
        assertEquals("20", values["fumeRow_farDamage"], "the whole row ahead is covered")
        assertEquals("0", values["fumeRow_behindDamage"], "behind the plant is not")
        assertEquals("0", values["fumeRow_otherRowDamage"], "nor is another row")

        assertEquals("400", values["butter_butteredCounter"])
        assertEquals("300", values["butter_iceTrapCounter"])
        assertEquals("1000", values["butter_chilledCounter"])
        assertEquals("true", values["butter_zamboniSpawned"])
        assertEquals("0", values["butter_zamboni_butteredCounter"])
        assertEquals("0", values["butter_zamboni_iceTrapCounter"])
        assertEquals("0", values["butter_zamboni_chilledCounter"])

        // 24k) The burn row freezes instead of unfreezing, and pays 1000 where the burn's own clamp is
        //      suppressed for it: the gargantuar above the clamp (3000) loses the rule's 1000 and keeps
        //      the rule's 750/2500 cold, and a plain zombie below the clamp burns away as it always did.
        assertEquals("3000", values["burnrow_healthBefore"])
        assertEquals("2000", values["burnrow_healthAfter"], "3000 - 1000, not 3000 - 1800")
        assertEquals("750/2500", values["burnrow_coldAfter"],
                "the full freeze (ice trap) and the slow, as the row's cave writes them")
        assertEquals("true", values["burnrow_plainDeadAfter"],
                "the control: a zombie under the clamp still burns away in the row")
        assertEquals("0/2500", values["burnrow_plainColdAfter"],
                "and the row's cold is on it: the burn clears the ice trap, the slow stays")

        // 24l) The yeti's per-type update moved to the flag zombie: the dispatch runs it for a flag
        //      (PHASE_YETI_RUNNING) and no longer for a yeti, which keeps the normal phase - each zombie
        //      put in the phase and counter the update reacts to.
        assertEquals("91", values["yeti_flag_phase"])
        assertEquals("0", values["yeti_yeti_phase"])

        // 24m) The super-mower toggle triggers every mower: right after the game wrote the flag, every
        //      mower of the board is MOWER_TRIGGERED(2).
        assertEquals("true", values["mower_modeAfter"])
        for (aMowerState in values["mower_statesAfter"]!!.split(",")) {
            assertEquals("2", aMowerState, "every mower must be triggered by the toggle")
        }

        // 25) The sixth batch: the torchwood's conversion table and the wave budget.
        //
        // 25a) The conversion is registered as the game's own two rows moved, and the rows the mod does
        //      not touch still answer with the game's own conversion (a pea to a fireball, a snow pea to
        //      a plain pea) - the API side of the table.
        assertValue(values, "torchwood_registeredConversions", "0->1,1->6")
        assertValue(values, "torchwood_gamePeaTarget", "1")
        assertValue(values, "torchwood_gameSnowPeaTarget", "6")

        // 25b) A pea fired through a torchwood really becomes a snow pea: the projectile the game fires
        //      as PROJECTILE_PEA (0) is PROJECTILE_SNOWPEA (1) after the flame, its damage is the snow
        //      pea's 20, no flame reanimation is attached, and its impact chills the zombie it lands on -
        //      while the same shot with no torchwood in the lane stays a pea and leaves the zombie warm.
        assertValue(values, "torchwood_subjectsReady", "true")
        assertValue(values, "torchwood_peaFired", "true")
        assertValue(values, "torchwood_peaTypeBefore", "0")
        assertValue(values, "torchwood_convertedType", "1")
        assertValue(values, "torchwood_convertedTypeIsSnowPea", "true")
        assertValue(values, "torchwood_convertedDamage", "20")
        assertValue(values, "torchwood_convertedHasFlameReanim", "-1")
        assertTrue(intOf(values, "torchwood_peaXAtConversion") > intOf(values, "torchwood_peaXBefore"),
                "the flame the pea met is ahead of where it was fired")
        assertValue(values, "torchwood_hitDamage", "20")
        assertEquals("1000", values["torchwood_hitChilledCounter"],
                "a snow pea's hit must chill the zombie (the game's own 1000-tick cold), got "
                        + values["torchwood_hitChilledCounter"])
        assertValue(values, "torchwood_slowApplied", "true")

        assertValue(values, "control_torchwood_subjectsReady", "true")
        assertValue(values, "control_torchwood_peaType", "0")
        assertValue(values, "control_torchwood_hitDamage", "20")
        assertValue(values, "control_torchwood_chilledCounter", "0")
        assertValue(values, "control_torchwood_slowApplied", "false")

        // 25c) The pixels: the sprite the game's own Projectile::Draw puts on a canvas for the converted
        //      projectile is the snow pea's material - the same drawn pixels as a snow pea the game fired
        //      as one, and not the pixels of the pea the same projectile was drawn as before the flame.
        //      The two images the game ships are reported too, so both summaries are readable.
        val peaPixels = values["torchwood_peaPixelsBefore"]
        val convertedPixels = values["torchwood_convertedPixels"]
        val referencePixels = values["torchwood_referenceSnowPeaPixels"]
        assertValue(values, "torchwood_referenceSnowPeaFired", "true")
        assertValue(values, "torchwood_referenceSnowPeaType", "1")
        assertNotNull(convertedPixels, "the converted projectile must report the pixels it draws")
        assertFalse(convertedPixels!!.startsWith("error") || convertedPixels == "none",
                "the drawn sprite must be readable (report: " + convertedPixels + ")")
        assertEquals(referencePixels, convertedPixels,
                "the converted pea must draw the snow pea's material")
        assertFalse(peaPixels == convertedPixels,
                "and not the pea's it drew before the flame (before: " + peaPixels + ")")
        assertFalse(values["torchwood_imagePea"] == values["torchwood_imageSnowPea"],
                "the two materials the game ships must differ in pixels")

        // 25d) The other direction and the row the game never asks about: a snow pea that crosses a
        //      torchwood becomes a fireball (PROJECTILE_FIREBALL, 6) with the flame reanimation attached
        //      and the fireball's 40 damage, and a fireball that crosses one is left alone.
        assertValue(values, "torchwood_snowPeaSubjectsReady", "true")
        assertValue(values, "torchwood_snowPeaBecomes", "6")
        assertValue(values, "torchwood_snowPeaDamage", "40")
        assertValue(values, "torchwood_snowPeaFlameReanim", "92")
        assertEquals("13", values["torchwood_snowPeaHitDamage"],
                "a fireball's impact is the game's own splash, a third of its 40: "
                        + values["torchwood_snowPeaHitDamage"])
        assertValue(values, "torchwood_fireballStaysFireball", "true")
        assertValue(values, "torchwood_fireballTypeAfterTorchwood", "6")

        // 25e) The wave budget: the zombies Board::InitZombieWaves writes into the 1-1's waves. Both
        //      halves of the pair start the game's own random stream at the same seed, so the only thing
        //      that differs between them is the factor - and the first three waves are exactly four
        //      times the retail ones (1, 1, 1 -> 4, 4, 4). The final wave is 4 + 3 against 1 + 3: the
        //      game tops an adventure level's last wave up with the level's own zombie types, a count no
        //      factor scales, which is why the total ratio is 2.875 and the waves before it are 4.0000.
        assertValue(values, "spawn_multiplierRegistered", "4")
        assertValue(values, "spawn_adventureLevel1_waves", "4")
        assertValue(values, "spawn_adventureLevel1_retailCounts", "1,1,1,5")
        assertValue(values, "spawn_adventureLevel1_ruledCounts", "4,4,4,11")
        assertValue(values, "spawn_adventureLevel1_retailTotal", "8")
        assertValue(values, "spawn_adventureLevel1_ruledTotal", "23")
        assertValue(values, "spawn_adventureLevel1_ratio", "2.8750")
        assertEquals("4.0000", values["spawn_adventureLevel1_bodyRatio"],
                "the waves the final-wave top-up does not touch must be exactly four times the retail "
                        + "ones, got " + values["spawn_adventureLevel1_bodyRatio"])

        // 25f) The three controls, one per arm of the game's own chain: a mode whose own factor is 1
        //      (Beghouled) is multiplied now - 2.94 times as many zombies, not 4, because the extra
        //      budget is spent on stronger types and the game caps a wave at 50 - the little-trouble
        //      level that owned the x4 arm keeps exactly what it had, and Column Like You See 'Em keeps
        //      its own x6. Column is the one mode the change does not move: the game tests that mode
        //      before the arm the patch arms (see ../verification/VERIFICATION.md §63).
        assertValue(values, "spawn_littleTrouble_ratio", "1.0000")
        assertValue(values, "spawn_littleTrouble_bodyRatio", "1.0000")
        assertValue(values, "spawn_column_ratio", "1.0000")
        assertValue(values, "spawn_column_bodyRatio", "1.0000")
        val beghouledRatio = values["spawn_beghouled_bodyRatio"]!!.trim().toDouble()
        assertTrue(beghouledRatio > 2.5 && beghouledRatio < 4.5,
                "a mode the game gave no factor must be multiplied (report: " + beghouledRatio + ")")
        assertTrue(intOf(values, "spawn_beghouled_ruledTotal") > intOf(values, "spawn_beghouled_retailTotal"),
                "and really carries more zombies")
        for (mode in listOf("adventureLevel1", "beghouled", "littleTrouble", "column")) {
            assertInRange(values, "spawn_" + mode + "_retailTotal", 1, 5000)
        }

        // 26) The seventh batch: the grave mode, the whack-a-zombie wave and the two backgrounds.
        // 26a) The grave stones grow on the game's own wave hook: the adventure level 1-9 (whose 20
        //      waves give the flag waves 9 and 19) grows none through the first flag wave, one per wave
        //      after it - nine between the two flags, and none on the last wave - all of them in the
        //      five right-hand columns. With the grave mode taken back the same twenty waves grow
        //      nothing at all, and with the game's own threshold of 5 they start at wave 6 instead.
        assertValue(values, "grave_background", "0")
        assertValue(values, "grave_levelWaves", "20")
        assertValue(values, "grave_flagWaves", "9,19")
        assertValue(values, "grave_growthCounts",
                "0,0,0,0,0,0,0,0,0,0,1,2,3,4,5,6,7,8,9,9")
        assertValue(values, "grave_growthStonesAtEnd", "9")
        assertValue(values, "grave_growthRiseTotal", "0")
        assertValue(values, "grave_growthColumns", "4..8")
        assertValue(values, "grave_growthOutsideRightFiveColumns", "0")
        assertValue(values, "grave_control_growthCounts",
                "0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0")
        assertValue(values, "grave_control_growthStonesAtEnd", "0")
        assertValue(values, "grave_control_growthRiseTotal", "0")
        assertValue(values, "grave_threshold5_growthCounts",
                "0,0,0,0,0,0,1,2,3,3,4,5,6,7,8,9,10,11,12,12")
        assertValue(values, "grave_threshold5_growthStonesAtEnd", "12")
        assertValue(values, "grave_threshold_restored", "9")

        // 26b) The cell bound of the game's own placement: 25 stones fill a day lawn (five columns,
        //      five rows) and 20 fill a pool lawn (the two water rows take none), all in columns 4..8.
        assertValue(values, "graveBounds_dayBackground", "0")
        assertValue(values, "graveBounds_dayCount", "25")
        assertValue(values, "graveBounds_dayColumns", "4..8")
        assertValue(values, "graveBounds_dayRows", "0..4")
        assertValue(values, "graveBounds_poolBackground", "2")
        assertValue(values, "graveBounds_poolCount", "20")
        assertValue(values, "graveBounds_poolColumns", "4..8")
        // Rows 0..5: the pool lawn's two water rows are the hole in the middle, not the end of the lawn.
        assertValue(values, "graveBounds_poolRows", "0..5")
        assertValue(values, "graveBounds_poolWaterStones", "0")

        // 26c) The flag wave rises one zombie out of every grown stone, of the game's own three types
        //      (normal, cone, pail), each standing on its stone's cell and in the rising-from-grave
        //      phase - and with the grave mode taken back the same flag wave on the same board rises
        //      nothing. War and Peas rises none even with the stones there (the game's own exclusion),
        //      a pool level rises its own trio instead (none of them on a stone) and a roof level the
        //      sky trio - the two "trio" rules the grave mode moves to every flag wave, which is why
        //      a pool level with the grave mode off rises nothing.
        assertValue(values, "graveRise_graves", "3")
        assertValue(values, "graveRise_grownStones", "3")
        assertValue(values, "graveRise_risen", "3")
        assertValue(values, "graveRise_onGraveCells", "3")
        assertValue(values, "graveRise_phaseRisingFromGrave", "3")
        assertGraveRisingTypes(values["graveRise_types"])
        assertValue(values, "graveRise_control_risen", "0")
        assertValue(values, "graveZomBotany_graves", "3")
        assertValue(values, "graveZomBotany_risen", "0")
        assertValue(values, "gravePool_background", "2")
        assertValue(values, "gravePool_graves", "3")
        assertValue(values, "gravePool_risen", "3")
        assertValue(values, "gravePool_onGraveCells", "0")
        assertGraveRisingTypes(values["gravePool_types"])
        assertValue(values, "gravePool_control_risen", "0")
        assertValue(values, "graveRoof_background", "4")
        assertValue(values, "graveRoof_risen", "6")
        assertValue(values, "graveRoof_bungees", "3")

        // 26d) The whack-a-zombie wave: the mod's base count rises two zombies out of two different
        //      stones, the game's own base one out of one (the same board, the same phase, only the
        //      count differs).
        assertValue(values, "whackAZombie_graves", "4")
        assertValue(values, "whackAZombie_risen", "2")
        assertValue(values, "whackAZombie_types", "0,0")
        assertEquals("2", values["whackAZombie_gravesUsed"]!!.split(",").size.toString(),
                "two different stones must have been used: " + values["whackAZombie_gravesUsed"])
        assertValue(values, "whackAZombie_control_risen", "1")
        assertEquals("1", values["whackAZombie_control_gravesUsed"]!!.split(",").size.toString(),
                "the game's own base count uses one stone: " + values["whackAZombie_control_gravesUsed"])
        assertValue(values, "whackAZombie_restored", "2")

        // 26e) The two backgrounds, at the game's own read point: the air raid is played on the day
        //      lawn and the ice level in the pool (with the pool's two water rows, which is what the
        //      background really decides), where the game's own cells put back answer the fog night and
        //      the day lawn - and a mode nobody registered keeps its own.
        assertValue(values, "modeBackground_airRaid_ruled", "0")
        assertValue(values, "modeBackground_airRaid_waterRows", "0")
        assertValue(values, "modeBackground_ice_ruled", "2")
        assertValue(values, "modeBackground_ice_waterRows", "2")
        assertValue(values, "modeBackground_airRaid_retail", "3")
        assertValue(values, "modeBackground_ice_retail", "0")
        assertValue(values, "modeBackground_ice_retailWaterRows", "0")
        assertValue(values, "modeBackground_control_rainingSeeds", "3")
        assertValue(values, "modeBackground_restored", "41->0,42->2")

        // 26f) ... and the registration itself: the four knobs the mod left on the API, read before
        //      anything above ran (put last so that a rule whose registration is wrong fails on what
        //      the rule was supposed to do, not on the number it registered - see §64.5).
        assertValue(values, "grave_modeEnabled", "true")
        assertValue(values, "grave_waveThreshold", "9")
        assertValue(values, "grave_whackAZombieCount", "2")
        assertValue(values, "grave_modeBackgrounds", "41->0,42->2")
    }

    /** The jar a player drops into {@code mods/} must carry the code and the metadata, and no art. */
    private fun assertModJarContents(modJar: Path) {
        val entries: Set<String> = JarFile(modJar.toFile()).use { jar ->
            jar.stream().map { it.name }.collect(Collectors.toSet<String>())
        }

        assertTrue(entries.contains("fabric.mod.json"), "the jar must carry fabric.mod.json, got " + entries)
        assertTrue(entries.contains("cn/enaium/fabricpvz/mod95/Edition95Mod.class"), "the entry point class")
        assertTrue(entries.contains("cn/enaium/fabricpvz/mod95/Edition95Rules.class"), "the 95 rule table")
        assertTrue(entries.stream().noneMatch { name -> name.startsWith("assets/") },
                "the mod ships no art files (it uses the retail ones), got " + entries)
    }

    /**
     * The aggregate API jar this workspace builds must nest the {@code api-zombie} of <em>this</em>
     * source tree: the run below then really exercises {@code ZombieOverrides} and its Mixin, not an
     * older module from a stale build.
     */
    private fun assertApiJarCarriesTheNewZombieModule(apiJar: Path) {
        val extracted = tmp.resolve("api-zombie-under-test.jar")
        JarFile(apiJar.toFile()).use { api ->
            val nested: List<String> = api.stream().map { it.name }
                .filter { name -> name.startsWith("META-INF/jars/api-zombie-") && name.endsWith(".jar") }
                .collect(Collectors.toList())
            assertFalse(nested.isEmpty(), "the aggregate API jar must nest api-zombie, got " + apiJar)
            api.getInputStream(api.getJarEntry(nested[0])).use { aStream ->
                Files.copy(aStream, extracted, StandardCopyOption.REPLACE_EXISTING)
            }
        }

        JarFile(extracted.toFile()).use { zombie ->
            assertNotNull(zombie.getEntry("cn/enaium/fabricpvz/api/zombie/ZombieOverrides.class"),
                    "the module must carry the ZombieOverrides facade")
            assertNotNull(zombie.getEntry("cn/enaium/fabricpvz/api/zombie/ZombieOverride.class"),
                    "the module must carry the ZombieOverride handle")
            assertNotNull(zombie.getEntry("cn/enaium/fabricpvz/api/zombie/mixin/ZombieOverridesMixin.class"),
                    "the module must carry the Mixin that applies the overrides")
            assertNotNull(zombie.getEntry("cn/enaium/fabricpvz/api/zombie/mixin/ZombieStatusMixin.class"),
                    "the module must carry the Mixin that carries the burn and cold-effect rules")
            assertNotNull(zombie.getEntry("cn/enaium/fabricpvz/api/zombie/mixin/ZombieCombatMixin.class"),
                    "the module must carry the Mixin that carries the shield rule")
            val config = zombie.getJarEntry("api-zombie.mixins.json")
            assertNotNull(config, "the module must carry its Mixin configuration")
            zombie.getInputStream(config!!).use { aStream ->
                val json = String(aStream.readAllBytes(), StandardCharsets.UTF_8)
                assertTrue(json.contains("ZombieOverridesMixin"),
                        "the Mixin configuration must list the new Mixin, got " + json)
                assertTrue(json.contains("ZombieStatusMixin") && json.contains("ZombieCombatMixin"),
                        "the Mixin configuration must list the batch-five Mixins, got " + json)
            }
        }
    }

    /**
     * The aggregate API jar this workspace builds must also nest the {@code api-plant} of <em>this</em>
     * source tree: the run below then really exercises {@code PlantOverrides} / {@code ProjectileOverrides}
     * and the plant Mixin, not an older module from a stale build.
     */
    private fun assertApiJarCarriesTheNewPlantModule(apiJar: Path) {
        val extracted = tmp.resolve("api-plant-under-test.jar")
        JarFile(apiJar.toFile()).use { api ->
            val nested: List<String> = api.stream().map { it.name }
                .filter { name -> name.startsWith("META-INF/jars/api-plant-") && name.endsWith(".jar") }
                .collect(Collectors.toList())
            assertFalse(nested.isEmpty(), "the aggregate API jar must nest api-plant, got " + apiJar)
            api.getInputStream(api.getJarEntry(nested[0])).use { aStream ->
                Files.copy(aStream, extracted, StandardCopyOption.REPLACE_EXISTING)
            }
        }

        JarFile(extracted.toFile()).use { plant ->
            assertNotNull(plant.getEntry("cn/enaium/fabricpvz/api/plant/PlantOverrides.class"),
                    "the module must carry the PlantOverrides facade")
            assertNotNull(plant.getEntry("cn/enaium/fabricpvz/api/plant/PlantOverride.class"),
                    "the module must carry the PlantOverride handle")
            assertNotNull(plant.getEntry("cn/enaium/fabricpvz/api/plant/ProjectileOverrides.class"),
                    "the module must carry the ProjectileOverrides facade")
            assertNotNull(plant.getEntry("cn/enaium/fabricpvz/api/plant/ProjectileOverride.class"),
                    "the module must carry the ProjectileOverride handle")
            assertNotNull(plant.getEntry("cn/enaium/fabricpvz/api/plant/mixin/PlantOverridesMixin.class"),
                    "the module must carry the Mixin that applies the spawn overrides")
            assertNotNull(plant.getEntry("cn/enaium/fabricpvz/api/plant/BoardOverrides.class"),
                    "the module must carry the BoardOverrides facade")
            assertNotNull(plant.getEntry("cn/enaium/fabricpvz/api/plant/mixin/BoardWavesMixin.class"),
                    "the module must carry the wave-level Mixin")
            assertNotNull(plant.getEntry("cn/enaium/fabricpvz/api/plant/mixin/ChallengeMixin.class"),
                    "the module must carry the Challenge Mixin")
            assertNotNull(plant.getEntry("cn/enaium/fabricpvz/api/plant/mixin/ZombieCrushMixin.class"),
                    "the module must carry the Mixin that answers the square-squash comparison")
            assertNotNull(plant.getEntry("cn/enaium/fabricpvz/api/plant/mixin/ZombieSmashMixin.class"),
                    "the module must carry the Mixin that answers the gargantuar's smash comparison")
            val config = plant.getJarEntry("api-plant.mixins.json")
            assertNotNull(config, "the module must carry its Mixin configuration")
            plant.getInputStream(config!!).use { aStream ->
                val json = String(aStream.readAllBytes(), StandardCharsets.UTF_8)
                assertTrue(json.contains("PlantOverridesMixin"),
                        "the Mixin configuration must list the new Mixin, got " + json)
                assertTrue(json.contains("BoardWavesMixin") && json.contains("ChallengeMixin")
                                && json.contains("ChallengeBeghouledMixin") && json.contains("ChallengeWeatherMixin")
                                && json.contains("CoinMixin") && json.contains("SeedChooserScreenMixin"),
                        "the Mixin configuration must list the batch-three Mixins, got " + json)
                assertTrue(json.contains("ZombieCrushMixin") && json.contains("ZombieSmashMixin"),
                        "the Mixin configuration must list the batch-five Mixins, got " + json)
            }
        }
    }

    /**
     * Starts the real Fabric Loader in a second JVM, exactly like the launcher would.
     *
     * @return the launcher's exit code
     */
    private fun launch(mods: Path, runDir: Path, report: Path, output: Path): Int {
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val command = ArrayList<String>()
        command.add(java)
        command.add("--enable-native-access=ALL-UNNAMED")

        if (System.getProperty("os.name").lowercase(Locale.ROOT).contains("mac")) {
            // SDL (LWJGL's bindings) needs the first thread.
            command.add("-XstartOnFirstThread")
        }

        command.add("-cp")
        command.add(forkClasspathWithoutBundledApi())
        // The harness entry point lives in this project's test classes, which are not part of the
        // launcher's class path proper: the provider adds these entries to the loader's own class
        // loader, so the harness and the mod/API/game classes it inspects are all defined by the same
        // class loader (otherwise it would read a second copy of every static).
        command.add("-Dpvz.extraClasspath=" + harnessDirectory())
        // Development mode: the loader also discovers mods on the class path. The API is deliberately
        // NOT on the launcher class path (it is filtered out below); it is a jar in mods/, exactly as
        // in an installation, so the run loads the api-zombie module the 95 Edition needs.
        command.add("-Dfabric.development=true")
        // This port is the only provider on the class path.
        command.add("-Dfabric.skipMcProvider=true")
        // A crash must exit instead of opening the loader's Swing error window.
        command.add("-Dfabric.noGui=true")
        command.add("-Dpvz.gameDir=" + runDir)
        command.add("-Dfabric.modsFolder=" + mods)
        command.add("-Dpvz.game.mainClass=cn.enaium.fabricpvz.mod95.test.Edition95GameMain")
        command.add("-Dpvz.test.report=" + report)
        val retail = retailResourceRoot()
        if (!retail.isBlank()) {
            command.add("-Dpvz.test.resources=" + retail)
        }
        command.add(LAUNCHER_CLASS)
        command.add("-savedir")
        command.add(runDir.resolve("userdata").toString())

        val builder = ProcessBuilder(command)
            .directory(tmp.toFile())
            .redirectOutput(output.toFile())
            .redirectErrorStream(true)
        println("[mod95-e2e] boot command:\n  " + command.joinToString(" \\\n  "))
        val process = builder.start()

        if (!process.waitFor(15, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            throw AssertionError("Fabric Loader did not finish; launcher output:\n" + read(output))
        }

        println("[mod95-e2e] " + LAUNCHER_CLASS + " exited with " + process.exitValue())
        println("[mod95-e2e] launcher output:\n" + read(output))
        return process.exitValue()
    }
}
