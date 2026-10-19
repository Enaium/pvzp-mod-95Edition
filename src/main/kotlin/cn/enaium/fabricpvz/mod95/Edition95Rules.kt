package cn.enaium.fabricpvz.mod95

import cn.enaium.fabricpvz.api.plant.BoardOverrides
import cn.enaium.fabricpvz.api.plant.PlantOverride
import cn.enaium.fabricpvz.api.plant.PlantOverrides
import cn.enaium.fabricpvz.api.plant.ProjectileOverrides
import cn.enaium.fabricpvz.api.zombie.ZombieOverride
import cn.enaium.fabricpvz.api.zombie.ZombieOverrides
import cn.enaium.fabricpvz.portable.lawn.BackgroundType
import cn.enaium.fabricpvz.portable.lawn.Board
import cn.enaium.fabricpvz.portable.lawn.CoinType
import cn.enaium.fabricpvz.portable.lawn.GameConstants
import cn.enaium.fabricpvz.portable.lawn.GameMode
import cn.enaium.fabricpvz.portable.lawn.HelmType
import cn.enaium.fabricpvz.portable.lawn.Plant
import cn.enaium.fabricpvz.portable.lawn.PlantState
import cn.enaium.fabricpvz.portable.lawn.ProjectileMotion
import cn.enaium.fabricpvz.portable.lawn.ProjectileType
import cn.enaium.fabricpvz.portable.lawn.SeedPacket
import cn.enaium.fabricpvz.portable.lawn.SeedType
import cn.enaium.fabricpvz.portable.lawn.Zombie
import cn.enaium.fabricpvz.portable.lawn.ZombiePhase
import cn.enaium.fabricpvz.portable.lawn.ZombieType
import cn.enaium.fabricpvz.portable.lawn.hooks.LawnHooks
import cn.enaium.fabricpvz.portable.lawn.hooks.LawnHooksRegistry
import kotlin.math.abs

/**
 * The 95 Edition's rebalance, as rules the engine runs: the numbers its author patched into
 * {@code PlantsVsZombies.exe}, ported rule by rule from the reverse engineering in
 * {@code ../verification/research/95-edition-exe-patch.md} and its machine-readable twin
 * {@code ../verification/research/95-edition-rules.json}.
 *
 * <p>Every entry below carries the file offset / VA the rule was read from and its confidence, the
 * same way the JSON does, so a rule can be traced back to the bytes it came from. This round adds the
 * <b>plant numbers</b> and the <b>projectile damage</b> next to the <b>zombie numbers</b> of the first
 * round: the plant costs / recharges / firing cadences are definition-row writes
 * ({@link PlantOverrides}), the spikerock's health is a spawn value ({@link PlantOverride#bodyHealth}),
 * and the projectile damages are table writes ({@link ProjectileOverrides}). The rules that need the
 * game's behaviour rewritten rather than its numbers - the explode-o-nut / tall nut damage hooks, the
 * seed-packet type rewrite and the type-check remaps - are deliberately not ported yet; they are
 * listed in {@code ../verification/VERIFICATION.md} §56.
 *
 * <p>Nothing here changes the art: the mod registers no resource root and the engine reads the retail
 * {@code main.pak}, which is what the delivered remix needs (its loose files are only the Chinese
 * localisation of the retail art, not art of its own).
 *
 * <p>The batch that {@link #applyAdditions()} carries is the cross-checked one: the values of
 * {@code 95-edition-rules-additions.json}, which is authoritative where it disagrees with the older
 * {@code 95-edition-rules.json} - including the two batch-three values it corrects (the small sun's
 * worth and the coin an ordinary sky drop is). See {@code ../verification/VERIFICATION.md} §58 for what that
 * batch ports, what it measures and what it deliberately leaves to the batch after it.
 *
 * <p>{@link #applyBehaviourRules()} is that batch: the additions' behaviour rules - the mind-control
 * boost, the burn's clamp, the cold-effect rewrite, the shield/ladder branch, the refusal of a hiding
 * scary-shroom, the extra shot that moved from the cattail to the gatling pea, the tall nut's ram and
 * the explode-o-nut / tall-nut transformations. They are the ones the first four batches could not
 * carry, because they are rewrites of the game's own decisions rather than numbers; see
 * {@code ../verification/VERIFICATION.md} §60.
 *
 * <p>{@link #applyTorchwoodAndSpawnRules()} is the batch after it: the torchwood's conversion table
 * (a pea becomes a snow pea, a snow pea a fireball) and the ×4 zombie-points budget every level's waves
 * are composed with; see {@code ../verification/VERIFICATION.md} §63.
 *
 * <p>The Java original of this file was a final class of static members; it is a Kotlin `object` here,
 * because that is what the same thing is in Kotlin: one instance, reached as
 * `Edition95Rules.apply()` - which is also how a Java caller (the end-to-end harness) reaches it, as
 * `Edition95Rules.INSTANCE.apply()`. Everything else - the rules, their order, the offsets and the
 * confidence of every one of them - is unchanged.
 */
object Edition95Rules {

    /**
     * The bucket zombie's body animation: every track the zombie reanimation names {@code anim_bucket*}
     * is the pail, and showing it is what the game's own bucket zombie does.
     */
    private const val BUCKET_BODY_ANIMATION = "anim_bucket"

