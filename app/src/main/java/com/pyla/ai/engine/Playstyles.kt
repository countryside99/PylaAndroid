package com.pyla.ai.engine

import kotlin.math.hypot
import kotlin.random.Random

data class PlayContext(
    val playerData: FloatArray,
    val enemyData: List<FloatArray>,
    val teammateData: List<FloatArray>,
    val brawler: String,
    val walls: List<FloatArray>,
    val bushes: List<FloatArray>,
    val play: Play,
    val brawlersInfo: Map<String, BrawlerInfo>,
    val isSuperReady: Boolean,
    val isGadgetReady: Boolean,
    val isHyperchargeReady: Boolean,
    val persistentData: PersistentData,
    val secondsToHoldAttackAfterMax: Double,
)

interface Playstyle {
    val id: String
    val displayName: String
    val gamemodes: List<String>
    fun computeMovement(ctx: PlayContext): Pair<Double, Double>?
}

object Playstyles {
    val all: List<Playstyle> = listOf(DefaultPylaPlaystyle, ShowdownFollowerPlaystyle, UniversalPlaystyle)
    val default: Playstyle = all.first()

    fun byId(id: String?): Playstyle = when (id) {
        "showdown_survivor", "showdown_follower" -> ShowdownFollowerPlaystyle
        "universal" -> UniversalPlaystyle
        else -> all.firstOrNull { it.id == id } ?: default
    }
}

internal object PlaystyleCombat {

    fun nowSec() = System.currentTimeMillis() / 1000.0

    fun releaseHeldAttackIfExpired(ctx: PlayContext) {
        val brawlerInfo = ctx.brawlersInfo[ctx.brawler]
        if (!ctx.play.mustHoldAttack(ctx.brawler)) return
        val heldSince = ctx.persistentData.timeSinceHoldingAttack ?: return
        if (nowSec() - heldSince >= (brawlerInfo?.holdAttack ?: 0.0) + ctx.secondsToHoldAttackAfterMax) {
            ctx.play.attack(touchUp = true, touchDown = false)
            ctx.persistentData.timeSinceHoldingAttack = null
        }
    }

    fun engageEnemy(ctx: PlayContext, playerPos: Pair<Double, Double>, enemyCoords: Pair<Double, Double>, enemyDistance: Double) {
        val play = ctx.play
        val brawlerInfo = ctx.brawlersInfo[ctx.brawler]
        val (_, attackRange, superRange) = play.getBrawlerRange(ctx.brawler)
        val mustBrawlerHoldAttack = play.mustHoldAttack(ctx.brawler)

        if (ctx.isSuperReady && ctx.persistentData.timeSinceHoldingAttack == null) {
            val superType = brawlerInfo?.superType ?: "damage"
            val enemyHittable = play.isEnemyHittable(playerPos, enemyCoords, ctx.walls, "super")
            if (enemyHittable && (enemyDistance <= superRange ||
                    superType in setOf("spawnable", "other") ||
                    (ctx.brawler in setOf("stu", "surge") && superType == "charge" && enemyDistance <= superRange + attackRange))) {
                if (ctx.isHyperchargeReady) play.useHypercharge()
                play.useSuper()
            }
        }

        if (enemyDistance <= attackRange) {
            val enemyHittable = play.isEnemyHittable(playerPos, enemyCoords, ctx.walls, "attack")
            if (enemyHittable) {
                if (ctx.isGadgetReady && ctx.persistentData.timeSinceHoldingAttack == null) play.useGadget()
                if (!mustBrawlerHoldAttack) {
                    play.attack()
                } else {
                    if (ctx.persistentData.timeSinceHoldingAttack == null) {
                        ctx.persistentData.timeSinceHoldingAttack = nowSec()
                        play.attack(touchUp = false, touchDown = true)
                    } else {
                        val heldFor = nowSec() - (ctx.persistentData.timeSinceHoldingAttack ?: 0.0)
                        if (heldFor >= (brawlerInfo?.holdAttack ?: 0.0)) {
                            play.attack(touchUp = true, touchDown = false)
                            ctx.persistentData.timeSinceHoldingAttack = null
                        }
                    }
                }
            }
        }
    }