    /**
     * Registers the 95 Edition's rules with the engine. Idempotent, so a loader that runs the mod's
     * entry point twice ends up with exactly one registration per rule.
     */
    fun apply() {
        // 95 has no screen-door zombie at all: every ZOMBIE_DOOR spawns as a ZOMBIE_PAIL.
        // R-Z1, file 0x1225e6 (VA 0x5225e6): `if (type==6) type=4`, confidence high.
        ZombieOverrides.of(ZombieType.ZOMBIE_DOOR).spawnsAs(ZombieType.ZOMBIE_PAIL)

        // The flag zombie becomes a bucket-headed flag zombie: 820 body, a pail, and the bucket art.
        // R-Z4, file 0x1227b5 (VA 0x5227b5): mBodyHealth 270 -> 820, confidence high.
        // R-Z5, file 0x123299 (VA 0x523298) -> cave 0x4009e6: `anim_bucket` (string @0x668ebc) and
        // mHelmType=HELM_PAIL, mHelmHealth=mHelmMaxHealth=1100, confidence high.
        val aFlag: ZombieOverride = ZombieOverrides.of(ZombieType.ZOMBIE_FLAG)
        aFlag.bodyHealth(820)
        aFlag.helm(HelmType.HELMTYPE_PAIL, 1100)
        aFlag.bodyAnimation(BUCKET_BODY_ANIMATION)

        // The bucket zombie's pail is pinned to the retail 1100: 95's patched code writes the type and
        // the health explicitly instead of trusting the type's own setup.
        // R-Z2, file 0x12263c (VA 0x52263c): mHelmType = HELM_PAIL(2), confidence high.
        // R-Z3, file 0x1226f1 (VA 0x5226f1): mHelmHealth = 1100 (0x44c), confidence high.
        ZombieOverrides.of(ZombieType.ZOMBIE_PAIL).helm(HelmType.HELMTYPE_PAIL, 1100)

        // The football zombie's helmet is twice as hard: 1400 -> 2800. Only the health changes; the
        // helmet type stays HELMTYPE_FOOTBALL.
        // file 0x122bb0 (VA 0x522baa), the `cmp eax,7` / mHelmType==3 branch: `mov [edi+0xd0],0x578` ->
        // `0xaf0`, confidence high.
        ZombieOverrides.of(ZombieType.ZOMBIE_FOOTBALL).helm(HelmType.HELMTYPE_FOOTBALL, 2800)

        // The newspaper zombie's newspaper is 8x as tough: 150 -> 1200. The shield itself (and the
        // MADDENING phase that follows when it goes) is the game's own.
        // file 0x123377 (VA 0x523377), the `cmp ecx,5` / mShieldType==2 branch:
        // `mov [edi+0xdc],0x96` -> `0x4b0`, confidence high.
        ZombieOverrides.of(ZombieType.ZOMBIE_NEWSPAPER).shieldHealth(1200)

        // The dancing zombie's body is 500 -> 1350.
        // file 0x12352a (VA 0x52352a), the `cmp ecx,8` / mZombiePhase==0x28 branch:
        // `mov [edi+0xc8],0x1f4` -> `0x546`, confidence high.
        ZombieOverrides.of(ZombieType.ZOMBIE_DANCER).bodyHealth(1350)

        // The bungee zombie lands straight away: the landing counter is 0 instead of 300. In 95 the
        // write is in BungeeLanding (file 0x125127, VA 0x525124, confidence medium); the zombie sensor
        // the API has is the spawn, so the counter is cleared when the zombie is born - see
        // ../verification/VERIFICATION.md §55 for that deviation.
        ZombieOverrides.of(ZombieType.ZOMBIE_BUNGEE).phaseCounter(0)

        // ---- the plant numbers (95-edition-rules.json "plants") ----
        // Every entry writes into the plant's definition row in Plant.gPlantDefs - the table the game
        // reads the seed-packet price, the recharge and the firing cadence from (PlantOverrides#cost /
        // #refreshTime / #launchRate), so the write is live from the mod entry point on.

        // The sun producers pay out more slowly: sunflower and sun-shroom 2500 -> 3300.
        // file 0x29f2f0 (VA 0x69f2f0) gPlantDefs[1].mLaunchRate, confidence high.
        PlantOverrides.of(SeedType.SEED_SUNFLOWER).launchRate(3300)
        // file 0x29f410 (VA 0x69f410) gPlantDefs[9].mLaunchRate, confidence high.
        PlantOverrides.of(SeedType.SEED_SUNSHROOM).launchRate(3300)

        // The potato mine is twice as expensive: 25 -> 50.
        // file 0x29f350 (VA 0x69f350) gPlantDefs[4].mSeedCost, confidence high.
        PlantOverrides.of(SeedType.SEED_POTATOMINE).cost(50)

        // The grave buster is cheaper: 75 -> 50.
        // file 0x29f44c (VA 0x69f44c) gPlantDefs[11].mSeedCost, confidence high.
        PlantOverrides.of(SeedType.SEED_GRAVEBUSTER).cost(50)

        // The hypnotised-shroom is dearer: 75 -> 100.
        // file 0x29f470 (VA 0x69f470) gPlantDefs[12].mSeedCost, confidence high.
        PlantOverrides.of(SeedType.SEED_HYPNOSHROOM).cost(100)

        // The tall nut costs 175 and recharges faster: 125 -> 175 and 3000 -> 2000.
        // file 0x29f5fc (VA 0x69f5fc) mSeedCost, file 0x29f600 (VA 0x69f600) mRefreshTime, high.
        PlantOverrides.of(SeedType.SEED_TALLNUT).cost(175).refreshTime(2000)

        // The sea-shroom recharges faster: 3000 -> 1500.
        // file 0x29f624 (VA 0x69f624) gPlantDefs[24].mRefreshTime, confidence high.
        PlantOverrides.of(SeedType.SEED_SEASHROOM).refreshTime(1500)

        // The cactus costs 200: 125 -> 200.
        // file 0x29f668 (VA 0x69f668) gPlantDefs[26].mSeedCost, confidence high.
        PlantOverrides.of(SeedType.SEED_CACTUS).cost(200)

        // The blover costs 200 and recharges slower: 100 -> 200 and 750 -> 2000.
        // file 0x29f68c (VA 0x69f68c) mSeedCost, file 0x29f690 (VA 0x69f690) mRefreshTime, high.
        PlantOverrides.of(SeedType.SEED_BLOVER).cost(200).refreshTime(2000)

        // The starfruit costs 250 and shoots more slowly: 125 -> 250 and 150 -> 200.
        // file 0x29f6d4 (VA 0x69f6d4) mSeedCost, file 0x29f6e0 (VA 0x69f6e0) mLaunchRate, high.
        PlantOverrides.of(SeedType.SEED_STARFRUIT).cost(250).launchRate(200)

        // The marigold costs 75, recharges faster and pays out more slowly:
        // 50 -> 75, 3000 -> 1500 and 2500 -> 3300.
        // file 0x29f818 (VA 0x69f818) mSeedCost, file 0x29f81c (VA 0x69f81c) mRefreshTime,
        // file 0x29f824 (VA 0x69f824) mLaunchRate, confidence high.
        PlantOverrides.of(SeedType.SEED_MARIGOLD).cost(75).refreshTime(1500).launchRate(3300)

        // The gatling pea costs almost twice as much: 250 -> 450.
        // file 0x29f860 (VA 0x69f860) gPlantDefs[40].mSeedCost, confidence high.
        PlantOverrides.of(SeedType.SEED_GATLINGPEA).cost(450)

        // The cattail costs 275 and fires half again as fast: 225 -> 275 and 150 -> 75.
        // file 0x29f8cc (VA 0x69f8cc) mSeedCost, file 0x29f8d8 (VA 0x69f8d8) mLaunchRate, high.
        PlantOverrides.of(SeedType.SEED_CATTAIL).cost(275).launchRate(75)

        // The explode-o-nut is no longer free: 0 -> 150.
        // file 0x29f9a4 (VA 0x69f9a4) gPlantDefs[49].mSeedCost, confidence high.
        PlantOverrides.of(SeedType.SEED_EXPLODE_O_NUT).cost(150)

        // The spikerock is born with 36 times the health: 450 -> 16200. Unlike the rows above this is
        // a spawn number, not a table entry: 95 patches the per-seed branch of the plant's
        // initialisation (`mov [ebx+0x40], 0x1c2` -> `0x3f48`), which is the port's
        // `case SeedType.SEED_SPIKEROCK: mPlantHealth = 450;`. 16200 is the "450->16200" entry of the
        // JSON's global section (md report §4, cluster 74).
        // file 0x5e5c3 (VA 0x45e5c0), confidence high.
        PlantOverrides.of(SeedType.SEED_SPIKEROCK).bodyHealth(16200)

        // ---- the projectile damages (95-edition-rules.json "projectiles") ----
        // A projectile's damage is a table row too (Projectile.gProjectileDefinition), read at impact
        // time, so these are two writes and no Mixin at all.

        // The starfruit's star hits twice as hard: 20 -> 40.
        // file 0x29f21c (VA 0x69f21c) gProjectileDefinition[7].mDamage, confidence high.
        ProjectileOverrides.of(ProjectileType.PROJECTILE_STAR).damage(40)

        // The spike - the projectile the cactus and the cattail fire (Plant::Fire's
        // SEED_CACTUS / SEED_CATTAIL branch, Projectile.cpp:38-51 index 8) - is cut to 1: 20 -> 1.
        // file 0x29f228 (VA 0x69f228) gProjectileDefinition[8].mDamage, confidence high.
        ProjectileOverrides.of(ProjectileType.PROJECTILE_SPIKE).damage(1)

        applyBoardRules()
    }

    /**
     * The third batch: the {@code waves} and {@code global} sections of
     * {@code 95-edition-rules.json} - what a mode allows, when a zombie may appear, the five cells of
     * the allowed-levels table, and the hand-full of global numbers. Every rule below carries its
     * cluster / VA / byte evidence the same way the two batches above do.
     */
    private fun applyBoardRules() {
        // ---- A. the extra allowed zombies (R-WAVE3's seven hooks) ----
        // 95 patches the tail of each GameMode branch of Challenge::InitZombieWaves to set further
        // mZombieAllowed[] indices (Board+0x54d4+idx) before the wave picker reads the array.

        // 0x4258a8 -> cave 0x4004b4: CHALLENGE_SPEED(29) also allows FOOTBALL(7), JACK(15), LADDER(21).
        BoardOverrides.allowZombies(
            GameMode.GAMEMODE_CHALLENGE_SPEED, ZombieType.ZOMBIE_FOOTBALL,
            ZombieType.ZOMBIE_JACK_IN_THE_BOX, ZombieType.ZOMBIE_LADDER,
        )

        // 0x4258bb -> cave 0x4006b8: POGO_PARTY(34) also allows NORMAL(0), CONE(2), PAIL(4),
        // NEWSPAPER(5), DOOR(6), FOOTBALL(7), ZAMBONI(12), GARGANTUAR(23).
        BoardOverrides.allowZombies(
            GameMode.GAMEMODE_CHALLENGE_POGO_PARTY, ZombieType.ZOMBIE_NORMAL,
            ZombieType.ZOMBIE_TRAFFIC_CONE, ZombieType.ZOMBIE_PAIL, ZombieType.ZOMBIE_NEWSPAPER,
            ZombieType.ZOMBIE_DOOR, ZombieType.ZOMBIE_FOOTBALL, ZombieType.ZOMBIE_ZAMBONI,
            ZombieType.ZOMBIE_GARGANTUAR,
        )

        // 0x425984 -> cave 0x4004fd: SUNNY_DAY(37) also allows DOOR(6), LADDER(21).
        BoardOverrides.allowZombies(
            GameMode.GAMEMODE_CHALLENGE_SUNNY_DAY, ZombieType.ZOMBIE_DOOR,
            ZombieType.ZOMBIE_LADDER,
        )

        // 0x425aae -> cave 0x4006ed: the CHALLENGE_BEGHOULED branch - which the port, like upstream,
        // shares between BEGHOULED(20) and BEGHOULED_TWIST(24) - also allows POLEVAULTER(3), CONE(2),
        // PAIL(4), JACK(15). The hook sits in the branch the JSON calls "0x14(20) GAMEMODE_CHALLENGE_
        // BEGHOULED 邻域", so both modes of that branch get them.
        BoardOverrides.allowZombies(
            GameMode.GAMEMODE_CHALLENGE_BEGHOULED, ZombieType.ZOMBIE_POLEVAULTER,
            ZombieType.ZOMBIE_TRAFFIC_CONE, ZombieType.ZOMBIE_PAIL, ZombieType.ZOMBIE_JACK_IN_THE_BOX,
        )
        BoardOverrides.allowZombies(
            GameMode.GAMEMODE_CHALLENGE_BEGHOULED_TWIST, ZombieType.ZOMBIE_POLEVAULTER,
            ZombieType.ZOMBIE_TRAFFIC_CONE, ZombieType.ZOMBIE_PAIL, ZombieType.ZOMBIE_JACK_IN_THE_BOX,
        )

        // 0x425bae -> cave 0x4003d0: WAR_AND_PEAS(16) also allows NEWSPAPER(5), JACK(15), BALLOON(16),
        // POGO(18), GARGANTUAR(23).
        BoardOverrides.allowZombies(
            GameMode.GAMEMODE_CHALLENGE_WAR_AND_PEAS, ZombieType.ZOMBIE_NEWSPAPER,
            ZombieType.ZOMBIE_JACK_IN_THE_BOX, ZombieType.ZOMBIE_BALLOON, ZombieType.ZOMBIE_POGO,
            ZombieType.ZOMBIE_GARGANTUAR,
        )

        // 0x425c75 -> cave 0x4008e1: the branch whose GameMode is ICE(42) also allows NORMAL(0),
        // PAIL(4), NEWSPAPER(5), DANCER(8), SNORKEL(11), DOLPHIN_RIDER(14), DIGGER(17).
        BoardOverrides.allowZombies(
            GameMode.GAMEMODE_CHALLENGE_ICE, ZombieType.ZOMBIE_NORMAL, ZombieType.ZOMBIE_PAIL,
            ZombieType.ZOMBIE_NEWSPAPER, ZombieType.ZOMBIE_DANCER, ZombieType.ZOMBIE_SNORKEL,
            ZombieType.ZOMBIE_DOLPHIN_RIDER, ZombieType.ZOMBIE_DIGGER,
        )

        // 0x425cdf -> cave 0x400476: the branches the JSON names as "0x17/0x18", i.e. the GameModes
        // 0x17(23) ZOMBIQUARIUM and 0x18(24) BEGHOULED_TWIST, also allow FLAG(1), POLEVAULTER(3),
        // LADDER(21). (The report could not pin a neighbouring `cmp` for this hook, so the two modes
        // are the JSON's own hex values read as GameMode ids.)
        BoardOverrides.allowZombies(
            GameMode.GAMEMODE_CHALLENGE_ZOMBIQUARIUM, ZombieType.ZOMBIE_FLAG,
            ZombieType.ZOMBIE_POLEVAULTER, ZombieType.ZOMBIE_LADDER,
        )
        BoardOverrides.allowZombies(
            GameMode.GAMEMODE_CHALLENGE_BEGHOULED_TWIST, ZombieType.ZOMBIE_FLAG,
            ZombieType.ZOMBIE_POLEVAULTER, ZombieType.ZOMBIE_LADDER,
        )

        // ---- B. the two spawn vetoes are gone (R-WAVE1) ----
        // file 0xd6a3 / 0xd6ac (VA 0x40d6a3 / 0x40d6ac): `jge`->`jmp` and `je`->`nop nop` in
        // Board::CanZombieSpawnOnLevel drop "theLevel < mStartingLevel" and "mPickWeight == 0"; only
        // gZombieAllowedLevels is left to decide. confidence high.
        BoardOverrides.relaxZombieSpawnRules(true)

        // ---- C. the five gZombieAllowedLevels cells (R-WAVE2) ----
        // Table VA 0x6a35b0, 0xcc bytes per row = {mZombieType, mAllowedOnLevel[50]}.
        // file 0x2a3894 (VA 0x6a3894): ZOMBIE_POLEVAULTER level 32(4-2) false -> true, confidence high.
        BoardOverrides.allowZombieOnLevel(ZombieType.ZOMBIE_POLEVAULTER, 32, true)
        // file 0x2a39d8 / 0x2a39dc: ZOMBIE_NEWSPAPER levels 11(2-1) and 12(2-2) true -> false, high.
        BoardOverrides.allowZombieOnLevel(ZombieType.ZOMBIE_NEWSPAPER, 11, false)
        BoardOverrides.allowZombieOnLevel(ZombieType.ZOMBIE_NEWSPAPER, 12, false)
        // file 0x2a39ec: ZOMBIE_NEWSPAPER level 16(2-6) false -> true, confidence high.
        BoardOverrides.allowZombieOnLevel(ZombieType.ZOMBIE_NEWSPAPER, 16, true)
        // file 0x2a3bc4 (VA 0x6a3bc4): ZOMBIE_FOOTBALL level 32(4-2) true -> false, confidence high.
        BoardOverrides.allowZombieOnLevel(ZombieType.ZOMBIE_FOOTBALL, 32, false)

        // ---- D. the global numbers ----
        // R-BEG, clusters 39/40/41/43: Beghouled / Beghouled Twist are won at 100, and the "almost
        // there" hint (the game's own score-5) moves 70 -> 95 with it.
        // file 0x211f5 (push 0x4b -> 0x64) / 0x21287 (0x46 -> 0x5f) / 0x2131d (0x4b -> 0x64) /
        // 0x22a25 (lea +0x4b -> +0x64), confidence high.
        BoardOverrides.beghouledWinningScore(100)

        // R-MODE cluster 33: the instant kill inside Board::KillAllZombiesInRadius 1800 -> 2400.
        // file 0x1d931 (VA 0x41d930) `push 0x708` -> `push 0x960`, confidence medium.
        BoardOverrides.killAllZombiesDamage(2400)

        // R-MODE cluster 46: Challenge::UpdateRainingSeeds drops its seed packet every 500..999
        // instead of drawing from that range - the interval becomes 200..699.
        // file 0x234e7 (VA 0x4234e6) `add eax,0x1f4` -> `0xc8`, confidence high.
        BoardOverrides.rainSeedInterval(200, 699)

        // R-MODE cluster 29: Board::StageHasGraveStones is always false (95 has no grave stones).
        // file 0x1c078 (VA 0x41c078) `je` -> `jmp`, confidence high.
        BoardOverrides.stageHasGraveStones(false)

        // R-MODE cluster 66: those stages carry 10 sun coins worth 10, 25 and 50 - which 95 doubles to
        // 20, 50 and 75 (Coin::GetSunValue).
        // file 0x309f0 (VA 0x4309f0) 0x19->0x32, 0x0a->0x14, 0x32->0x4b, confidence high.
        // (The port's small sun is worth 15 where the 95 exe's is 10; the three targets - 50, 20, 75 -
        // are what the patch puts in, and that is what is registered here.)
        BoardOverrides.sunValues(50, 20, 75)

        // R-MODE cluster 27: Board::AddSunMoney's ceiling is lifted from 9990 to 2e9.
        // file 0x1b96e (VA 0x41b96c) `cmp ecx,0x2706` -> `0x77359400`, confidence high.
        BoardOverrides.sunMoneyCap(2_000_000_000)

        // R-CONV clusters 44/45: Challenge::UpdateConveyorBelt's Portal Combat list swaps
        // PEASHOOTER(0)->THREEPEATER(18), WALLNUT(3)->EXPLODE_O_NUT(49), CHERRYBOMB(2)->DOOMSHROOM(15);
        // the Invisighoul list swaps PEASHOOTER->THREEPEATER and SQUASH(17)->CHERRYBOMB(2).
        // file 0x231b4 (VA 0x4231b4) + cave 0x400638, file 0x2325a (VA 0x42325a) + cave 0x4004e8, high.
        BoardOverrides.conveyorSeed(
            GameMode.GAMEMODE_CHALLENGE_PORTAL_COMBAT, SeedType.SEED_PEASHOOTER,
            SeedType.SEED_THREEPEATER,
        )
        BoardOverrides.conveyorSeed(
            GameMode.GAMEMODE_CHALLENGE_PORTAL_COMBAT, SeedType.SEED_WALLNUT,
            SeedType.SEED_EXPLODE_O_NUT,
        )
        BoardOverrides.conveyorSeed(
            GameMode.GAMEMODE_CHALLENGE_PORTAL_COMBAT, SeedType.SEED_CHERRYBOMB,
            SeedType.SEED_DOOMSHROOM,
        )
        BoardOverrides.conveyorSeed(
            GameMode.GAMEMODE_CHALLENGE_INVISIGHOUL, SeedType.SEED_PEASHOOTER,
            SeedType.SEED_THREEPEATER,
        )
        BoardOverrides.conveyorSeed(
            GameMode.GAMEMODE_CHALLENGE_INVISIGHOUL, SeedType.SEED_SQUASH,
            SeedType.SEED_CHERRYBOMB,
        )

        // R-MODE cluster 18: Board::UpdateSunSpawning's special mode moves from SUNNY_DAY(37) to
        // AIR_RAID(41), and what falls is no longer a big sun but a usable seed packet.
        // file 0x13bd7 (VA 0x413bd7) `cmp ...,0x25` -> `0x29` and `mov eax,6` -> `0x10`, confidence high.
        BoardOverrides.sunSpawning(GameMode.GAMEMODE_CHALLENGE_AIR_RAID, CoinType.COIN_USABLE_SEED_PACKET)

        // R-SD2, 10 hooks + 0x48461b: SeedChooserScreen::SeedNotAllowedToPick gains the AIR_RAID(41)
        // mode and SEED_MARIGOLD(38). confidence high.
        BoardOverrides.seedNotAllowedToPick(GameMode.GAMEMODE_CHALLENGE_AIR_RAID, SeedType.SEED_MARIGOLD)

        // R-MODE cluster 30: Board::SeedNotRecommendedForLevel's Grave Buster "needs grave stones" bit
        // is inverted (marked not-recommended exactly when the stage does have grave stones).
        // file 0x1ccf1 (VA 0x41ccf1) `jne` -> `je`, confidence high.
        BoardOverrides.graveBusterNeedsGravesInverted(true)

        applyAdditions()
    }