    fun approachOrFleeMovement(ctx: PlayContext, playerPos: Pair<Double, Double>, enemyCoords: Pair<Double, Double>, enemyDistance: Double): Pair<Double, Double> {
        val play = ctx.play
        val safeRange = play.getBrawlerRange(ctx.brawler).first
        val dx = enemyCoords.first - playerPos.first
        val dy = enemyCoords.second - playerPos.second

        val movementOptions = if (enemyDistance > safeRange) {
            listOf(dx to dy, 0.0 to dy, dx to 0.0)
        } else {
            listOf(-dx to -dy, 0.0 to -dy, -dx to 0.0)
        }
        movementOptions.firstOrNull { !play.isPathBlocked(ctx.playerData, it, ctx.walls) }?.let { return it }

        val alternatives = allMoves().toMutableList().apply { shuffle(Random) }
        return alternatives.firstOrNull { !play.isPathBlocked(ctx.playerData, it, ctx.walls) }
            ?: safeRandom(ctx)
    }

    fun allMoves(): List<Pair<Double, Double>> {
        val r = PylaUtils.JOYSTICK_RADIUS.toDouble()
        val d = r * 0.7071
        return listOf(
            0.0 to -r, r to -r, r to 0.0, r to r,
            0.0 to r, -r to r, -r to 0.0, -r to -r,
        )
    }

    fun safeRandom(ctx: PlayContext): Pair<Double, Double> {
        val r = PylaUtils.JOYSTICK_RADIUS.toDouble()
        val options = allMoves().toMutableList().apply { shuffle(Random) }
        for (m in options) {
            if (!ctx.play.isPathBlocked(ctx.playerData, m, ctx.walls)) return m
        }
        val angle = Random.nextDouble(0.0, Math.PI * 2)
        val dist = Random.nextDouble(r * 0.3, r)
        return (Math.cos(angle) * dist) to (Math.sin(angle) * dist)
    }

    fun cardinalMoves(): List<Pair<Double, Double>> = listOf(
        0.0 to -PylaUtils.JOYSTICK_RADIUS.toDouble(),
        -PylaUtils.JOYSTICK_RADIUS.toDouble() to 0.0,
        0.0 to PylaUtils.JOYSTICK_RADIUS.toDouble(),
        PylaUtils.JOYSTICK_RADIUS.toDouble() to 0.0,
    )

    fun strafeAround(ctx: PlayContext, playerPos: Pair<Double, Double>, target: Pair<Double, Double>): Pair<Double, Double>? {
        val dx = target.first - playerPos.first
        val dy = target.second - playerPos.second
        val len = hypot(dx, dy)
        if (len <= 0) return null
        val r = PylaUtils.JOYSTICK_RADIUS.toDouble()
        val side = if ((System.currentTimeMillis() / 2000) % 2 == 0L) 1.0 else -1.0
        val full = (-dy * side * r / len) to (dx * side * r / len)
        if (!ctx.play.isPathBlocked(ctx.playerData, full, ctx.walls)) return full
        val reverse = (dy * side * r / len) to (-dx * side * r / len)
        if (!ctx.play.isPathBlocked(ctx.playerData, reverse, ctx.walls)) return reverse
        return null
    }

    fun avoidPoisonGas(ctx: PlayContext): Pair<Double, Double>? {
        val r = PylaUtils.JOYSTICK_RADIUS.toDouble()
        val gas = ctx.play.isTherePoisonGas(ctx.playerData)
        if (gas.isEmpty()) return null
        var x = 0.0; var y = 0.0
        val up = gas["up"] ?: 0; val down = gas["down"] ?: 0
        val left = gas["left"] ?: 0; val right = gas["right"] ?: 0
        if (up > 0 || down > 0) y = if (up > down) r else -r
        if (left > 0 || right > 0) x = if (left > right) r else -r
        return if (x != 0.0 || y != 0.0) x to y else null
    }
}

object DefaultPylaPlaystyle : Playstyle {

    override val id: String = "default"
    override val displayName: String = "Default Pyla"
    override val gamemodes: List<String> = listOf("3v3", "5v5")

    private val preferredMove = 0.0 to -PylaUtils.JOYSTICK_RADIUS.toDouble()