    /**
     * The fourth batch: the cross-checked additions of
     * {@code 95-edition-rules-additions.json} - the data / value half of it (its {@code plants},
     * {@code global} and {@code waves} sections' numbers) plus the two values batch three registered
     * wrongly, and the {@code mNumWaves} table. The behaviour rules of the same file (the mind-control
     * boost, the burn predicate, the cold-effect and shield rewrites, the plant-target rejection, the
     * extra shot's type, the tall-nut ram, the seed-packet rewrite, the Scary Potter level data) are
     * left to the batch after this one; they are listed in {@code ../verification/VERIFICATION.md} §58.
     *
     * <p>Every value is the one the addition's evidence carries, and the rule names the file offset or
     * the injected-code address it was read from, exactly like the batches above.
     */
    private fun applyAdditions() {
        // ---- A. the two corrections batch three registered wrongly ----

        // Coin::GetSunValue's small sun is 25, not 20: the additions' `{COIN_SUN: 50, COIN_SMALLSUN:
        // 25, COIN_LARGESUN: 75}`, where batch three had followed the old reverse-engineering's
        // `50/20/75`. The old reading took the displacements of three `lea` instructions for the values
        // (they are `5+10` and `5+20`, i.e. 15 and 25); the patched immediates are what the coin is worth.
        // file 0x309f0 (VA 0x4309f0) `0x19->0x32`, file 0x309fd `8d480a->8d4814` (15 -> 25),
        // file 0x30a0d `83e132->83e14b`, confidence high.
        BoardOverrides.sunValues(50, 25, 75)

        // The ordinary sky drop is a small sun, not a normal one: what falls from the sky on every
        // stage that is not the special mode (batch three moved that mode from Sunny Day to Air Raid)
        // is COIN_SMALLSUN, worth 25 under the correction above - "the small sun in the sky is 25".
        // file 0x2ba09 (VA 0x42ba08) `6a 04 -> 6a 05` (`Board::AddCoin(..., COIN_SUN, ...)` ->
        // `COIN_SMALLSUN`), confidence high.
        BoardOverrides.skyDropCoin(CoinType.COIN_SMALLSUN)

        // ---- B. the plant values ----

        // The potato mine arms twice as fast: the per-seed branch of Plant::PlantInitialize gives it
        // 1000 (10s) instead of 1500 (15s), in the game's 10ms counter unit.
        // file 0x5e34b (VA 0x45e34b) `mov [eax+0x54],0x5dc -> 0x3e8`, confidence high.
        // Its blast is the cherry bomb's: radius 60 -> 115, its own row -> one row either side, and it
        // burns instead of merely damaging (the game's own burn, i.e. the ash effect, which the retail
        // mine does not use). The blast's position stays the plant's own.
        // file 0x66a5f-0x66a67 (VA 0x466a5f) `6a3c -> 6a73` (60 -> 115), `6a00 -> 6a01` twice
        // (row range 0 -> 1, burn false -> true); the call is the plant's DoSpecial potato-mine branch
        // (VA 0x41d8a0 = Board::KillAllZombiesInRadius), confidence high.
        PlantOverrides.of(SeedType.SEED_POTATOMINE).spawnCountdown(1000).explosionBlast(115, 1, true)

        // The sun-shroom grows in 90s instead of 120s.
        // file 0x5e3ee (VA 0x45e3ee) `mov [ebx+0x54],0x2ee0 -> 0x2328`, confidence high.
        PlantOverrides.of(SeedType.SEED_SUNSHROOM).spawnCountdown(9000)

        // The chomper digests in 25s instead of 40s, and the third type it bites instead of eating
        // changes from the boss to the football zombie - so it swallows a boss and only bites a
        // football zombie (the gargantuar and the red-eye gargantuar keep being bitten, as they were).
        // file 0x6154e (VA 0x46154e) `mov [edi+0x54],0xfa0 -> 0x9c4` for the pause, file 0x61451
        // (VA 0x461451) `83 f8 19 -> 83 f8 07` (`cmp eax, ZOMBIE_BOSS` -> `ZOMBIE_FOOTBALL`),
        // confidence high.
        // The chomper's mouth reaches two cells as well (user list 1; no byte of its own): the game's
        // attack rectangle for it ends 120 pixels ahead of the plant, and its search accepts a zombie
        // only while that rectangle and the zombie's own overlap - so a zombie standing two cells away
        // is out of reach (the one cell a player sees) until the rectangle is widened to 190.
        PlantOverrides.of(SeedType.SEED_CHOMPER).digestCountdown(2500)
            .biteInsteadOfEatZombieType(ZombieType.ZOMBIE_FOOTBALL)
            .attackRectExtraWidth(CHOMPER_ATTACK_EXTRA_WIDTH)

        // The spikerock takes 1800 per hit instead of 50 and cracks at 10800 and 5400 instead of 300
        // and 150, i.e. the same ×36 the batch-two health (450 -> 16200) is scaled by - nine hits kill
        // it, as nine did before.
        // cave 0x4003b8 `sub [esi+0x40],0x708` (1800), file 0x5ec6a `cmp eax,0x12c -> 0x2a30`
        // (300 -> 10800), file 0x5ec7d `cmp [esi+0x40],0x96 -> 0x1518` (150 -> 5400), confidence high.
        PlantOverrides.of(SeedType.SEED_SPIKEROCK).spikeRockDamage(1800, 10800, 5400)

        // The marigold pays out sun instead of silver and gold coins: about half the time a normal sun
        // (COIN_SUN, 50) and otherwise a large one (COIN_LARGESUN, 75), and in a Big Time level the
        // coin it pays on top is a normal sun as well.
        // file 0x5fafa `lea esi,[eax-0x25] -> [eax-0x22]` (COIN_SILVER -> COIN_SUN), file 0x5fb05
        // `cmp eax,0xa -> 0x32` (the rare roll: 10 out of 100 -> 50), file 0x5fb0a `mov esi,2 -> 6`
        // (COIN_GOLD -> COIN_LARGESUN), confidence high; the Big Time branch file 0x5fb5b
        // (VA 0x45fb5b) `push 1 -> push 4` (COIN_SILVER -> COIN_SUN), confidence medium. (The
        // additions file and the cross-check name that branch "Last Stand"; the byte it compares is
        // 0x27 = 39, which is GAMEMODE_CHALLENGE_BIG_TIME - the port's own Big Time branch is the one
        // that pays a marigold a silver coin, so that is the site this registers.)
        PlantOverrides.of(SeedType.SEED_MARIGOLD)
            .producedCoins(CoinType.COIN_SUN, CoinType.COIN_LARGESUN, 50)

        // ---- C. the board values ----

        // A zombie's death loot is the whack-a-zombie sun drop on every level, and its three coins are
        // a small sun, a large sun and a normal sun - 25 + 75 + 50 = 150, the "at most 150" of the user
        // list, and no silver or gold coin among them.
        // file 0x1cf10 (VA 0x41cf10) `74 18 -> eb 18` (the `IsWhackAZombieLevel()` test becomes
        // unconditional), file 0x1cfe6 (VA 0x41cfe5) `6a 04 -> 6a 05`, file 0x1cff6 (VA 0x41cff5)
        // `6a 04 -> 6a 06` (the third coin keeps its `push 4`), confidence high.
        BoardOverrides.dropLootSunBranch(true)
        BoardOverrides.dropLootCoins(CoinType.COIN_SMALLSUN, CoinType.COIN_LARGESUN, CoinType.COIN_SUN)

        // A Last Stand level starts with 8000 sun instead of 5000 (the method's only large starting
        // bank; every other branch of Board::InitLevel starts a level on 0, 150 or 50).
        // file 0x40b052 (VA 0x40b052) `mov [ebp+0x5560],0x1388 -> 0x1f40`, confidence high.
        BoardOverrides.lastStandInitialSun(8000)

        // A coin is born carrying the blover as its usable seed type instead of "no seed": the packet an
        // air-raid coin hands the cursor when nothing named one.
        // file 0x2ffb9 (VA 0x42ffb6) `mov [ebp+0x68],0xffffffff -> 0x1b` (SEED_NONE -> SEED_BLOVER),
        // confidence high.
        BoardOverrides.coinUsableSeedType(SeedType.SEED_BLOVER)

        // The wave-count table (Board+0x5564), five branches: a normal survival stage and Last Stand
        // 10 -> 20 waves, a hard or endless survival stage (game modes 6..10) 20 -> 40, a
        // whack-a-zombie challenge 12 -> 20, the 30-wave group 30 -> 40, and every remaining challenge
        // 40 -> 50.
        // file 0x92fd (VA 0x4092fd, modes 6..10) 0x14->0x28; file 0x93f2 0x0c->0x14; file 0x9466
        // 0x28->0x32; file 0x9472 0x1e->0x28; file 0x9499 0x0a->0x14, confidence medium (the function
        // the five writes live in, VA 0x4092e0, is Board::PickZombieWaves' neighbourhood, and the field
        // is the one the level's wave count is read from).
        //
        // The 0x92fd immediate is shared: additions-2 attributes two more of its readers to it - the
        // floor a second-run adventure level is raised to (0x409394, `mNumWaves < 10 ? 20 : mNumWaves+10`)
        // and the group of seven 20-wave challenges (0x409400-0x409420: Wall-nut Bowling, Air Raid,
        // Grave Danger, High Gravity, Portal Combat, War and Peas, Invisighoul), besides the survival
        // hard stage batch four already covers. One immediate, so the single 20 -> 40 registration
        // reaches all of them; §58 recorded the group as "not among the patched values, stays 20",
        // which additions-2 corrects (see ../verification/VERIFICATION.md §64).
        BoardOverrides.numWaves(10, 20)
        BoardOverrides.numWaves(20, 40)
        BoardOverrides.numWaves(12, 20)
        BoardOverrides.numWaves(30, 40)
        BoardOverrides.numWaves(40, 50)

        applyBehaviourRules()
        applyTorchwoodAndSpawnRules()
    }

    /**
     * The sixth batch: the torchwood's own conversion table and the zombie-points budget of a wave.
     *
     * <p>Both are rewrites of a decision rather than a number. The torchwood's is a table with two rows
     * - which projectile type it converts and what it converts it to - and 95 moves both rows; the
     * wave's is the factor the composition of a wave multiplies its points budget by, and 95 makes the
     * arm that applies the ×4 one the arm every level takes. Neither is a number in a table the game
     * reads, so neither could be a definition-row write like the batches before this one.
     */
    private fun applyTorchwoodAndSpawnRules() {
        // ---- A. the torchwood's conversion ----
        // The retail pair is "pea -> fireball, snow pea -> pea". 95 swaps the two branches and rewrites
        // the type the second one writes back:
        //   file 0x606ad (VA 0x4606ad) `75 0f -> 74 0f` and file 0x606c1 (VA 0x4606c1)
        //   `75 0d -> 74 ec` (Plant::UpdateTorchwood's two `jne`s become `je`s, so each type takes the
        //   other branch's conversion);
        //   file 0x6ee12 (VA 0x46ee12, the immediate at 0x6ee15) `c7 47 5c 00 00 00 00 ->
        //   c7 47 5c 01 00 00 00` (Projectile::ConvertToPea writes PROJECTILE_SNOWPEA(1) instead of
        //   PROJECTILE_PEA(0)).
        // Projectile::ConvertToFireball (VA 0x46ecb0) is untouched: it still writes PROJECTILE_FIREBALL(6)
        // and still attaches REANIM_FIRE_PEA. So a pea becomes a snow pea (20 damage, DAMAGE_FREEZE) and
        // a snow pea becomes a fireball, confidence high.
        ProjectileOverrides.torchwoodConversion(ProjectileType.PROJECTILE_PEA, ProjectileType.PROJECTILE_SNOWPEA)
        ProjectileOverrides.torchwoodConversion(ProjectileType.PROJECTILE_SNOWPEA, ProjectileType.PROJECTILE_FIREBALL)

        // ---- B. the wave's zombie-points budget ----
        // A wave is composed by spending a budget of zombie points, and the game multiplies that budget
        // by a per-level factor: x6 for Column Like You See 'Em, x4 for Little Trouble and Wall-nut
        // Bowling, x3 for a mini-boss or an adventure Stormy Night, x2 for Shovel / Bungee Blitz /
        // Portal Combat / Invisighoul, x1 otherwise. 95 makes the x4 arm the arm every level takes:
        //   file 0x988a (VA 0x40988a) `0f 84 d0 00 00 00 -> e9 d1 00 00 00 90` turns the conditional
        //   jump that skips that arm's mode test into an unconditional one (Board::PickZombieWaves);
        //   the arm's factor itself (VA 0x409960/0x409962, the two `add ecx,ecx` = x4) is unchanged, and
        //   so is the x6 Column arm tested before it - which is why a Column level keeps x6 and every
        //   other level ends up at x4 (the x3 and x2 arms sit after the patched jump and go dead).
        // The 1-1 the user watches is the clearest case: its four waves spend 1, 1, 1 and 2 points on the
        // retail factor and 4, 4, 4 and 8 on this one, confidence high.
        BoardOverrides.spawnMultiplier(4)

        applyGraveRules()
    }