    override fun computeMovement(ctx: PlayContext): Pair<Double, Double> {
        PlaystyleCombat.releaseHeldAttackIfExpired(ctx)
        val playerPos = ctx.play.getEntityPos(ctx.playerData)

        if (ctx.enemyData.isEmpty()) return noEnemyMovement(ctx)
        val (enemyCoords, enemyDistance) = ctx.play.findClosestEnemy(ctx.enemyData, playerPos, ctx.walls, "attack")
        if (enemyCoords == null || enemyDistance == null) return noEnemyMovement(ctx)

        val (safeRange, attackRange, _) = ctx.play.getBrawlerRange(ctx.brawler)
        val movement = if (enemyDistance <= attackRange && enemyDistance > safeRange) {
            PlaystyleCombat.strafeAround(ctx, playerPos, enemyCoords)
                ?: PlaystyleCombat.approachOrFleeMovement(ctx, playerPos, enemyCoords, enemyDistance)
        } else {
            PlaystyleCombat.approachOrFleeMovement(ctx, playerPos, enemyCoords, enemyDistance)
        }
        PlaystyleCombat.engageEnemy(ctx, playerPos, enemyCoords, enemyDistance)
        return movement
    }

    private fun noEnemyMovement(ctx: PlayContext): Pair<Double, Double> {
        if (!ctx.play.isPathBlocked(ctx.playerData, preferredMove, ctx.walls)) return preferredMove
        val alternatives = PlaystyleCombat.allMoves().toMutableList()
            .apply { remove(preferredMove); remove(0.0 to PylaUtils.JOYSTICK_RADIUS.toDouble()); shuffle(Random) }
        return alternatives.firstOrNull { !ctx.play.isPathBlocked(ctx.playerData, it, ctx.walls) }
            ?: PlaystyleCombat.safeRandom(ctx)
    }
}

object ShowdownFollowerPlaystyle : Playstyle {

    override val id: String = "showdown_follower"
    override val displayName: String = "Showdown Follower"
    override val gamemodes: List<String> = listOf("showdown trio")

    override fun computeMovement(ctx: PlayContext): Pair<Double, Double> {
        PlaystyleCombat.releaseHeldAttackIfExpired(ctx)
        val playerPos = ctx.play.getEntityPos(ctx.playerData)
        val antiGas = PlaystyleCombat.avoidPoisonGas(ctx)

        if (ctx.enemyData.isEmpty()) return antiGas ?: noEnemyMovement(ctx, playerPos)
        val (enemyCoords, enemyDistance) = ctx.play.findClosestEnemy(ctx.enemyData, playerPos, ctx.walls, "attack")
        if (enemyCoords == null || enemyDistance == null) return antiGas ?: noEnemyMovement(ctx, playerPos)

        if (antiGas != null) {
            PlaystyleCombat.engageEnemy(ctx, playerPos, enemyCoords, enemyDistance)
            return antiGas
        }
        val movement = enemyMovement(ctx, playerPos, enemyCoords, enemyDistance)
        PlaystyleCombat.engageEnemy(ctx, playerPos, enemyCoords, enemyDistance)
        return movement
    }

    private fun enemyMovement(ctx: PlayContext, playerPos: Pair<Double, Double>, enemyCoords: Pair<Double, Double>, enemyDistance: Double): Pair<Double, Double> {
        val play = ctx.play
        val safeRange = play.getBrawlerRange(ctx.brawler).first
        val dx = enemyCoords.first - playerPos.first
        val dy = enemyCoords.second - playerPos.second

        val movementOptions = if (enemyDistance > safeRange) {
            listOf(dx to dy, 0.0 to dy, dx to 0.0)
        } else {
            listOf(-dx to -dy, 0.0 to -dy, -dx to 0.0)
        }
        movementOptions.firstOrNull { !play.isPathBlocked(ctx.playerData, it, ctx.walls) }?.let { return it }
        PlaystyleCombat.allMoves().shuffled(Random).firstOrNull { !play.isPathBlocked(ctx.playerData, it, ctx.walls) }?.let { return it }
        return PlaystyleCombat.safeRandom(ctx)
    }