    /**
     * The seventh batch: the grave mode, the whack-a-zombie wave's zombie count, the background two
     * game modes are played on, and the two further readers of the wave-count table's shared immediate
     * - the {@code grave}, {@code challenge}, {@code global} and {@code waves} sections of
     * {@code 95-edition-rules-additions-2.json}, all cross-checked byte for byte in
     * {@code ../verification/research/95-edition-article-crosscheck.md}.
     *
     * <p>Everything here is a rule of the board rather than of a plant, a zombie or a projectile: which
     * level the grave branch runs on and how late it starts, how many zombies a whack-a-zombie wave
     * rises, which lawn a mode is played on, and how many waves a level is worth. See
     * {@code ../verification/VERIFICATION.md} §64 for the measurements and for what the batch does not do.
     */
    private fun applyGraveRules() {
        // ---- A. the grave mode, globalised (files 0x268f1 / 0x268fa -> cave 0x40069b / 0x426925) ----
        // Retail raises a grave stone and rises a zombie out of every grown one only in
        // GAMEMODE_CHALLENGE_GRAVE_DANGER. 95 defuses that guard (file 0x268f1, VA 0x4268f1:
        // `83 b9 f8 07 00 00 2d -> ...16` turns the compared mode 45 GRAVE_DANGER into 22
        // SEEING_STARS) and enters the branch body from an injected hook (file 0x268fa, 5 bytes ->
        // cave 0x40069b) that only steps aside when the board's own switch byte is 1
        // (`cmp byte [ebx+0x5766],1; je 0x42692e`) - so on every level: after the first flag wave the
        // level grows one grave stone per wave and each flag wave rises a zombie out of every grave
        // stone. The same version raises the growth threshold from `mCurrentWave > 5` to `> 9`
        // (file 0x426925), which with the flag waves at 9/19/29... means "after the first flag wave",
        // and the branch keeps its own `mCurrentWave != mNumWaves-1` (a level with a single flag wave
        // grows none) and its own exclusion arms: a War and Peas level rises no grave zombie at all,
        // a pool level rises its pool trio and a roof level its sky trio instead.
        // confidence high.
        BoardOverrides.graveModeEnabled(true)
        BoardOverrides.graveStoneWaveThreshold(9)

        // ---- B. the whack-a-zombie wave rises two (file 0x42618f, VA 0x42618f) ----
        // Challenge::WhackAZombieSpawning's `aZombieCount` base `c7 44 24 1c 01 00 00 00 ->
        // ... 02 00 00 00` (1 -> 2): each spawn rises two zombies out of two different grave stones
        // instead of one, still under the method's own final-wave 20 / triple 3 / double 2 arms and
        // still clamped to the grave stones it can pick from. confidence high.
        BoardOverrides.whackAZombieZombieCount(2)

        // ---- C. two modes are played on another lawn (file 0x40aae9, VA 0x40aae9) ----
        // Board::InitLevel's GameMode -> background case table (table VA 0x40aac0, jump table
        // VA 0x40aa98) moves two of its cells: GAMEMODE_CHALLENGE_AIR_RAID(41) from case 4 to case 1
        // (BACKGROUND_4_FOG -> BACKGROUND_1_DAY) and GAMEMODE_CHALLENGE_ICE(42) from case 1 to case 3
        // (BACKGROUND_1_DAY -> BACKGROUND_3_POOL). Everything downstream follows the background: the
        // plantable rows of the pool, the art group the level loads, and the lawn's own rules.
        // confidence high.
        BoardOverrides.modeBackground(GameMode.GAMEMODE_CHALLENGE_AIR_RAID, BackgroundType.BACKGROUND_1_DAY)
        BoardOverrides.modeBackground(GameMode.GAMEMODE_CHALLENGE_ICE, BackgroundType.BACKGROUND_3_POOL)
    }