    private fun noEnemyMovement(ctx: PlayContext, playerPos: Pair<Double, Double>): Pair<Double, Double> {
        val play = ctx.play
        val r = PylaUtils.JOYSTICK_RADIUS.toDouble()
        val (teammate, teammateDistance) = play.findClosestTeammate(ctx.teammateData, playerPos)
        if (teammate == null) {
            val tile = play.tileSizePx
            var bush: Pair<Double, Double>? = null
            var bushDist = Double.MAX_VALUE
            for (b in ctx.bushes) {
                val c = play.getEntityPos(b)
                val d = play.getDistance(c, playerPos)
                if (d < bushDist) { bushDist = d; bush = c }
            }
            if (bush != null && bushDist <= tile * 8) {
                if (bushDist < tile) return 0.0 to 0.0
                val bx = bush.first - playerPos.first
                val by = bush.second - playerPos.second
                val options = listOf(bx to by, bx to 0.0, 0.0 to by)
                options.firstOrNull { !play.isPathBlocked(ctx.playerData, it, ctx.walls) }?.let { return it }
            }
            val forward = 0.0 to -r
            if (!play.isPathBlocked(ctx.playerData, forward, ctx.walls)) return forward
            return PlaystyleCombat.safeRandom(ctx)
        }
        if (teammateDistance != null && teammateDistance <= 60.0) return 0.0 to 0.0

        val dx = teammate.first - playerPos.first
        val dy = teammate.second - playerPos.second
        val movementOptions = listOf(dx to dy, dx to 0.0, 0.0 to dy)
        movementOptions.firstOrNull { !play.isPathBlocked(ctx.playerData, it, ctx.walls) }?.let { return it }
        PlaystyleCombat.allMoves().shuffled(Random).firstOrNull { !play.isPathBlocked(ctx.playerData, it, ctx.walls) }?.let { return it }
        return PlaystyleCombat.safeRandom(ctx)
    }
}

object UniversalPlaystyle : Playstyle {

    override val id: String = "universal"
    override val displayName: String = "Universal"
    override val gamemodes: List<String> = listOf("all")

    private val r = PylaUtils.JOYSTICK_RADIUS.toDouble()

    private fun normalize(x: Double, y: Double): Pair<Double, Double> {
        val len = hypot(x, y)
        if (len <= 0) return 0.0 to 0.0
        return (x * r / len) to (y * r / len)
    }

    private fun isBlocked(ctx: PlayContext, move: Pair<Double, Double>): Boolean =
        ctx.play.isPathBlocked(ctx.playerData, move, ctx.walls)

    private fun firstUnblocked(ctx: PlayContext, moves: List<Pair<Double, Double>>, fallback: Pair<Double, Double>): Pair<Double, Double> {
        for (m in moves) if (!isBlocked(ctx, m)) return m
        return fallback
    }

    private fun moveToward(ctx: PlayContext, playerPos: Pair<Double, Double>, target: Pair<Double, Double>): Pair<Double, Double> {
        val dx = target.first - playerPos.first
        val dy = target.second - playerPos.second
        val full = normalize(dx, dy)
        return firstUnblocked(ctx, listOf(full, normalize(dx, 0.0), normalize(0.0, dy)), full)
    }

    private fun moveAwayFrom(ctx: PlayContext, playerPos: Pair<Double, Double>, target: Pair<Double, Double>): Pair<Double, Double> {
        val dx = playerPos.first - target.first
        val dy = playerPos.second - target.second
        val full = normalize(dx, dy)
        return firstUnblocked(ctx, listOf(full, normalize(dx, 0.0), normalize(0.0, dy)), full)
    }

    private fun strafeAround(ctx: PlayContext, playerPos: Pair<Double, Double>, target: Pair<Double, Double>): Pair<Double, Double> {
        val dx = target.first - playerPos.first
        val dy = target.second - playerPos.second
        val side = if ((System.currentTimeMillis() / 2000) % 2 == 0L) 1.0 else -1.0
        val full = normalize(-dy * side, dx * side)
        val reverse = normalize(dy * side, -dx * side)
        return firstUnblocked(ctx, listOf(full, reverse), full)
    }