    /**
     * The fifth batch: the behaviour rules of {@code 95-edition-rules-additions.json} - the
     * {@code mMindControlled_boost}, the {@code ApplyBurn} predicate, {@code RemoveColdEffects}, the
     * shield/ladder branch of {@code TakeDamage}, the plant-target rejection, the extra shot's seed
     * type, the tall-nut ram and the explode-o-nut / tall-nut transformations - i.e. everything the
     * four batches before this one left alone because changing a number was not enough.
     *
     * <p>Each rule is registered with the seam that owns it: a whole-game rule of {@code api-zombie}
     * ({@link ZombieOverrides}) or {@code api-plant} ({@link PlantOverrides}) where the game reads a
     * value, and - where the rule is a rewrite of a method rather than an answer - with a
     * {@link LawnHooks} implementation of this mod ({@link Edition95EatRules}), which is the seam the
     * game offers for exactly that ({@code ../verification/research/03-board-core.md §7.4}).
     */
    private fun applyBehaviourRules() {
        // ---- B1. the mind-controlled zombie is buffed (cave 0x400372 / 0x4002b0 / 0x40038e) ----
        // The hook file 0x12fa7c (VA 0x52fa7c, the `mov byte [esi+0xb8],1` that set mMindControlled) is
        // redirected to a cave that clears the chill counter, adds 200 to the body health and 200 to
        // the head-armour health, sets the scale to 1.25 and keeps mMindControlled=1. The cave's shield
        // arm (0x4002c1) is unreachable from the only entry (0x400388 jumps to the body arm and falls
        // through to the helmet one), so the shield is *not* touched - the correction the additions
        // file carries against the older reading.
        ZombieOverrides.mindControlBoost(200, 200, 1.25f)

        // The newspaper zombie's body health is a flat 920 there, not "its maximum plus 200":
        // cave 0x4002b0 `cmp [esi+0x24],5` / `mov ecx,0x398`, confidence high.
        ZombieOverrides.of(ZombieType.ZOMBIE_NEWSPAPER).mindControlBodyHealth(920)

        // ---- B2. the burn's clamp is the zombie's total health (hook file 0x132b96) ----
        // file 0x132b96 (VA 0x532b96) -> cave 0x4005ce: `mBodyHealth + mHelmHealth + mShieldHealth >=
        // 1800` (the game's own predicate looks at `mBodyHealth` alone), and the newspaper zombie's
        // walking and maddening phases take that arm whatever their health. 0x1d/0x1e are
        // PHASE_NEWSPAPER_READING and PHASE_NEWSPAPER_MADDENING; the zombie's mad phase (0x1f) is not
        // in the pair, so a newspaper zombie that is already raging burns like anything else.
        ZombieOverrides.burnImmunity(
            1800, ZombiePhase.PHASE_NEWSPAPER_READING,
            ZombiePhase.PHASE_NEWSPAPER_MADDENING,
        )

        // ---- B3. RemoveColdEffects writes 1000 instead of clearing the counter (file 0x132b51) ----
        // file 0x132b51 (VA 0x532b51) `cmp [esi+0xac],0 / jle` -> `mov [esi+0xac],0x3e8`, then
        // file 0x132b69 calls 0x52f050 (Zombie::UpdateAnimSpeed), confidence high.
        ZombieOverrides.coldRemovalChilledCounter(1000)

        // ---- B4. damage always goes through the shield (files 0x131844 / 0x131852) ----
        // file 0x131844 (VA 0x531844) `75 -> 70` (the `test bl,1; jne` that let a hit skip the shield
        // becomes `jo`, i.e. it never skips) and file 0x131852 `74 -> eb` (the `test bl,2; je` that
        // gave the body the full damage after the shield becomes an unconditional jump, i.e. it never
        // does). Both flags are the two PvzpCommon.testBit calls of Zombie::TakeDamage.
        ZombieOverrides.shieldAlwaysAbsorbs(true)

        // ---- B5. a hiding scary-shroom cannot be targeted (files 0x12e524 / 0x12e520) ----
        // file 0x12e524 (VA 0x52e524) `cmp [esi+0x134],2 -> cmp [esi+0x3c],0x15`: Plant::NotOnGround's
        // "rising with a bungee" clause (mOnBungeeState == RISING_WITH_BUNGEE, offset 0x134) becomes
        // "mState == 21" (STATE_SCAREDYSHROOM_SCARED, offset 0x3c), with the mSquished test before it
        // rewritten `cmp byte [esi+0x142],0 -> 1` + inverted branch (an equivalent-to-`!=0` change on a
        // bool). So the hiding scary-shroom is refused by the zombie's plant search, and the bungee
        // clause stops applying to every plant - the price the additions file records for this rule.
        PlantOverrides.notOnGroundState(PlantState.STATE_SCAREDYSHROOM_SCARED)

        // ---- B6. the extra shot at launch counter 50 moves from the cattail to the gatling pea ----
        // file 0x5f925 (VA 0x45f922) `cmp dword [esi+0x24],0x2b -> 0x28`: the one extra
        // FindTargetAndFire Plant::UpdateShooter makes when mLaunchCounter==50 now answers
        // SEED_GATLINGPEA(40) instead of SEED_CATTAIL(43). The per-burst chain (shooting counters
        // 18/35/51/68, i.e. four peas) is untouched by the move, so this is *not* "six peas a burst".
        PlantOverrides.extraFireSeedType(SeedType.SEED_GATLINGPEA)

        // ---- B7. the tall nut is ram-proof like the spikerock (files 0x126d72 / 0x12e96b) ----
        // file 0x126d72 (VA 0x526d72) -> cave 0x40095e and file 0x12e96b (VA 0x52e96b) -> cave 0x400977
        // both extend `[plant+0x24] == 0x2e(SEED_SPIKEROCK)` to `0x17(SEED_TALLNUT) || 0x2e`. The two
        // sites are Zombie::UpdateZombieGargantuar's smash test (the block pushes 0x14, i.e. the
        // gargantuar's 20 bounce damage, then damages the plant) and Zombie::SquishAllInSquare (the
        // whole-square squash a drive-over and a squash make). Both comparisons are answered from here,
        // because a plant that survives one and not the other is still squashed.
        PlantOverrides.of(SeedType.SEED_TALLNUT).crushProof(true)

        // ---- B8. the eaten plant: 8 a bite, and the two transformations (rule R-EX1) ----
        // The eat path and the level-start packet rewrite are *rewrites*, not values, so they are this
        // mod's own LawnHooks implementation rather than a registered number.
        if (!eatRulesInstalled) {
            eatRulesInstalled = true
            LawnHooksRegistry.add(Edition95EatRules())
        }

        // ---- B9. the starfruit's star: it flies straight along its row after its distance ----
        // Not a byte in the patch: the 95 edition's star leaves the plant on a diagonal, covers its
        // distance and then travels straight along the row it has reached, and the engine has no
        // number for that - the flight is the projectile's own state, so this is another of the mod's
        // LawnHooks implementations.
        if (!starRulesInstalled) {
            starRulesInstalled = true
            LawnHooksRegistry.add(Edition95StarRules())
        }

        // ---- B10. a butter hit also freezes (hook file 0x13273b -> cave 0x400ac4) ----
        // file 0x13273b (VA 0x53273b), the `mov [esi+0xb0],0x190` that writes mButteredCounter=400 in
        // Zombie::ApplyButter, is redirected to a cave that writes mIceTrapCounter=0x12c(300) and
        // mChilledCounter=0x3e8(1000) before it returns to the game's own instruction. The counters are
        // written behind the method's own guards (no head / unfreezable / zamboni / boss / bungee) and
        // before the animation speed the end of the method derives from them, so a buttered zombie is
        // frozen and slowed for an extra 300/1000 frames. confidence high.
        ZombieOverrides.butterCold(300, 1000)

        // ---- B11. the burn row freezes instead of unfreezing, and pays 1000 (file 0x664fe) ----
        // File 0x664fe (VA 0x4664fe) is inside Plant::BurnRow: where the game removes the cold effects
        // of each zombie the row covers (`mIceTrapCounter > 0` -> RemoveIceTrap, `mChilledCounter > 0`
        // -> clear + UpdateAnimSpeed), 95 writes mIceTrapCounter=0x2ee(750), mChilledCounter=0x9c4
        // (2500), calls UpdateAnimSpeed (0x52f050) and then Zombie::TakeDamage(1000) (0x5317c0). The
        // burn itself still runs - file 0x132fe5 wraps the TakeDamage(1800, 0x12) of Zombie::ApplyBurn
        // in a stub that zeroes the damage when the call comes from 0x46651a, i.e. exactly the burn
        // BurnRow makes - so the row deals 1000 instead of the clamp's 1800, and a zombie under the
        // clamp still burns away as it always did. confidence high.
        ZombieOverrides.burnRowCold(750, 2500, 1000)

        // ---- B15. the squash squashes two cells away (user list 8; no byte of its own) ----
        // The game's attack rectangle for the squash ends 45 pixels ahead of it and its target search
        // accepts a zombie within another 70, i.e. 115 pixels - one and a half cells, which a player
        // reads as one. 65 more pixels put the rectangle's edge at 110 and the search's far end at 180:
        // the two cells (160) the 95 edition gives the squash, and still short of three.
        PlantOverrides.of(SeedType.SEED_SQUASH).attackRectExtraWidth(SQUASH_ATTACK_EXTRA_WIDTH)

        // ---- B17. the fume-shroom's puff covers the whole row ahead (user list 4; no byte) ----
        // Plant::DoRowAreaDamage damages the zombies of its row whose own rectangle overlaps the
        // plant's attack rectangle, and the game's fume-shroom rectangle ends 400 pixels ahead of it
        // (mX + 60, width 340) - about five cells. 500 more pixels put its edge 900 ahead, past the
        // board's own right edge (800) from any column, so the puff reaches every zombie in front of
        // the plant along its row.
        PlantOverrides.of(SeedType.SEED_FUMESHROOM).attackRectExtraWidth(FUME_ATTACK_EXTRA_WIDTH)

        // ---- B12. the yeti's per-type update moves to the flag zombie (file 0x12b214) ----
        // file 0x12b214 (VA 0x52b214) `cmp [esi+0x24],0x13 -> 0x01` in Zombie::UpdateActions: the branch
        // that runs Zombie::UpdateYeti for a ZOMBIE_YETI(19) now runs it for a ZOMBIE_FLAG(1), so the
        // yeti keeps the plain zombie's behaviour while the flag zombie takes the yeti's phase change.
        // confidence medium (the byte is exact; the reading of the phase is inferred).
        ZombieOverrides.yetiUpdateOnFlag(true)

        // ---- B13. the super-mower toggle also triggers every mower (hook file 0x1b132) ----
        // file 0x1b132 (VA 0x41b132), the `mov byte [edi+0x5762],bl` that writes Board::mSuperMowerMode
        // in Board::SetSuperMowerMode, is redirected to a cave that sets the six mowers of the board
        // (Board+0x100, stride 0x48, field +0x2c) to mMowerState=2 (MOWER_TRIGGERED) before the game's
        // own instruction and loop run. confidence high.
        BoardOverrides.superMowerTriggersMowers(true)
    }

    /** The pixels the squash's attack rectangle is widened by: 110 (see above) less the game's 45. */
    private const val SQUASH_ATTACK_EXTRA_WIDTH = 65

    /** The pixels the fume-shroom's attack rectangle is widened by: past the board from any column. */
    private const val FUME_ATTACK_EXTRA_WIDTH = 500

    /**
     * The pixels the chomper's attack rectangle is widened by: the game's edge is 120 pixels ahead of
     * the plant, a zombie's own rectangle starts about 36 pixels ahead of the zombie, and the search
     * needs the two rectangles to overlap - so a zombie standing two cells (160) away reaches 196 and
     * needs the edge at least there. 90 more pixels put it at 210: the two-cell zombie overlaps by 14
     * pixels and one three cells (240, reaching 276) is still 66 short.
     */
    private const val CHOMPER_ATTACK_EXTRA_WIDTH = 90