    private fun randomSafeMovement(ctx: PlayContext): Pair<Double, Double> {
        val options = mutableListOf(
            0.0 to -r, 0.0 to r, -r to 0.0, r to 0.0,
            normalize(r, -r), normalize(-r, -r), normalize(r, r), normalize(-r, r),
        )
        options.shuffle(Random)
        val fallback = Random.nextInt(-75, 76).toDouble() to Random.nextInt(-75, 76).toDouble()
        return firstUnblocked(ctx, options, fallback)
    }

    private fun detectArchetype(info: BrawlerInfo): String = when {
        info.ignoreWallsForAttacks -> "LOBS"
        info.attackRange <= 256 -> "ASSASSIN"
        info.safeRange == 0.0 && info.attackRange <= 350 -> "TANK"
        info.attackRange >= 600 -> "SNIPER"
        else -> "RANGED"
    }

    private fun doAttack(ctx: PlayContext, info: BrawlerInfo) {
        val play = ctx.play
        if (!play.mustHoldAttack(ctx.brawler)) {
            play.attack()
            return
        }
        val since = ctx.persistentData.timeSinceHoldingAttack
        if (since == null) {
            ctx.persistentData.timeSinceHoldingAttack = PlaystyleCombat.nowSec()
            play.attack(touchUp = false, touchDown = true)
        } else if (PlaystyleCombat.nowSec() - since >= info.holdAttack) {
            play.attack(touchUp = true, touchDown = false)
            ctx.persistentData.timeSinceHoldingAttack = null
        }
    }

    private fun tryUseSuper(ctx: PlayContext, playerPos: Pair<Double, Double>, enemyCoords: Pair<Double, Double>, enemyDistance: Double, attackRange: Int, superRange: Int, info: BrawlerInfo) {
        if (!ctx.isSuperReady) return
        if (ctx.persistentData.timeSinceHoldingAttack != null) return
        val enemyHittable = ctx.play.isEnemyHittable(playerPos, enemyCoords, ctx.walls, "super")
        val shouldSuper = enemyHittable && (enemyDistance <= superRange ||
                info.superType in setOf("spawnable", "other") ||
                (ctx.brawler in setOf("stu", "surge") && info.superType == "charge" && enemyDistance <= superRange + attackRange))
        if (shouldSuper) {
            if (ctx.isHyperchargeReady) ctx.play.useHypercharge()
            ctx.play.useSuper()
        }
    }

    private fun tryAttack(ctx: PlayContext, playerPos: Pair<Double, Double>, enemyCoords: Pair<Double, Double>, enemyDistance: Double, attackRange: Int, info: BrawlerInfo, allowWallAttack: Boolean): Boolean {
        if (enemyDistance > attackRange) return false
        val enemyHittable = ctx.play.isEnemyHittable(playerPos, enemyCoords, ctx.walls, "attack")
        if (enemyHittable || allowWallAttack) {
            if (ctx.isGadgetReady && ctx.persistentData.timeSinceHoldingAttack == null &&
                PlaystyleCombat.nowSec() - ctx.persistentData.lastGadgetUse > 3.0) {
                ctx.play.useGadget()
                ctx.persistentData.lastGadgetUse = PlaystyleCombat.nowSec()
            }
            doAttack(ctx, info)
            return true
        }
        return false
    }

    private fun avoidPoisonGas(ctx: PlayContext): Pair<Double, Double>? {
        val gas = ctx.play.isTherePoisonGas(ctx.playerData)
        if (gas.isEmpty()) return null
        var x = 0.0; var y = 0.0
        val up = gas["up"] ?: 0; val down = gas["down"] ?: 0
        val left = gas["left"] ?: 0; val right = gas["right"] ?: 0
        if (up > 0 || down > 0) y = if (up > down) r else -r
        if (left > 0 || right > 0) x = if (left > right) r else -r
        if (x == 0.0 && y == 0.0) return null
        val move = normalize(x, y)
        return firstUnblocked(ctx, listOf(move, x to 0.0, 0.0 to y), move)
    }

    private fun noEnemyMovement(ctx: PlayContext, playerPos: Pair<Double, Double>): Pair<Double, Double> {
        val (teammate, teammateDistance) = ctx.play.findClosestTeammate(ctx.teammateData, playerPos)
        if (teammate != null && (teammateDistance == null || teammateDistance > ctx.play.tileSizePx * 3)) {
            return moveToward(ctx, playerPos, teammate)
        }
        val forward = 0.0 to -r
        if (!isBlocked(ctx, forward)) return forward
        return randomSafeMovement(ctx)
    }

    override fun computeMovement(ctx: PlayContext): Pair<Double, Double> {
        val info = ctx.brawlersInfo[ctx.brawler] ?: return 0.0 to 0.0
        PlaystyleCombat.releaseHeldAttackIfExpired(ctx)
        val (safeRange, attackRange, superRange) = ctx.play.getBrawlerRange(ctx.brawler)
        val playerPos = ctx.play.getEntityPos(ctx.playerData)
        val archetype = detectArchetype(info)

        var enemyCoords: Pair<Double, Double>? = null
        var enemyDistance: Double? = null
        if (ctx.enemyData.isNotEmpty()) {
            val found = ctx.play.findClosestEnemy(ctx.enemyData, playerPos, ctx.walls, "attack")
            enemyCoords = found.first
            enemyDistance = found.second
        }

        var movement: Pair<Double, Double>
        val antiGas = avoidPoisonGas(ctx)

        if (antiGas != null) {
            movement = antiGas
            if (enemyCoords != null && enemyDistance != null) {
                tryUseSuper(ctx, playerPos, enemyCoords, enemyDistance, attackRange, superRange, info)
                tryAttack(ctx, playerPos, enemyCoords, enemyDistance, attackRange, info, archetype == "LOBS")
            }
        } else if (enemyCoords == null || enemyDistance == null) {
            movement = noEnemyMovement(ctx, playerPos)
        } else {
            tryUseSuper(ctx, playerPos, enemyCoords, enemyDistance, attackRange, superRange, info)
            tryAttack(ctx, playerPos, enemyCoords, enemyDistance, attackRange, info, archetype == "LOBS")

            movement = when (archetype) {
                "ASSASSIN" ->
                    if (enemyDistance > attackRange * 0.40) moveToward(ctx, playerPos, enemyCoords)
                    else strafeAround(ctx, playerPos, enemyCoords)
                "TANK" ->
                    if (enemyDistance > attackRange * 0.25) moveToward(ctx, playerPos, enemyCoords)
                    else strafeAround(ctx, playerPos, enemyCoords)
                "SNIPER" -> {
                    val minSafe = attackRange * 0.35
                    val idealFar = attackRange * 0.75
                    when {
                        enemyDistance < minSafe -> moveAwayFrom(ctx, playerPos, enemyCoords)
                        enemyDistance <= idealFar -> strafeAround(ctx, playerPos, enemyCoords)
                        else -> moveToward(ctx, playerPos, enemyCoords)
                    }
                }
                "LOBS" -> {
                    val idealDist = attackRange * 0.55
                    when {
                        enemyDistance < attackRange * 0.45 -> moveAwayFrom(ctx, playerPos, enemyCoords)
                        enemyDistance > idealDist -> moveToward(ctx, playerPos, enemyCoords)
                        else -> strafeAround(ctx, playerPos, enemyCoords)
                    }
                }
                else -> {
                    val minSafe = maxOf(safeRange * 0.50, attackRange * 0.30)
                    val idealFar = attackRange * 0.60
                    when {
                        enemyDistance < minSafe -> moveAwayFrom(ctx, playerPos, enemyCoords)
                        enemyDistance <= idealFar -> strafeAround(ctx, playerPos, enemyCoords)
                        else -> moveToward(ctx, playerPos, enemyCoords)
                    }
                }
            }

            if (isBlocked(ctx, movement)) {
                movement = if (enemyDistance < safeRange) {
                    firstUnblocked(ctx, listOf(
                        moveAwayFrom(ctx, playerPos, enemyCoords),
                        strafeAround(ctx, playerPos, enemyCoords),
                        randomSafeMovement(ctx),
                    ), movement)
                } else {
                    firstUnblocked(ctx, listOf(
                        moveToward(ctx, playerPos, enemyCoords),
                        strafeAround(ctx, playerPos, enemyCoords),
                        randomSafeMovement(ctx),
                    ), movement)
                }
            }
        }

        if (isBlocked(ctx, movement)) movement = randomSafeMovement(ctx)
        return movement
    }
}