    /**
     * The speed every star is fired with ({@code Plant::starFruitFire}: 3.33 pixels a tick, the same
     * magnitude for the four angled cases as for the straight ones), and the speed it flies forward
     * with once it has turned.
     */
    private const val STAR_FORWARD_SPEED = 3.33f

    /**
     * How many rows a star is fired across before it turns: the pair fired along the row is already
     * there, each 30-degree pair crosses one row (its vertical speed is 1.665, a sixth of the speed
     * along the row), and the pair fired straight up and down - the fastest vertical speed of the five,
     * 3.33 - carries the fan to the second row. Those five rows (the plant's, the two beside it and the
     * two beyond) are the starfruit's attack in the 95 edition.
     */
    private fun starRowsBeforeTurning(theVelocityX: Float, theVelocityY: Float): Int = when {
        theVelocityY == 0.0f -> 0
        theVelocityX == 0.0f -> 2
        else -> 1
    }

    /** Whether the mod's star-flight hooks are already in the game's registry. */
    private var starRulesInstalled = false

    /** The health below which an explode-o-nut turns into a cherry bomb (`cmp [esi+0x40],0x28`). */
    private const val EXPLODE_O_NUT_BLAST_HEALTH = 40

    /** The health below which a tall nut turns into a squash (`cmp ecx,0x12c`). */
    private const val TALLNUT_SQUASH_HEALTH = 300

    /** What one bite takes off a plant in 95 (`add dword [esi+0x40],-8`). */
    private const val BITE_DAMAGE = 8

    /** Whether the mod's {@link LawnHooks} implementation is already in the game's registry. */
    private var eatRulesInstalled = false

    /**
     * The two 95 rules that are a rewrite of the game's own code rather than an answer the game asks
     * for, written as the mod-side hook the engine offers for them
     * ({@code LawnHooksRegistry}, {@code ../verification/research/03-board-core.md §7.4}):
     *
     * <ul>
     *   <li><b>R-EX1 - what a bite does</b>, on {@link LawnHooks#onPlantDamage}. The patched
     *       {@code Zombie::EatPlant} (hook file 0x12fcf0 -> cave 0x400563, and file 0x12fcf5 ->
     *       cave 0x40044f) lowers the plant by 8 instead of 4 (the original instruction it replaces is
     *       `add dword [esi+0x40],-4`), and around that one write it does two transformations: an
     *       explode-o-nut at less than 40 health becomes a cherry bomb that goes off in a tick
     *       ({@code mSeedType=SEED_CHERRYBOMB}, {@code mDoSpecialCountdown=1}, {@code mStateCountdown=1}
     *       - the plant's own {@code DoSpecial} does the rest), and a tall nut whose health is below
     *       300 <em>after</em> that bite becomes a squash. The port funnels the game's per-bite write
     *       through {@code Plant::TakeDamage} -> {@code LawnHooks.onPlantDamage}, which is called before
     *       the health is lowered - so the explosion test reads the health the cave reads (before the
     *       bite) and the squash test subtracts this bite itself to see the health the cave's second
     *       half sees.</li>
     *   <li><b>R-S1 - the seed packets</b>, on {@link LawnHooks#onLevelStart}. The patched
     *       {@code Board::InitLevel} tail (hook file 0x1b034 -> cave 0x40064e) walks the seed bank's
     *       ten packets and turns every wall-nut packet into an explode-o-nut one: at level start the
     *       bank is already filled (the player's picks went into it before the level initialised), so
     *       rewriting it there is the same moment the cave runs at. Both halves of the cave's test are
     *       kept - the packet type and the imitated type - and both writes go to the packet type, which
     *       is what the cave does.</li>
     * </ul>
     */
    /**
     * The starfruit's star (rule B9): every star flies the way {@code Plant::starFruitFire} fires it -
     * out of the plant on its own heading - for a short distance, and then turns and flies straight
     * forward along the row it is in, which is the 95 edition's starfruit: a fan-out of five stars
     * that becomes five stars sweeping forward.
     *
     * <p>Taken literally "all of them forward" means all five cases: the forward diagonal and the
     * one fired straight ahead keep their heading (at the fired speed), the backward diagonal and the
     * one fired straight back turn around, and the two fired straight up and down leave the vertical
     * motion behind them - the engine's own {@code MOTION_STAR} branch recomputes the row from the
     * star's height each tick, so once the height stops changing the star holds the row it reached.
     *
     * <p>A star that already has exactly this velocity is left alone, so the rule writes once per star.
     */
    private class Edition95StarRules : LawnHooks {

        /** How far along its heading a star has to fly before it turns, and where that started. */
        private class Flight(val startY: Float, val rows: Int)

        /** The flight of every star still on its way, keyed by the projectile it belongs to. */
        private val flights = HashMap<Long, Flight>()

        override fun onTick(theBoard: Board, theTick: Int) {
            val aRowHeight = (theBoard.gridToPixelY(0, 1) - theBoard.gridToPixelY(0, 0)).toFloat()
            for (aProjectile in theBoard.mProjectiles) {
                if (aProjectile.mDead
                    || aProjectile.mProjectileType != ProjectileType.PROJECTILE_STAR
                    || aProjectile.mMotionType != ProjectileMotion.MOTION_STAR
                ) {
                    flights.remove(aProjectile.mAllocId)
                    continue
                }
                if (aProjectile.mVelX == STAR_FORWARD_SPEED && aProjectile.mVelY == 0.0f) {
                    flights.remove(aProjectile.mAllocId)
                    continue
                }

                // How far this star has to fly along its own heading before it turns: the rows its
                // heading covers times the height of a row. The distance is measured on the star itself,
                // so where exactly it left the plant (the fire offset) and when the engine recomputes
                // its row cannot move the turn - only then does the star sweep the row it has reached.
                val aFlight = flights.getOrPut(aProjectile.mAllocId) {
                    Flight(aProjectile.mPosY, starRowsBeforeTurning(aProjectile.mVelX, aProjectile.mVelY))
                }
                if (abs(aProjectile.mPosY - aFlight.startY) < aFlight.rows * aRowHeight) {
                    continue
                }

                aProjectile.mVelX = STAR_FORWARD_SPEED
                aProjectile.mVelY = 0.0f
                flights.remove(aProjectile.mAllocId)
            }
        }
    }

    private class Edition95EatRules : LawnHooks {

        override fun onPlantDamage(theBoard: Board, thePlant: Plant, theAmount: Int): Int {
            // Only the bite itself: the game's other writers of a plant's health (a zombie's thrown
            // projectile, a spikerock hit, a squash) do not go through this amount, and no projectile
            // in the retail table deals exactly this one. See ../verification/VERIFICATION.md §60.7 for the
            // deviation note.
            if (theAmount != Zombie.DAMAGE_PER_EAT) {
                return theAmount
            }

            if (thePlant.mSeedType == SeedType.SEED_EXPLODE_O_NUT &&
                thePlant.mPlantHealth < EXPLODE_O_NUT_BLAST_HEALTH
            ) {
                thePlant.mSeedType = SeedType.SEED_CHERRYBOMB
                thePlant.mDoSpecialCountdown = 1
                thePlant.mStateCountdown = 1
            }

            if (thePlant.mSeedType == SeedType.SEED_TALLNUT &&
                thePlant.mPlantHealth - BITE_DAMAGE < TALLNUT_SQUASH_HEALTH
            ) {
                thePlant.mSeedType = SeedType.SEED_SQUASH
            }

            return BITE_DAMAGE
        }

        override fun onLevelStart(theBoard: Board, theLevel: Int, theGameMode: Int, theBackground: Int) {
            for (i in 0 until GameConstants.SEEDBANK_MAX) {
                val aPacket: SeedPacket = theBoard.mSeedBank.mSeedPackets[i]
                if (aPacket.mPacketType == SeedType.SEED_WALLNUT ||
                    aPacket.mImitaterType == SeedType.SEED_WALLNUT
                ) {
                    aPacket.mPacketType = SeedType.SEED_EXPLODE_O_NUT
                }
            }
        }
    }
}
